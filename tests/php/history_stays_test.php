<?php

declare(strict_types=1);

require_once dirname(__DIR__, 2) . '/private/lib/history_stays.php';

date_default_timezone_set('Asia/Shanghai');

function history_test_assert(bool $condition, string $message): void
{
    if (!$condition) {
        throw new RuntimeException($message);
    }
}

function history_test_sources_by_type(array $diagnostics, string $type): array
{
    return array_values(array_filter(
        is_array($diagnostics['sources'] ?? null) ? $diagnostics['sources'] : [],
        static fn (mixed $source): bool => is_array($source) && ($source['type'] ?? '') === $type
    ));
}

function history_test_find_by_field(array $items, string $field, string $value): array
{
    foreach ($items as $item) {
        if (is_array($item) && (string) ($item[$field] ?? '') === $value) {
            return $item;
        }
    }
    throw new RuntimeException(sprintf('missing diagnostic item %s=%s', $field, $value));
}

function history_test_point(
    int $id,
    int $userId,
    string $groupName,
    float $northMeters,
    string $createdAt,
    array $overrides = []
): array {
    $row = [
        'id' => $id,
        'user_id' => $userId,
        'group_name' => $groupName,
        'role' => 'guardian',
        'latitude' => 30.0 + rad2deg($northMeters / HISTORY_EARTH_RADIUS_METERS),
        'longitude' => 120.0,
        'altitude' => null,
        'accuracy' => 3.0,
        'heading' => null,
        'speed' => null,
        'location_meta' => json_encode(['coordinate_system' => 'wgs84']),
        'address_diagnostics' => null,
        'address_mismatch' => 0,
        'encryption_mode' => '',
        'encrypted_payload' => '',
        'p2p_key_version' => 0,
        'created_at' => $createdAt,
        'username' => 'user-' . $userId,
        'display_name' => '成员' . $userId,
    ];
    return array_replace($row, $overrides);
}

$base = '2026-07-16 12:00:00';

foreach ([[24.9, 1], [25.0, 1], [25.1, 2]] as [$distance, $expectedStays]) {
    $rows = [
        history_test_point(1, 11, 'family-a', 0, $base),
        history_test_point(2, 11, 'family-a', $distance, '2026-07-16 12:01:00'),
    ];
    $merged = history_merge_locations($rows);
    history_test_assert(
        count($merged) === $expectedStays,
        sprintf('distance %.1fm produced %d stays, expected %d', $distance, count($merged), $expectedStays)
    );
}

$anchored = history_merge_locations([
    history_test_point(1, 11, 'family-a', 0, $base),
    history_test_point(2, 11, 'family-a', 20, '2026-07-16 12:01:00'),
    history_test_point(3, 11, 'family-a', 40, '2026-07-16 12:02:00'),
]);
history_test_assert(count($anchored) === 2, 'first-point anchoring must prevent transitive drift');
history_test_assert($anchored[0]['id'] === 3 && $anchored[0]['report_count'] === 1, 'new drifted stay is incorrect');
history_test_assert($anchored[1]['id'] === 2 && $anchored[1]['report_count'] === 2, 'anchored stay is incorrect');

$revisit = history_merge_locations([
    history_test_point(1, 11, 'family-a', 0, $base),
    history_test_point(2, 11, 'family-a', 100, '2026-07-16 12:01:00'),
    history_test_point(3, 11, 'family-a', 0, '2026-07-16 12:02:00'),
]);
history_test_assert(count($revisit) === 3, 'a revisit after leaving must remain a separate stay');

$partitions = history_merge_locations([
    history_test_point(1, 11, 'family-a', 0, $base),
    history_test_point(2, 12, 'family-a', 0, '2026-07-16 12:01:00'),
    history_test_point(3, 11, 'family-b', 0, '2026-07-16 12:02:00'),
    history_test_point(4, 11, 'family-b', 5, '2026-07-16 12:03:00'),
]);
history_test_assert(count($partitions) === 3, 'group and user partitions must be independent');
history_test_assert(array_column($partitions, 'id') === [4, 2, 1], 'merged stays must be newest first');
history_test_assert($partitions[0]['report_count'] === 2, 'same-partition records should merge');

