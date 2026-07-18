<?php

declare(strict_types=1);

require_once dirname(__DIR__, 2) . '/private/lib/bootstrap.php';

function payload_policy_assert(bool $condition, string $message): void
{
    if (!$condition) {
        throw new RuntimeException($message);
    }
}

payload_policy_assert(MAX_P2P_ENCRYPTED_PAYLOAD_BYTES === 128 * 1024, 'single P2P payload cap drifted');
payload_policy_assert(MAX_HISTORY_RESPONSE_ENCRYPTED_BYTES === 8 * 1024 * 1024, 'normal history response cap drifted');
payload_policy_assert(MAX_HISTORY_RESPONSE_BYTES === 16 * 1024 * 1024, 'encoded history response cap drifted');
for ($index = 0; $index < 64; $index += 1) {
    payload_policy_assert(
        preg_match('/^[0-9a-f]{32}$/D', generate_group_code_candidate(false)) === 1,
        'stage-one group code is not compatible with old workers'
    );
    payload_policy_assert(
        preg_match('/^[0-9a-z]{8}$/D', generate_group_code_candidate(true)) === 1,
        'stage-two group code does not follow the 8-character contract'
    );
}
payload_policy_assert(!should_generate_current_group_code(false, false), 'stage one did not preserve 32-character writes');
payload_policy_assert(should_generate_current_group_code(true, false), 'stage two did not switch to 8-character writes');
payload_policy_assert(
    should_generate_current_group_code(false, true),
    'a drained stage-one worker could recreate 32-character codes after backfill completion'
);

$lockName = schema_advisory_lock_name('loc');
payload_policy_assert(strlen($lockName) <= 64, 'schema advisory-lock name exceeds the MySQL limit');
payload_policy_assert($lockName === schema_advisory_lock_name(' loc '), 'schema lock name is not normalized');
payload_policy_assert($lockName !== schema_advisory_lock_name('loc_test'), 'different databases share a schema lock');

$emptyDatabaseRejected = false;
try {
    schema_advisory_lock_name('  ');
} catch (RuntimeException) {
    $emptyDatabaseRejected = true;
}
payload_policy_assert($emptyDatabaseRejected, 'empty database name was accepted for schema locking');

$metadata = [
    ['id' => 1, 'encryption_mode' => '', 'encrypted_payload_bytes' => 0, 'encrypted_payload' => ''],
    ['id' => 2, 'encryption_mode' => 'p2p-v1', 'encrypted_payload_bytes' => 4, 'encrypted_payload' => '1'],
    ['id' => 3, 'encryption_mode' => '', 'encrypted_payload_bytes' => 3, 'encrypted_payload' => '1'],
];
$plan = history_client_snapshot_plan($metadata, 4, 7);
payload_policy_assert($plan['eligible'] === true, 'an exactly bounded snapshot was rejected');
payload_policy_assert($plan['total_bytes'] === 7, 'snapshot encrypted-byte total is incorrect');
payload_policy_assert($plan['expectations'] === [2 => 4, 3 => 3], 'snapshot payload expectations are incorrect');

$complete = history_complete_client_snapshot($metadata, [2 => 'abcd', 3 => 'xyz'], 4, 7);
payload_policy_assert(is_array($complete), 'complete snapshot was rejected');
payload_policy_assert($complete[0]['encrypted_payload'] === '', 'plaintext metadata marker was not cleared');
payload_policy_assert($complete[1]['encrypted_payload'] === 'abcd', 'P2P payload was not hydrated');
payload_policy_assert($complete[2]['encrypted_payload'] === 'xyz', 'legacy encrypted payload was not hydrated');
payload_policy_assert(array_column($complete, 'id') === [1, 2, 3], 'complete raw snapshot order or row count changed');

payload_policy_assert(
    history_complete_client_snapshot($metadata, [2 => 'abc', 3 => 'xyz'], 4, 7) === null,
    'length-mismatched snapshot was marked complete'
);
payload_policy_assert(
    history_complete_client_snapshot($metadata, [2 => 'abcd'], 4, 7) === null,
    'partial snapshot was marked complete'
);
payload_policy_assert(
    history_client_snapshot_plan($metadata, 3, 7)['eligible'] === false,
    'single-payload hard limit was not enforced'
);
payload_policy_assert(
    history_client_snapshot_plan($metadata, 4, 6)['eligible'] === false,
    'total snapshot hard limit was not enforced'
);
payload_policy_assert(
    history_client_snapshot_plan([
        ['id' => 9, 'encryption_mode' => 'p2p-v1', 'encrypted_payload_bytes' => 0],
    ], 4, 7)['eligible'] === false,
    'P2P metadata without ciphertext was marked complete'
);

$selected = history_encrypted_payload_expectations([
    $metadata[1],
    $metadata[2],
    ['id' => 4, 'encrypted_payload_bytes' => 5],
], 4);
payload_policy_assert($selected === [2 => 4, 3 => 3], 'selected-row payload plan ignored its per-item bound');

