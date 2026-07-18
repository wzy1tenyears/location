<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';
require_once __DIR__ . '/../private/lib/history_stays.php';

require_app_user_agent();

$historyPdo = null;
$historyTransactionStarted = false;
try {
    $user = require_user();
    $data = request_data();
    $membership = require_user_membership($user, selected_group_name_from_request());
    $page = (int) ($data['page'] ?? 1);
    $perPage = (int) ($data['per_page'] ?? 20);
    $mapPerUser = (int) ($data['map_per_user'] ?? 20);
    $filterUserId = (int) ($data['user_id'] ?? 0);
    $clientMergeSnapshotRequested = filter_var(
        $data['client_merge_snapshot'] ?? false,
        FILTER_VALIDATE_BOOLEAN
    );

    if (!in_array($perPage, [20, 50, 100], true)) {
        $perPage = 20;
    }
    if (!in_array($mapPerUser, [20, 50, 100], true)) {
        $mapPerUser = 20;
    }

    if ($page < 1) {
        $page = 1;
    }

    $membersSql = '
        SELECT
            u.id AS user_id,
            u.username,
            u.display_name,
            ug.role
        FROM users u
        INNER JOIN user_groups ug ON ug.user_id = u.id
        WHERE ug.group_name = ? AND u.is_active = 1
    ';
    $membersSql .= ' ORDER BY ug.role ASC, u.username ASC';

    $membersStmt = db()->prepare($membersSql);
    $membersStmt->execute([(string) $membership['group_name']]);
    $allMembers = $membersStmt->fetchAll();
    $members = $allMembers;

    if ($filterUserId > 0) {
        $members = array_values(array_filter(
            $allMembers,
            static fn (array $member): bool => (int) $member['user_id'] === $filterUserId
        ));
    }

    if ($filterUserId > 0 && !$members) {
        throw new RuntimeException('无权查看这个成员。');
    }

    $userFilterSql = $filterUserId > 0 ? ' AND l.user_id = ?' : '';
    $historyParams = [(string) $membership['group_name']];
    if ($filterUserId > 0) {
        $historyParams[] = $filterUserId;
    }

    $fetchEncryptedPayloads = static function (PDO $pdo, string $groupName, array $expectations): array {
        if (!$expectations) {
            return [];
        }

        $payloads = [];
        foreach (array_chunk(array_keys($expectations), 250) as $idChunk) {
            $placeholders = implode(',', array_fill(0, count($idChunk), '?'));
            $stmt = $pdo->prepare(
                'SELECT id, encrypted_payload
                 FROM locations
                 WHERE group_name = ? AND id IN (' . $placeholders . ')'
            );
            $stmt->execute(array_merge([$groupName], $idChunk));
            foreach ($stmt->fetchAll() as $payloadRow) {
                $id = (int) ($payloadRow['id'] ?? 0);
                $payload = $payloadRow['encrypted_payload'] ?? null;
                if (isset($expectations[$id])
                    && is_string($payload)
                    && strlen($payload) === (int) $expectations[$id]) {
                    $payloads[$id] = $payload;
                }
            }
        }

        return $payloads;
    };

    $historyPdo = db();
    if (!$historyPdo->inTransaction()) {
        $historyPdo->exec('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ');
        $historyPdo->beginTransaction();
        $historyTransactionStarted = true;
    }

    $historyStmt = $historyPdo->prepare('
        SELECT
            l.id,
            l.user_id,
            l.group_name,
            l.role,
            l.latitude,
            l.longitude,
            l.altitude,
            l.accuracy,
            l.heading,
            l.speed,
            l.location_meta,
            l.address_diagnostics,
            l.address_mismatch,
            l.encryption_mode,
            CASE
                WHEN COALESCE(OCTET_LENGTH(l.encrypted_payload), 0) > 0 THEN \'1\'
                ELSE \'\'
            END AS encrypted_payload,
            COALESCE(OCTET_LENGTH(l.encrypted_payload), 0) AS encrypted_payload_bytes,
            l.p2p_key_version,
            l.created_at,
            u.username,
            u.display_name
        FROM locations l
        INNER JOIN users u ON u.id = l.user_id
        INNER JOIN user_groups ug ON ug.user_id = l.user_id AND ug.group_name = l.group_name
        WHERE l.group_name = ? AND u.is_active = 1' . $userFilterSql . '
        ORDER BY l.user_id ASC, l.created_at ASC, l.id ASC
    ');
    $historyStmt->execute($historyParams);
    $rawHistoryRows = $historyStmt->fetchAll();
    $view = history_compose_view($rawHistoryRows, $page, $perPage, $mapPerUser);
    $rows = $view['rows'];
    $mapRows = $view['map_rows'];
    $metadataRows = $rows;
    $metadataMapRows = $mapRows;
    $page = (int) $view['page'];
    $total = (int) $view['total'];
    $totalPages = (int) $view['total_pages'];

    $clientMergeComplete = false;
    $clientMergeRows = null;
    $snapshotPayloads = [];
    if ($clientMergeSnapshotRequested) {
        $snapshotPlan = history_client_snapshot_plan(
            $rawHistoryRows,
            MAX_P2P_ENCRYPTED_PAYLOAD_BYTES,
            MAX_CLIENT_MERGE_SNAPSHOT_ENCRYPTED_BYTES
        );
        if ($snapshotPlan['eligible']) {
            $snapshotPayloads = $fetchEncryptedPayloads(
                $historyPdo,
                (string) $membership['group_name'],
                $snapshotPlan['expectations']
            );
            $completeRows = history_complete_client_snapshot(
                $rawHistoryRows,
                $snapshotPayloads,
                MAX_P2P_ENCRYPTED_PAYLOAD_BYTES,
                MAX_CLIENT_MERGE_SNAPSHOT_ENCRYPTED_BYTES
            );
            if ($completeRows !== null) {
                $clientMergeComplete = true;
                $clientMergeRows = $completeRows;
            }
        }
    }

    $selectedRows = array_merge($metadataRows, $metadataMapRows);
    $selectedPayloadExpectations = history_encrypted_payload_expectations(
        $selectedRows,
        MAX_P2P_ENCRYPTED_PAYLOAD_BYTES,
        MAX_HISTORY_RESPONSE_ENCRYPTED_BYTES
    );
    $normalPayloadUnavailableReasons = history_encrypted_payload_unavailable_reasons(
        $selectedRows,
        $selectedPayloadExpectations,
        MAX_P2P_ENCRYPTED_PAYLOAD_BYTES
    );
    if ($clientMergeComplete) {
        $selectedPayloads = [];
        $selectedPayloadUnavailableReasons = history_encrypted_payload_unavailable_reasons(
            $selectedRows,
            [],
            MAX_P2P_ENCRYPTED_PAYLOAD_BYTES,
            'complete_snapshot_only'
        );
    } else {
        $selectedPayloads = $fetchEncryptedPayloads(
            $historyPdo,
            (string) $membership['group_name'],
            $selectedPayloadExpectations
        );
        $selectedPayloadUnavailableReasons = $normalPayloadUnavailableReasons;
    }
    $rows = history_hydrate_encrypted_payloads($metadataRows, $selectedPayloads, $selectedPayloadUnavailableReasons);
    $mapRows = history_hydrate_encrypted_payloads($metadataMapRows, $selectedPayloads, $selectedPayloadUnavailableReasons);
    if ($historyTransactionStarted) {
        $historyPdo->commit();
        $historyTransactionStarted = false;
    }

    $historyPayload = static function (array $row): array {
        $diagnostics = null;
        if (!empty($row['address_diagnostics'])) {
            $decoded = json_decode((string) $row['address_diagnostics'], true);
            $diagnostics = is_array($decoded) ? $decoded : null;
        }

        return [
            'id' => (int) $row['id'],
            'user_id' => (int) $row['user_id'],
            'username' => $row['username'],
            'display_name' => $row['display_name'],
            'role' => normalize_role((string) $row['role']),
            'role_label' => role_label((string) $row['role']),
            'group_name' => $row['group_name'],
            'latitude' => (float) $row['latitude'],
            'longitude' => (float) $row['longitude'],
            'altitude' => $row['altitude'] === null ? null : (float) $row['altitude'],
            'accuracy' => $row['accuracy'] === null ? null : (float) $row['accuracy'],
            'heading' => $row['heading'] === null ? null : (float) $row['heading'],
            'speed' => $row['speed'] === null ? null : (float) $row['speed'],
            'location_meta' => !empty($row['location_meta']) ? json_decode((string) $row['location_meta'], true) : null,
            'address_mismatch' => (int) ($row['address_mismatch'] ?? 0) === 1,
            'address_diagnostics' => $diagnostics,
            'encryption_mode' => (string) ($row['encryption_mode'] ?? ''),
            'encrypted_payload' => (string) ($row['encrypted_payload'] ?? ''),
            'encrypted_payload_available' => (bool) ($row['encrypted_payload_available'] ?? true),
            'encrypted_payload_unavailable_reason' => (string) ($row['encrypted_payload_unavailable_reason'] ?? ''),
            'p2p_key_version' => (int) ($row['p2p_key_version'] ?? 0),
            'created_at' => format_datetime((string) $row['created_at']),
            'first_reported_at' => format_datetime((string) ($row['first_reported_at'] ?? $row['created_at'])),
            'last_reported_at' => format_datetime((string) ($row['last_reported_at'] ?? $row['created_at'])),
            'stay_duration_seconds' => max(0, (int) ($row['stay_duration_seconds'] ?? 0)),
            'report_count' => max(1, (int) ($row['report_count'] ?? 1)),
        ];
    };

    $history = array_map($historyPayload, $rows);
    $mapHistory = array_map($historyPayload, $mapRows);
    $clientMergeHistory = $clientMergeComplete && is_array($clientMergeRows)
        ? array_map($historyPayload, $clientMergeRows)
        : null;

    $response = [
        'ok' => true,
        'user' => public_user_payload_for_group($user, $membership),
        'selected_group' => group_payload($membership),
        'members' => array_map(static function (array $member): array {
            return [
                'user_id' => (int) $member['user_id'],
                'username' => $member['username'],
                'display_name' => $member['display_name'],
                'role' => normalize_role((string) $member['role']),
                'role_label' => role_label((string) $member['role']),
            ];
        }, $allMembers),
        'history' => $history,
        'map_history' => $mapHistory,
        'pagination' => [
            'page' => $page,
            'per_page' => $perPage,
            'map_per_user' => $mapPerUser,
            'total' => $total,
            'total_pages' => $totalPages,
            'user_id' => $filterUserId,
        ],
        'client_merge_complete' => $clientMergeComplete,
        'server_time' => date('Y-m-d H:i:s'),
    ];
    if ($clientMergeComplete) {
        $response['client_merge_history'] = $clientMergeHistory;
    }

    $responseBytes = history_json_response_bytes($response);
    if ($responseBytes === null) {
        throw new RuntimeException('历史数据无法编码。');
    }
    if ($clientMergeComplete && $responseBytes > MAX_HISTORY_RESPONSE_BYTES) {
        $normalPayloads = array_intersect_key($snapshotPayloads, $selectedPayloadExpectations);
        $fallbackRows = history_hydrate_encrypted_payloads(
            $metadataRows,
            $normalPayloads,
            $normalPayloadUnavailableReasons
        );
        $fallbackMapRows = history_hydrate_encrypted_payloads(
            $metadataMapRows,
            $normalPayloads,
            $normalPayloadUnavailableReasons
        );
        $response['history'] = array_map($historyPayload, $fallbackRows);
        $response['map_history'] = array_map($historyPayload, $fallbackMapRows);
        $response['client_merge_complete'] = false;
        unset($response['client_merge_history']);
        $responseBytes = history_json_response_bytes($response);
    }
    if ($responseBytes === null || $responseBytes > MAX_HISTORY_RESPONSE_BYTES) {
        json_response([
            'ok' => false,
            'message' => '历史数据响应超过安全上限，请筛选单个成员后重试。',
        ], 413);
    }
    json_response($response);
} catch (Throwable $th) {
    if ($historyTransactionStarted && $historyPdo instanceof PDO && $historyPdo->inTransaction()) {
        $historyPdo->rollBack();
    }
    json_response(['ok' => false, 'message' => api_error_message($th)], 500);
}