$coordinatePartitions = history_merge_locations([
    history_test_point(11, 15, 'coordinate-partitions', 0, $base),
    history_test_point(12, 15, 'coordinate-partitions', 0, '2026-07-16 12:01:00', [
        'location_meta' => json_encode(['coordinate_system' => 'gcj02']),
    ]),
    history_test_point(13, 15, 'coordinate-partitions', 5, '2026-07-16 12:02:00'),
]);
history_test_assert(count($coordinatePartitions) === 2, 'coordinate systems must be independent stay partitions');
history_test_assert(
    $coordinatePartitions[0]['id'] === 13 && $coordinatePartitions[0]['report_count'] === 2,
    'WGS points did not merge across an interleaved GCJ point'
);
history_test_assert(
    $coordinatePartitions[1]['id'] === 12 && $coordinatePartitions[1]['report_count'] === 1,
    'interleaved GCJ point did not remain its own stay'
);

$unverifiable = [
    history_test_point(1, 11, 'p2p-break', 0, $base),
    history_test_point(2, 11, 'p2p-break', 0, '2026-07-16 12:01:00', [
        'encryption_mode' => 'p2p-v1',
        'encrypted_payload' => '{"ciphertext":"opaque"}',
    ]),
    history_test_point(3, 12, 'zero', 0, $base, ['latitude' => 0, 'longitude' => 0]),
    history_test_point(4, 12, 'zero', 0, '2026-07-16 12:01:00', ['latitude' => 0, 'longitude' => 0]),
    history_test_point(5, 13, 'invalid', 0, $base, ['latitude' => 91]),
    history_test_point(6, 13, 'invalid', 0, '2026-07-16 12:01:00', ['latitude' => 91]),
    history_test_point(7, 14, 'systems', 0, $base),
    history_test_point(8, 14, 'systems', 5, '2026-07-16 12:01:00', [
        'location_meta' => json_encode(['coordinate_system' => 'gcj02']),
    ]),
    history_test_point(9, 15, 'unknown-system', 0, $base, [
        'location_meta' => json_encode(['coordinate_system' => 'custom-grid']),
    ]),
    history_test_point(10, 15, 'unknown-system', 5, '2026-07-16 12:01:00', [
        'location_meta' => json_encode(['coordinate_system' => 'custom-grid']),
    ]),
];
$unverifiableMerged = history_merge_locations($unverifiable);
history_test_assert(count($unverifiableMerged) === count($unverifiable), 'opaque, invalid, zero, or mismatched coordinates must stay single');

$addressRows = [
    history_test_point(1, 11, 'family-a', 0, $base, [
        'address_diagnostics' => json_encode(['preferred_address' => '旧地址']),
    ]),
    history_test_point(2, 11, 'family-a', 5, '2026-07-16 12:01:00', [
        'address_diagnostics' => json_encode(['sources' => [['type' => 'ip', 'poi' => '最新可用地点']]]),
        'address_mismatch' => 1,
    ]),
    history_test_point(3, 11, 'family-a', 10, '2026-07-16 12:02:00', [
        'address_diagnostics' => '{not-json',
    ]),
    history_test_point(4, 11, 'family-a', 15, '2026-07-16 12:03:00', [
        'address_diagnostics' => '{}',
        'username' => 'latest-snapshot',
    ]),
];
$addressMerged = history_merge_locations($addressRows);
history_test_assert(count($addressMerged) === 1, 'nearby address rows should be one stay');
$stay = $addressMerged[0];
history_test_assert($stay['id'] === 4 && $stay['username'] === 'latest-snapshot', 'latest row must represent the stay');
history_test_assert(is_string($stay['address_diagnostics']), 'merged diagnostics must remain a database-compatible JSON string');
$stayDiagnostics = json_decode($stay['address_diagnostics'], true);
history_test_assert(is_array($stayDiagnostics), 'merged diagnostics must be valid JSON');
history_test_assert(($stayDiagnostics['preferred_address'] ?? '') === '旧地址', 'usable preferred address must survive later empty diagnostics');
history_test_assert(($stayDiagnostics['sources'][0]['poi'] ?? '') === '最新可用地点', 'latest usable probe source must survive later empty diagnostics');
history_test_assert((int) $stay['address_mismatch'] === 1, 'address mismatch must follow the selected diagnostics');
history_test_assert($stay['first_reported_at'] === $base, 'first report time is incorrect');
history_test_assert($stay['last_reported_at'] === '2026-07-16 12:03:00', 'last report time is incorrect');
history_test_assert($stay['stay_duration_seconds'] === 180 && $stay['report_count'] === 4, 'stay duration or report count is incorrect');