$responseRows = [
    ['id' => 10, 'encryption_mode' => 'p2p-v1', 'encrypted_payload_bytes' => 4],
    ['id' => 11, 'encryption_mode' => 'p2p-v1', 'encrypted_payload_bytes' => 3],
    ['id' => 12, 'encryption_mode' => 'p2p-v1', 'encrypted_payload_bytes' => 5],
    ['id' => 13, 'encryption_mode' => 'p2p-v1', 'encrypted_payload_bytes' => 0],
];
$responseExpectations = history_encrypted_payload_expectations($responseRows, 4, 6);
payload_policy_assert($responseExpectations === [10 => 4], 'normal-response total byte cap is not deterministic');
$unavailableReasons = history_encrypted_payload_unavailable_reasons($responseRows, $responseExpectations, 4);
payload_policy_assert($unavailableReasons[11] === 'response_byte_limit', 'normal-response total overflow is unmarked');
payload_policy_assert($unavailableReasons[12] === 'payload_too_large', 'oversized historical payload is unmarked');
payload_policy_assert($unavailableReasons[13] === 'missing_ciphertext', 'missing historical ciphertext is unmarked');
$hydratedResponse = history_hydrate_encrypted_payloads($responseRows, [10 => 'abcd'], $unavailableReasons);
payload_policy_assert($hydratedResponse[0]['encrypted_payload_available'] === true, 'loaded payload is marked unavailable');
payload_policy_assert($hydratedResponse[1]['encrypted_payload_available'] === false, 'budget-skipped payload is marked available');
payload_policy_assert(
    $hydratedResponse[1]['encrypted_payload_unavailable_reason'] === 'response_byte_limit',
    'budget-skipped payload reason was lost'
);

$duplicateResponseRows = [
    ['id' => 20, 'encryption_mode' => 'p2p-v1', 'encrypted_payload_bytes' => 4],
    ['id' => 20, 'encryption_mode' => 'p2p-v1', 'encrypted_payload_bytes' => 4],
    ['id' => 21, 'encryption_mode' => 'p2p-v1', 'encrypted_payload_bytes' => 3],
];
payload_policy_assert(
    history_encrypted_payload_expectations($duplicateResponseRows, 4, 10) === [20 => 4],
    'normal-response byte budget did not count duplicate serialization'
);
payload_policy_assert(
    history_encrypted_payload_expectations($duplicateResponseRows, 4, 7) === [21 => 3],
    'normal-response budget did not deterministically skip an oversized repeated record'
);
$encodedFixture = ['payload' => str_repeat('"', 32), 'label' => '中文'];
payload_policy_assert(
    history_json_response_bytes($encodedFixture) === strlen((string) json_encode(
        $encodedFixture,
        JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES
    )),
    'encoded response byte calculation does not match json_response'
);

$historySource = file_get_contents(dirname(__DIR__, 2) . '/api/history.php');
$bootstrapSource = file_get_contents(dirname(__DIR__, 2) . '/private/lib/bootstrap.php');
$configSource = file_get_contents(dirname(__DIR__, 2) . '/private/config.php');
payload_policy_assert(
    is_string($historySource) && is_string($bootstrapSource) && is_string($configSource),
    'policy source files are unreadable'
);
foreach ([
    'OCTET_LENGTH(l.encrypted_payload)',
    'history_encrypted_payload_expectations(',
    'history_complete_client_snapshot(',
    '$clientMergeRows = $completeRows;',
    'MAX_HISTORY_RESPONSE_ENCRYPTED_BYTES',
    'MAX_HISTORY_RESPONSE_BYTES',
    'encrypted_payload_unavailable_reason',
    "'complete_snapshot_only'",
    'history_json_response_bytes($response)',
    'if ($clientMergeComplete)',
] as $required) {
    payload_policy_assert(str_contains($historySource, $required), 'history endpoint is missing: ' . $required);
}
payload_policy_assert(
    !str_contains($historySource, "            l.encrypted_payload,\n"),
    'history metadata query still fetches every encrypted payload'
);
payload_policy_assert(
    !str_contains($historySource, '$clientMergeRows = history_merge_locations($completeRows);'),
    'complete client snapshot was pre-merged and lost raw chronology'
);
foreach ([
    'SELECT GET_LOCK(?, ?)',
    'finally {',
    'SELECT RELEASE_LOCK(?)',
    'schema_runtime_state_is_current($pdo)',
    'GROUP_CODE_BACKFILL_SETTING_KEY',
    'LOC_GROUP_CODE_BACKFILL_ENABLED',
    'group_code_backfill_is_current($pdo)',
    'group_code COLLATE utf8mb4_bin NOT REGEXP',
] as $required) {
    payload_policy_assert(str_contains($bootstrapSource, $required), 'schema lock/backfill gate is missing: ' . $required);
}
payload_policy_assert(
    !str_contains($bootstrapSource, 'BINARY group_code NOT REGEXP'),
    'group-code migration still uses MySQL 8-incompatible binary regular expressions'
);
payload_policy_assert(
    str_contains($configSource, '$locGroupCodeBackfillEnabled = true;'),
    'group-code backfill does not default to current 8-character writes'
);
payload_policy_assert(
    str_contains($configSource, '$locGroupCodeBackfillEnabled = $locGroupCodeBackfillParsed;'),
    'an explicit false environment value cannot select the compatibility stage'
);
payload_policy_assert(
    !str_contains($configSource, '$locGroupCodeBackfillEnabled = false;'),
    'group-code backfill still defaults to 32-character writes'
);

fwrite(STDOUT, "history_payload_policy_test: OK\n");
