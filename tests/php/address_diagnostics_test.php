<?php

declare(strict_types=1);

if (PHP_SAPI !== 'cli') {
    throw new RuntimeException('This test is CLI-only.');
}

final class AddressTestHttpResponse extends RuntimeException
{
    public function __construct(public readonly array $payload, public readonly int $status)
    {
        parent::__construct('HTTP ' . $status, $status);
    }
}

function json_response(array $data, int $status = 200): never
{
    throw new AddressTestHttpResponse($data, $status);
}

define('REPORT_LOCATION_FUNCTIONS_ONLY', true);
define('MAX_P2P_ENCRYPTED_PAYLOAD_BYTES', 128 * 1024);
require_once dirname(__DIR__, 2) . '/api/report_location.php';

function address_test_assert(bool $condition, string $message): void
{
    if (!$condition) {
        throw new RuntimeException($message);
    }
}

function address_test_source(array $sources, string $type): array
{
    foreach ($sources as $source) {
        if (($source['type'] ?? '') === $type) {
            return $source;
        }
    }

    throw new RuntimeException('missing source type: ' . $type);
}

$emptyEncryptedPayload = ['iv' => 'v', 'ciphertext' => ''];
$emptyEncryptedJson = json_encode($emptyEncryptedPayload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
address_test_assert(is_string($emptyEncryptedJson), 'failed to build P2P boundary fixture');
$ciphertextAtLimit = str_repeat(
    'x',
    MAX_P2P_ENCRYPTED_PAYLOAD_BYTES - strlen($emptyEncryptedJson)
);
$encodedAtLimit = p2p_encrypted_payload_from_request([
    'iv' => 'v',
    'ciphertext' => $ciphertextAtLimit,
]);
address_test_assert(is_string($encodedAtLimit), 'P2P payload at the byte limit was rejected');
address_test_assert(strlen($encodedAtLimit) === MAX_P2P_ENCRYPTED_PAYLOAD_BYTES, 'P2P boundary fixture is not exact');

$p2pOversizeResponse = null;
try {
    p2p_encrypted_payload_from_request([
        'iv' => 'v',
        'ciphertext' => $ciphertextAtLimit . 'x',
    ]);
} catch (AddressTestHttpResponse $exception) {
    $p2pOversizeResponse = $exception;
}
address_test_assert($p2pOversizeResponse instanceof AddressTestHttpResponse, 'oversized P2P payload was accepted');
address_test_assert($p2pOversizeResponse->status === 422, 'oversized P2P payload must return HTTP 422');

$preciseFields = [
    'address' => 'Shenzhen Nanshan Science Park Building A',
    'detail' => 'Building A, room 801',
    'poi' => 'Science Park',
    'district' => 'Nanshan',
    'street' => 'Yuehai Street',
    'postal_code' => '518000',
    'city' => 'Shenzhen',
    'region' => 'Guangdong',
    'country' => 'CN',
    'coordinate_system' => 'wgs84',
    'source' => 'amap-reverse',
];

$diagnostics = sanitize_address_diagnostics([
    'preferred_source' => 'gps',
    'preferred_address' => $preciseFields['address'],
    'preferred_detail' => $preciseFields['detail'],
    'preferred_poi' => $preciseFields['poi'],
    'preferred_district' => $preciseFields['district'],
    'preferred_street' => $preciseFields['street'],
    'preferred_postal_code' => $preciseFields['postal_code'],
    'preferred_city' => $preciseFields['city'],
    'preferred_region' => $preciseFields['region'],
    'preferred_country' => $preciseFields['country'],
    'preferred_coordinate_system' => $preciseFields['coordinate_system'],
    'preferred_latitude' => 22.5431,
    'preferred_longitude' => 114.0579,
    'sources' => [
        ['type' => 'gps', 'provider' => 'gps-one', 'address' => str_repeat('unstructured-', 60)],
        ['type' => 'gps', 'provider' => 'gps-two', 'address' => 'city district'],
        ['type' => 'gps', 'provider' => 'gps-three', 'address' => 'city district street'],
        ['type' => 'gps', 'provider' => 'precise-gps'] + $preciseFields,
        [
            'type' => 'ip',
            'provider' => 'ip-provider',
            'latitude' => 31.2304,
            'longitude' => 121.4737,
            'variants' => [[
                'label' => 'ipv4',
                'ip' => '198.51.100.8',
                'latitude' => 31.2304,
                'longitude' => 121.4737,
            ] + $preciseFields],
        ] + $preciseFields,
        [
            'type' => 'webrtc',
            'provider' => 'stun-geocoder',
            'latitude' => 22.5431,
            'longitude' => 114.0579,
            'candidates' => [[
                'ip' => '203.0.113.7',
                'candidate_type' => 'srflx',
                'latitude' => 22.5431,
                'longitude' => 114.0579,
            ] + $preciseFields],
        ] + $preciseFields,
    ],
]);

address_test_assert(is_array($diagnostics), 'sanitizer returned null');
$sources = $diagnostics['sources'];
address_test_assert(count($sources) === 3, 'sanitizer must retain one source of each supported type');
address_test_assert(array_column($sources, 'type') === ['gps', 'ip', 'webrtc'], 'source type order is unstable');
address_test_assert(address_test_source($sources, 'gps')['provider'] === 'precise-gps', 'best GPS source was not selected');

foreach ([
    'preferred_address', 'preferred_detail', 'preferred_poi', 'preferred_district',
    'preferred_street', 'preferred_postal_code', 'preferred_city', 'preferred_region',
    'preferred_country', 'preferred_coordinate_system',
] as $field) {
    $sourceField = substr($field, strlen('preferred_'));
    address_test_assert(
        ($diagnostics[$field] ?? null) === ($preciseFields[$sourceField] ?? null),
        'preferred field was not retained: ' . $field
    );
}

$ip = address_test_source($sources, 'ip');
$variant = $ip['variants'][0] ?? [];
$webrtc = address_test_source($sources, 'webrtc');
$candidate = $webrtc['candidates'][0] ?? [];
foreach (['source', 'district', 'street', 'detail', 'poi', 'postal_code', 'coordinate_system'] as $field) {
    foreach (['IP source' => $ip, 'IP variant' => $variant, 'WebRTC source' => $webrtc, 'WebRTC candidate' => $candidate] as $label => $item) {
        address_test_assert(($item[$field] ?? null) === $preciseFields[$field], $label . ' lost field: ' . $field);
    }
}

$nestedSelection = sanitize_address_diagnostics([
    'sources' => [
        ['type' => 'gps', 'provider' => 'country-only', 'country' => 'CN'],
        ['type' => 'gps', 'provider' => 'displayable-address', 'address' => str_repeat('complete-address-', 40)],
        ['type' => 'ip', 'provider' => 'coarse-ip', 'country' => 'CN'],
        [
            'type' => 'ip',
            'provider' => 'nested-ip',
            'variants' => [[
                'address' => 'Precise IP address',
                'district' => 'Nanshan',
                'street' => 'Yuehai Street',
                'detail' => 'Building A',
            ]],
        ],
        ['type' => 'webrtc', 'provider' => 'coarse-webrtc', 'country' => 'CN'],
        [
            'type' => 'webrtc',
            'provider' => 'nested-webrtc',
            'candidates' => [[
                'address' => 'Precise WebRTC address',
                'district' => 'Futian',
                'street' => 'Fuzhong Road',
                'poi' => 'Civic Center',
            ]],
        ],
    ],
]);
address_test_assert(is_array($nestedSelection), 'nested selection fixture was not sanitized');
address_test_assert(address_test_source($nestedSelection['sources'], 'gps')['provider'] === 'displayable-address', 'country-only GPS source beat a displayable address');
address_test_assert(address_test_source($nestedSelection['sources'], 'ip')['provider'] === 'nested-ip', 'best nested IP address did not select its source');
address_test_assert(address_test_source($nestedSelection['sources'], 'webrtc')['provider'] === 'nested-webrtc', 'best nested WebRTC address did not select its source');
address_test_assert(
    diagnostics_score_compare(
        diagnostics_address_precision_score(['address' => 'Shenzhen Nanshan Science Park Building A']),
        diagnostics_address_precision_score(['district' => 'Nanshan'])
    ) > 0,
    'district-only evidence beat a complete display address'
);
address_test_assert(
    diagnostics_score_compare(
        diagnostics_address_precision_score(['address' => 'Main Road']),
        diagnostics_address_precision_score(['postal_code' => '518000'])
    ) > 0,
    'postal-only evidence beat a short display address'
);

$candidateFixture = [];
for ($index = 0; $index < 12; $index += 1) {
    $candidateFixture[] = ['ip' => 'candidate-' . $index, 'country' => 'CN'];
}
$candidateFixture[] = [
    'ip' => 'candidate-best',
    'address' => 'Precise candidate address',
    'district' => 'Nanshan',
    'street' => 'Yuehai Street',
    'detail' => 'Building A',
];
$selectedCandidates = sanitize_probe_items($candidateFixture, 'webrtc_candidate');
address_test_assert(count($selectedCandidates) === 12, 'candidate storage limit changed');
address_test_assert($selectedCandidates[0]['ip'] === 'candidate-best', 'best thirteenth candidate was discarded');
address_test_assert($selectedCandidates[1]['ip'] === 'candidate-0', 'equal-score candidate order is not stable');
address_test_assert($selectedCandidates[11]['ip'] === 'candidate-10', 'lowest-ranked candidate was not the one removed');

$variants = [];
$candidates = [];
for ($index = 0; $index < 12; $index += 1) {
    $variants[] = [
        'ip' => '198.51.100.8',
        'address' => str_repeat('variant-address-', 50),
        'detail' => str_repeat('variant-detail-', 30),
        'district' => 'district',
        'street' => 'street',
        'coordinate_system' => 'wgs84',
    ];
    $candidates[] = [
        'ip' => '203.0.113.7',
        'address' => str_repeat('candidate-address-', 50),
        'detail' => str_repeat('candidate-detail-', 30),
        'district' => 'district',
        'street' => 'street',
        'coordinate_system' => 'wgs84',
    ];
}

$oversized = sanitize_address_diagnostics([
    'preferred_address' => 'Selected precise address',
    'preferred_detail' => 'Building A',
    'sources' => [
        ['type' => 'ip', 'address' => 'Selected IP address', 'variants' => $variants],
        ['type' => 'webrtc', 'address' => 'Selected WebRTC address', 'candidates' => $candidates],
    ],
]);
address_test_assert(is_array($oversized), 'oversize fixture was not sanitized');
$unboundedJson = json_encode($oversized, JSON_UNESCAPED_SLASHES);
address_test_assert(is_string($unboundedJson) && strlen($unboundedJson) > 3500, 'oversize fixture is too small');

$boundedJson = encode_address_diagnostics($oversized, 3500);
address_test_assert(is_string($boundedJson), 'bounded diagnostics were not encoded');
address_test_assert(strlen($boundedJson) <= 3500, 'bounded diagnostics exceed the byte limit');
$bounded = json_decode($boundedJson, true, 512, JSON_THROW_ON_ERROR);
address_test_assert($bounded['preferred_address'] === 'Selected precise address', 'preferred address was pruned');
foreach ($bounded['sources'] as $source) {
    address_test_assert(!array_key_exists('variants', $source), 'oversized variants were not structurally removed');
    address_test_assert(!array_key_exists('candidates', $source), 'oversized candidates were not structurally removed');
}
address_test_assert(isset($oversized['sources'][0]['variants']), 'encoder mutated original variants');
address_test_assert(isset($oversized['sources'][1]['candidates']), 'encoder mutated original candidates');

$thrown = null;
try {
    encode_address_diagnostics([
        'preferred_address' => str_repeat('irreducible-', 100),
        'sources' => [],
    ], 64);
} catch (AddressDiagnosticsTooLargeException $exception) {
    $thrown = $exception;
}
address_test_assert($thrown instanceof AddressDiagnosticsTooLargeException, 'irreducible payload must be rejected');
address_test_assert($thrown->getCode() === 422, 'irreducible payload must map to HTTP 422');

$response = null;
try {
    encode_address_diagnostics_or_fail([
        'preferred_address' => str_repeat('irreducible-', 100),
        'sources' => [],
    ], 64);
} catch (AddressTestHttpResponse $exception) {
    $response = $exception;
}
address_test_assert($response instanceof AddressTestHttpResponse, 'endpoint helper did not emit an HTTP response');
address_test_assert($response->status === 422, 'endpoint helper did not emit HTTP 422');
address_test_assert(($response->payload['ok'] ?? null) === false, 'endpoint helper returned a successful payload');

echo "address_diagnostics_test OK\n";