$crossSnapshotDiagnostics = history_merge_locations([
    history_test_point(21, 21, 'diagnostic-union', 0, $base, [
        'address_diagnostics' => [
            'preferred_source' => 'ip',
            'preferred_address' => 'Precise retained IP address',
            'preferred_district' => 'Nanshan',
            'preferred_street' => 'Yuehai Street',
            'sources' => [
                [
                    'type' => 'ip',
                    'provider' => 'early-ip',
                    'address' => 'Precise retained IP address',
                    'district' => 'Nanshan',
                    'street' => 'Yuehai Street',
                    'variants' => [[
                        'label' => 'ipv4',
                        'address' => 'Nested IP address',
                        'detail' => 'Building A',
                    ]],
                ],
                [
                    'type' => 'webrtc',
                    'provider' => 'early-webrtc',
                    'address' => 'Precise retained WebRTC address',
                    'poi' => 'Civic Center',
                    'candidates' => [[
                        'candidate_type' => 'srflx',
                        'address' => 'Nested WebRTC address',
                        'street' => 'Fuzhong Road',
                    ]],
                ],
            ],
        ],
    ]),
    history_test_point(22, 21, 'diagnostic-union', 5, '2026-07-16 12:01:00', [
        'address_diagnostics' => json_encode([
            'preferred_source' => 'gps',
            'preferred_city' => 'Shenzhen',
            'sources' => [[
                'type' => 'gps',
                'provider' => 'latest-gps',
                'address' => 'Latest GPS address',
                'city' => 'Shenzhen',
            ]],
        ]),
    ]),
]);
history_test_assert(count($crossSnapshotDiagnostics) === 1, 'diagnostic union fixture must merge into one stay');
$diagnosticStay = $crossSnapshotDiagnostics[0];
history_test_assert($diagnosticStay['id'] === 22, 'latest row must remain the diagnostic stay representative');
history_test_assert(is_string($diagnosticStay['address_diagnostics']), 'array diagnostics input must be emitted as JSON text');
$diagnosticUnion = json_decode($diagnosticStay['address_diagnostics'], true);
history_test_assert(is_array($diagnosticUnion), 'diagnostic union output is invalid JSON');
history_test_assert(array_column($diagnosticUnion['sources'], 'type') === ['gps', 'ip', 'webrtc'], 'later GPS must not discard earlier IP or WebRTC sources');
$diagnosticSources = array_column($diagnosticUnion['sources'], null, 'type');
history_test_assert(($diagnosticSources['ip']['variants'][0]['detail'] ?? '') === 'Building A', 'nested IP variants must be retained');
history_test_assert(($diagnosticSources['webrtc']['candidates'][0]['street'] ?? '') === 'Fuzhong Road', 'nested WebRTC candidates must be retained');
history_test_assert(($diagnosticUnion['preferred_address'] ?? '') === 'Precise retained IP address', 'less precise newer preferred place must not replace a precise address');

$identityPreserving = history_merge_locations([
    history_test_point(31, 31, 'diagnostic-quality', 0, $base, [
        'address_diagnostics' => json_encode(['sources' => [
            [
                'type' => 'ip',
                'source' => 'server',
                'provider' => 'coarse-provider',
                'ip' => '198.51.100.8',
                'city' => 'Shanghai',
                'variants' => [
                    [
                        'source' => 'ipgeo',
                        'provider' => 'coarse-variant',
                        'ip' => '198.51.100.8',
                        'city' => 'Shanghai',
                    ],
                    [
                        'source' => 'backup-ipgeo',
                        'provider' => 'backup-provider',
                        'ip' => '198.51.100.9',
                        'address' => 'Backup address',
                    ],
                ],
            ],
            [
                'type' => 'webrtc',
                'source' => 'stun-a',
                'provider' => 'STUN A',
                'ip' => '203.0.113.7',
                'stun_server' => 'stun-a.example:3478',
                'address' => 'STUN A address',
            ],
        ]]),
    ]),
    history_test_point(32, 31, 'diagnostic-quality', 5, '2026-07-16 12:01:00', [
        'address_diagnostics' => ['sources' => [
            [
                'type' => 'ip',
                'source' => 'server',
                'provider' => 'precise-provider',
                'server_ip' => '198.51.100.8',
                'ipv4' => '198.51.100.8',
                'address' => 'Shanghai exact address',
                'detail' => 'Building 8',
                'variants' => [
                    [
                        'source' => 'ipgeo',
                        'provider' => 'precise-variant',
                        'server_ip' => '198.51.100.8',
                        'address' => 'Variant exact address',
                        'detail' => 'Room 9',
                        'accuracy' => 8,
                    ],
                    [
                        'source' => 'third-ipgeo',
                        'provider' => 'third-provider',
                        'ipv4' => '198.51.100.10',
                        'address' => 'Third address',
                    ],
                ],
            ],
            [
                'type' => 'webrtc',
                'source' => 'stun-b',
                'provider' => 'STUN B',
                'ip' => '203.0.113.7',
                'stun_server' => 'stun-b.example:3478',
                'address' => 'STUN B address',
            ],
        ]],
    ]),
]);
$identityDiagnostics = json_decode((string) $identityPreserving[0]['address_diagnostics'], true);
history_test_assert(is_array($identityDiagnostics), 'identity-preserving diagnostics are invalid');
$identityIpSources = history_test_sources_by_type($identityDiagnostics, 'ip');
history_test_assert(count($identityIpSources) === 1, 'the same IP/source identity was not deduplicated');
$identityIp = $identityIpSources[0];
history_test_assert(($identityIp['provider'] ?? '') === 'precise-provider', 'source packages were mixed instead of selecting the precise package');
history_test_assert(($identityIp['address'] ?? '') === 'Shanghai exact address', 'precise source address was not retained');
history_test_assert(($identityIp['detail'] ?? '') === 'Building 8', 'precise source detail was not retained');
$identityVariants = is_array($identityIp['variants'] ?? null) ? $identityIp['variants'] : [];
history_test_assert(count($identityVariants) === 3, 'nested IP variants were not merged by identity');
$preciseVariant = history_test_find_by_field($identityVariants, 'server_ip', '198.51.100.8');
history_test_assert(($preciseVariant['provider'] ?? '') === 'precise-variant', 'variant provider came from another package');
history_test_assert(($preciseVariant['detail'] ?? '') === 'Room 9', 'variant detail came from another package');
history_test_assert((int) ($preciseVariant['accuracy'] ?? 0) === 8, 'variant accuracy was lost');
$identityWebRtcSources = history_test_sources_by_type($identityDiagnostics, 'webrtc');
history_test_assert(count($identityWebRtcSources) === 2, 'distinct STUN identities were collapsed');
history_test_find_by_field($identityWebRtcSources, 'stun_server', 'stun-a.example:3478');
history_test_find_by_field($identityWebRtcSources, 'stun_server', 'stun-b.example:3478');

$sameTypeTie = history_merge_locations([
    history_test_point(41, 41, 'diagnostic-recency', 0, $base, [
        'address_diagnostics' => json_encode(['sources' => [[
            'type' => 'webrtc',
            'source' => 'stun-shared',
            'stun_server' => 'stun-shared.example:3478',
            'provider' => 'equally-precise-earlier',
            'city' => 'Shenzhen',
        ]]]),
    ]),
    history_test_point(42, 41, 'diagnostic-recency', 5, '2026-07-16 12:01:00', [
        'address_diagnostics' => json_encode(['sources' => [[
            'type' => 'webrtc',
            'source' => 'stun-shared',
            'stun_server' => 'stun-shared.example:3478',
            'provider' => 'equally-precise-latest',
            'city' => 'Shenzhen',
        ]]]),
    ]),
]);
$tieDiagnostics = json_decode((string) $sameTypeTie[0]['address_diagnostics'], true);
history_test_assert(($tieDiagnostics['sources'][0]['provider'] ?? '') === 'equally-precise-latest', 'equally precise same-type sources must prefer the latest snapshot');

$sameTime = history_merge_locations([
    history_test_point(2, 11, 'family-a', 5, $base),
    history_test_point(1, 11, 'family-a', 0, $base),
]);
history_test_assert(count($sameTime) === 1 && $sameTime[0]['id'] === 2, 'same-time rows must use ascending id before choosing the latest representative');

$view = history_compose_view([
    history_test_point(1, 11, 'family-a', 0, $base),
    history_test_point(2, 11, 'family-a', 5, '2026-07-16 12:01:00'),
    history_test_point(3, 11, 'family-a', 100, '2026-07-16 12:02:00'),
    history_test_point(4, 12, 'family-a', 0, '2026-07-16 12:03:00'),
    history_test_point(5, 12, 'family-a', 100, '2026-07-16 12:04:00'),
], 2, 2, 1);
history_test_assert($view['total'] === 4 && $view['total_pages'] === 2, 'count and pages must use merged stays');
history_test_assert($view['page'] === 2 && array_column($view['rows'], 'id') === [3, 2], 'pagination must run after merge in newest-first order');
history_test_assert(array_column($view['map_rows'], 'id') === [5, 3], 'map limit must apply once per user after merge');

$mapSource = file_get_contents(dirname(__DIR__, 2) . '/api/history_map_webview.php');
history_test_assert(is_string($mapSource), 'history map source is unreadable');
foreach ([
    'function firstGpsSource(diagnostics)',
    'const gpsSource = firstGpsSource(diagnostics);',
    'gpsSource && gpsSource.address',
    'gpsSource && gpsSource.district',
    'gpsSource && gpsSource.street',
    'gpsSource && gpsSource.detail',
    'gpsSource && gpsSource.poi',
    'gpsSource && gpsSource.postal_code',
    'const gps = normalizeRecord(record, index);',
    'if (gps) items.push(gps);',
    "if (item.type !== 'gps') return;",
    'const path = group.map((item) => [item.lng, item.lat]);',
    'record.first_reported_at',
    'record.last_reported_at',
    'record.stay_duration_seconds',
    'record.report_count',
] as $required) {
    history_test_assert(str_contains($mapSource, $required), 'history map is missing: ' . $required);
}
foreach ([
    'function normalizeDiagnosticSource',
    '.marker.ip',
    '.marker.webrtc',
    'diagnostics.preferred_address',
    'diagnostics.preferred_coordinate_system',
] as $forbidden) {
    history_test_assert(!str_contains($mapSource, $forbidden), 'history map must not expose network-derived map data: ' . $forbidden);
}

fwrite(STDOUT, "history_stays_test: OK\n");
