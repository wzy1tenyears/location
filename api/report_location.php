<?php

declare(strict_types=1);

$reportLocationFunctionsOnly = PHP_SAPI === 'cli'
    && defined('REPORT_LOCATION_FUNCTIONS_ONLY')
    && REPORT_LOCATION_FUNCTIONS_ONLY === true;
if (!$reportLocationFunctionsOnly) {
    require_once __DIR__ . '/../private/lib/bootstrap.php';
}

function report_string(mixed $value, int $maxLength = 255): string
{
    $text = trim((string) $value);
    if (function_exists('mb_strlen') && mb_strlen($text, 'UTF-8') > $maxLength) {
        return mb_substr($text, 0, $maxLength, 'UTF-8');
    }

    if (!function_exists('mb_strlen') && strlen($text) > $maxLength * 4) {
        return substr($text, 0, $maxLength * 4);
    }

    return $text;
}

function report_float(mixed $value, float $min, float $max): ?float
{
    if ($value === null || $value === '') {
        return null;
    }

    if (!is_numeric($value)) {
        return null;
    }

    $number = (float) $value;
    if (!is_finite($number) || $number < $min || $number > $max) {
        return null;
    }

    return $number;
}

final class AddressDiagnosticsTooLargeException extends LengthException
{
}

function sanitized_report_strings(array $item, array $limits): array
{
    $clean = [];
    foreach ($limits as $field => $limit) {
        $clean[$field] = report_string($item[$field] ?? '', $limit);
    }

    return $clean;
}

function sanitize_probe_items(mixed $items, string $kind): array
{
    if (!is_array($items)) {
        return [];
    }

    $ranked = [];
    foreach ($items as $inputIndex => $item) {
        if (!is_array($item)) {
            continue;
        }

        $entry = null;
        if ($kind === 'ip_variant') {
            $entry = sanitized_report_strings($item, [
                'label' => 24,
                'ip' => 80,
                'address' => 600,
                'detail' => 240,
                'poi' => 120,
                'district' => 120,
                'street' => 160,
                'postal_code' => 32,
                'city' => 80,
                'region' => 80,
                'country' => 80,
                'provider' => 80,
                'source' => 80,
                'source_region' => 40,
                'coordinate_system' => 16,
                'asn' => 80,
                'isp' => 120,
                'org' => 120,
                'carrier' => 120,
            ]);
            $entry['domestic_source'] = !empty($item['domestic_source']);
            $entry['mobile_network'] = !empty($item['mobile_network']);
            $entry['latitude'] = report_float($item['latitude'] ?? null, -90, 90);
            $entry['longitude'] = report_float($item['longitude'] ?? null, -180, 180);
        } elseif ($kind === 'webrtc_candidate') {
            $entry = sanitized_report_strings($item, [
                'ip' => 80,
                'candidate_type' => 24,
                'stun_server' => 120,
                'stun_label' => 80,
                'stun_scope' => 20,
                'address' => 600,
                'detail' => 240,
                'poi' => 120,
                'district' => 120,
                'street' => 160,
                'postal_code' => 32,
                'city' => 80,
                'region' => 80,
                'country' => 80,
                'provider' => 80,
                'source' => 80,
                'coordinate_system' => 16,
            ]);
            $entry['latitude'] = report_float($item['latitude'] ?? null, -90, 90);
            $entry['longitude'] = report_float($item['longitude'] ?? null, -180, 180);
        }

        if (!is_array($entry)) {
            continue;
        }
        $candidate = [
            'entry' => $entry,
            'score' => diagnostics_address_precision_score($entry),
            'index' => (int) $inputIndex,
        ];
        $inserted = false;
        foreach ($ranked as $index => $existing) {
            if (diagnostics_score_compare($candidate['score'], $existing['score']) > 0) {
                array_splice($ranked, $index, 0, [$candidate]);
                $inserted = true;
                break;
            }
        }
        if (!$inserted) {
            $ranked[] = $candidate;
        }
        if (count($ranked) > 12) {
            array_pop($ranked);
        }
    }

    return array_map(static fn (array $item): array => $item['entry'], $ranked);
}

function diagnostics_address_precision_score(array $item): array
{
    $specificity = 0;
    $structuredFields = 0;
    foreach (['country', 'region', 'city', 'postal_code', 'district', 'street', 'detail', 'poi'] as $field) {
        if (trim((string) ($item[$field] ?? '')) !== '') {
            $structuredFields += 1;
        }
    }
    if (trim((string) ($item['country'] ?? '')) !== '') {
        $specificity = max($specificity, 1);
    }
    if (trim((string) ($item['region'] ?? '')) !== '') {
        $specificity = max($specificity, 2);
    }
    if (trim((string) ($item['city'] ?? '')) !== '') {
        $specificity = max($specificity, 3);
    }
    if (trim((string) ($item['postal_code'] ?? '')) !== '') {
        $specificity = max($specificity, 4);
    }
    if (trim((string) ($item['district'] ?? '')) !== '') {
        $specificity = max($specificity, 5);
    }
    if (trim((string) ($item['street'] ?? '')) !== '') {
        $specificity = max($specificity, 6);
    }
    if (
        trim((string) ($item['detail'] ?? '')) !== ''
        || trim((string) ($item['poi'] ?? '')) !== ''
    ) {
        $specificity = max($specificity, 7);
    }
    $address = trim((string) ($item['address'] ?? ''));
    if ($address !== '') {
        $specificity = max($specificity, strlen($address) >= 24 ? 5 : 4);
    }
    $hasDisplayAddress = $address !== '' ? 1 : 0;
    $hasCoordinates = ($item['latitude'] ?? null) !== null && ($item['longitude'] ?? null) !== null ? 1 : 0;
    $hasCoordinateSystem = trim((string) ($item['coordinate_system'] ?? '')) !== '' ? 1 : 0;

    return [
        $specificity,
        $hasDisplayAddress,
        $structuredFields,
        min(600, strlen($address)),
        $hasCoordinates,
        $hasCoordinateSystem,
    ];
}

function diagnostics_score_compare(array $left, array $right): int
{
    $length = max(count($left), count($right));
    for ($index = 0; $index < $length; $index += 1) {
        $leftValue = (int) ($left[$index] ?? 0);
        $rightValue = (int) ($right[$index] ?? 0);
        if ($leftValue !== $rightValue) {
            return $leftValue <=> $rightValue;
        }
    }

    return 0;
}

function diagnostics_source_precision_score(array $source): array
{
    $best = diagnostics_address_precision_score($source);
    foreach (['variants', 'candidates'] as $field) {
        foreach (($source[$field] ?? []) as $item) {
            if (is_array($item)) {
                $score = diagnostics_address_precision_score($item);
                if (diagnostics_score_compare($score, $best) > 0) {
                    $best = $score;
                }
            }
        }
    }

    return $best;
}

function sanitize_diagnostics_source(array $source, string $type): array
{
    $clean = sanitized_report_strings($source, [
        'name' => 40,
        'provider' => 80,
        'source' => 80,
        'source_region' => 40,
        'coordinate_system' => 16,
        'address' => 600,
        'detail' => 240,
        'poi' => 120,
        'district' => 120,
        'street' => 160,
        'postal_code' => 32,
        'city' => 80,
        'region' => 80,
        'country' => 80,
        'ip' => 80,
        'ipv4' => 80,
        'ipv6' => 80,
        'server_ip' => 80,
        'stun_server' => 120,
        'stun_label' => 80,
        'stun_scope' => 20,
        'candidate_type' => 24,
        'asn' => 80,
        'isp' => 120,
        'org' => 120,
        'carrier' => 120,
    ]);
    $clean = ['type' => $type] + $clean;
    $clean['domestic_source'] = !empty($source['domestic_source']);
    $clean['mobile_network'] = !empty($source['mobile_network']);
    $clean['variants'] = sanitize_probe_items($source['variants'] ?? [], 'ip_variant');
    $clean['candidates'] = sanitize_probe_items($source['candidates'] ?? [], 'webrtc_candidate');
    $clean['latitude'] = report_float($source['latitude'] ?? null, -90, 90);
    $clean['longitude'] = report_float($source['longitude'] ?? null, -180, 180);

    return $clean;
}

function sanitize_address_diagnostics(?array $diagnostics): ?array
{
    if (!$diagnostics) {
        return null;
    }

    $bestSources = [];
    $bestScores = [];
    $rawSources = is_array($diagnostics['sources'] ?? null) ? $diagnostics['sources'] : [];
    foreach ($rawSources as $source) {
        if (!is_array($source)) {
            continue;
        }

        $type = report_string($source['type'] ?? '', 24);
        if (!in_array($type, ['gps', 'ip', 'webrtc'], true)) {
            continue;
        }

        $clean = sanitize_diagnostics_source($source, $type);
        $score = diagnostics_source_precision_score($clean);
        $currentScore = $bestScores[$type] ?? null;
        if (
            !is_array($currentScore)
            || diagnostics_score_compare($score, $currentScore) > 0
        ) {
            $bestSources[$type] = $clean;
            $bestScores[$type] = $score;
        }
    }

    $sources = [];
    foreach (['gps', 'ip', 'webrtc'] as $type) {
        if (isset($bestSources[$type])) {
            $sources[] = $bestSources[$type];
        }
    }

    $mismatch = diagnostics_place_mismatch($sources);
    $mobileIpUncertain = diagnostics_mobile_ip_uncertain($sources);
    $sources = array_map(static function (array $source) use ($mobileIpUncertain): array {
        if (($source['type'] ?? '') === 'ip' && $mobileIpUncertain) {
            $source['mobile_network_uncertain'] = true;
        }

        return $source;
    }, $sources);

    return [
        'mismatch' => $mismatch,
        'mobile_ip_uncertain' => $mobileIpUncertain && !$mismatch,
        'checked_at' => report_string($diagnostics['checked_at'] ?? date('Y-m-d H:i:s'), 40),
        'complete' => !empty($diagnostics['complete']),
        'preferred_source' => report_string($diagnostics['preferred_source'] ?? '', 24),
        'preferred_address' => report_string($diagnostics['preferred_address'] ?? '', 600),
        'preferred_detail' => report_string($diagnostics['preferred_detail'] ?? '', 240),
        'preferred_poi' => report_string($diagnostics['preferred_poi'] ?? '', 120),
        'preferred_district' => report_string($diagnostics['preferred_district'] ?? '', 120),
        'preferred_street' => report_string($diagnostics['preferred_street'] ?? '', 160),
        'preferred_postal_code' => report_string($diagnostics['preferred_postal_code'] ?? '', 32),
        'preferred_city' => report_string($diagnostics['preferred_city'] ?? '', 80),
        'preferred_region' => report_string($diagnostics['preferred_region'] ?? '', 80),
        'preferred_country' => report_string($diagnostics['preferred_country'] ?? '', 80),
        'preferred_coordinate_system' => report_string($diagnostics['preferred_coordinate_system'] ?? '', 16),
        'preferred_latitude' => report_float($diagnostics['preferred_latitude'] ?? null, -90, 90),
        'preferred_longitude' => report_float($diagnostics['preferred_longitude'] ?? null, -180, 180),
        'sources' => $sources,
    ];
}

function encode_address_diagnostics(?array $diagnostics, int $maxBytes): ?string
{
    if ($diagnostics === null) {
        return null;
    }
    if ($maxBytes <= 0) {
        throw new AddressDiagnosticsTooLargeException('位置诊断数据大小限制无效。', 422);
    }

    $encode = static function (array $value): string {
        $json = json_encode(
            $value,
            JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE
        );
        if (!is_string($json)) {
            throw new RuntimeException('位置诊断数据无法编码。');
        }

        return $json;
    };

    $bounded = $diagnostics;
    $json = $encode($bounded);
    if (strlen($json) <= $maxBytes) {
        return $json;
    }

    if (is_array($bounded['sources'] ?? null)) {
        foreach ($bounded['sources'] as &$source) {
            if (!is_array($source)) {
                continue;
            }
            unset($source['variants'], $source['candidates']);
        }
        unset($source);
    }

    $json = $encode($bounded);
    if (strlen($json) <= $maxBytes) {
        return $json;
    }

    throw new AddressDiagnosticsTooLargeException('位置诊断数据过大，请缩减后重试。', 422);
}

function encode_address_diagnostics_or_fail(?array $diagnostics, int $maxBytes): ?string
{
    try {
        return encode_address_diagnostics($diagnostics, $maxBytes);
    } catch (AddressDiagnosticsTooLargeException $exception) {
        json_response(['ok' => false, 'message' => $exception->getMessage()], 422);
    }
}

function diagnostics_place_mismatch(array $sources): bool
{
    $trustedSources = array_values(array_filter(
        $sources,
        static fn (array $source): bool => in_array((string) ($source['type'] ?? ''), ['gps', 'webrtc'], true)
    ));

    if (count($trustedSources) < 2) {
        return false;
    }

    foreach (['country', 'region'] as $field) {
        $values = array_values(array_unique(array_filter(array_map(
            static fn (array $source): string => strtolower(preg_replace('/\s+/u', '', (string) ($source[$field] ?? ''))),
            $trustedSources
        ))));

        if (count($values) > 1) {
            return true;
        }
    }

    $cities = array_values(array_unique(array_filter(array_map(
        static fn (array $source): string => strtolower(preg_replace('/\s+/u', '', (string) ($source['city'] ?? ''))),
        $trustedSources
    ))));
    if (count($cities) > 1 && !diagnostics_ip_webrtc_same_city_same_region($sources)) {
        return true;
    }

    return false;
}

function diagnostics_ip_webrtc_same_city_same_region(array $sources): bool
{
    $gps = diagnostics_source_by_type($sources, 'gps');
    $ip = diagnostics_source_by_type($sources, 'ip');
    $webrtc = diagnostics_source_by_type($sources, 'webrtc');
    if (!$gps || !$ip || !$webrtc) {
        return false;
    }

    $ipCity = diagnostics_compare_value($ip['city'] ?? '');
    $webrtcCity = diagnostics_compare_value($webrtc['city'] ?? '');
    if ($ipCity === '' || $webrtcCity === '' || $ipCity !== $webrtcCity) {
        return false;
    }

    foreach (['country', 'region'] as $field) {
        $values = array_values(array_unique(array_filter(array_map(
            static fn (array $source): string => diagnostics_compare_value($source[$field] ?? ''),
            [$gps, $ip, $webrtc]
        ))));
        if (count($values) > 1) {
            return false;
        }
    }

    return true;
}

function diagnostics_source_by_type(array $sources, string $type): ?array
{
    foreach ($sources as $source) {
        if (($source['type'] ?? '') === $type) {
            return $source;
        }
    }

    return null;
}

function diagnostics_compare_value(mixed $value): string
{
    return strtolower(preg_replace('/\s+/u', '', (string) $value));
}

function diagnostics_mobile_ip_uncertain(array $sources): bool
{
    $ipSource = null;
    foreach ($sources as $source) {
        if (($source['type'] ?? '') === 'ip') {
            $ipSource = $source;
            break;
        }
    }

    if (!$ipSource) {
        return false;
    }

    foreach ($sources as $source) {
        if (!in_array((string) ($source['type'] ?? ''), ['gps', 'webrtc'], true)) {
            continue;
        }

        foreach (['country', 'region'] as $field) {
            $ipValue = strtolower(preg_replace('/\s+/u', '', (string) ($ipSource[$field] ?? '')));
            $sourceValue = strtolower(preg_replace('/\s+/u', '', (string) ($source[$field] ?? '')));
            if ($ipValue !== '' && $sourceValue !== '' && $ipValue !== $sourceValue) {
                return true;
            }
        }
    }

    return false;
}

function validate_location_measurements(?float $accuracy, ?float $heading, ?float $speed, ?float $altitude): void
{
    if ($altitude !== null && ($altitude < -500 || $altitude > 12000)) {
        json_response(['ok' => false, 'message' => '定位高度异常，已拒绝上报。'], 422);
    }

    if ($accuracy !== null && ($accuracy < 0 || $accuracy > MAX_LOCATION_ACCURACY_METERS)) {
        json_response(['ok' => false, 'message' => '定位精度异常，已拒绝上报。'], 422);
    }

    if ($heading !== null && ($heading < 0 || $heading > 360)) {
        json_response(['ok' => false, 'message' => '定位方向异常，已拒绝上报。'], 422);
    }

    if ($speed !== null && ($speed < 0 || $speed > MAX_LOCATION_SPEED_MPS)) {
        json_response(['ok' => false, 'message' => '定位速度异常，已拒绝上报。'], 422);
    }
}

function sanitized_location_meta(array $data): ?string
{
    $meta = [
        'provider' => report_string($data['location_provider'] ?? '', 40),
        'location_time' => report_string($data['location_time'] ?? '', 40),
        'vertical_accuracy' => report_float($data['vertical_accuracy'] ?? null, 0, 10000),
        'bearing_accuracy' => report_float($data['bearing_accuracy'] ?? null, 0, 360),
        'speed_accuracy' => report_float($data['speed_accuracy'] ?? null, 0, 1000),
    ];

    $meta = array_filter($meta, static fn (mixed $value): bool => $value !== null && $value !== '');
    if (!$meta) {
        return null;
    }

    $json = json_encode($meta, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    return is_string($json) ? $json : null;
}

function p2p_encrypted_payload_from_request(mixed $value): ?string
{
    if ($value === null || $value === '') {
        return null;
    }

    if (!is_array($value)) {
        json_response(['ok' => false, 'message' => '加密定位数据格式不正确。'], 422);
    }

    foreach (['iv', 'ciphertext'] as $field) {
        if (empty($value[$field]) || !is_string($value[$field])) {
            json_response(['ok' => false, 'message' => '加密定位数据不完整。'], 422);
        }
    }

    $json = json_encode($value, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    if (!is_string($json) || strlen($json) > MAX_P2P_ENCRYPTED_PAYLOAD_BYTES) {
        json_response(['ok' => false, 'message' => '加密定位数据过大。'], 422);
    }

    return $json;
}

function assert_location_report_plausible(PDO $pdo, int $userId, string $groupName, float $latitude, float $longitude, ?float $accuracy): void
{
    if (abs($latitude) < 0.000001 && abs($longitude) < 0.000001) {
        json_response(['ok' => false, 'message' => '定位坐标异常，已拒绝上报。'], 422);
    }

    $stmt = $pdo->prepare('
        SELECT latitude, longitude, accuracy, created_at
        FROM locations
        WHERE user_id = ? AND group_name = ?
        ORDER BY created_at DESC, id DESC
        LIMIT 1
    ');
    $stmt->execute([$userId, $groupName]);
    $previous = $stmt->fetch();
    if (!$previous) {
        return;
    }

    $elapsed = time() - strtotime((string) $previous['created_at']);
    if ($elapsed >= 0 && $elapsed < MIN_LOCATION_REPORT_SECONDS) {
        json_response(['ok' => false, 'message' => '上报过于频繁，请稍后再试。'], 429);
    }

    if ($elapsed <= 0) {
        return;
    }

    $distance = haversine_distance_meters(
        (float) $previous['latitude'],
        (float) $previous['longitude'],
        $latitude,
        $longitude
    );
    $previousAccuracy = $previous['accuracy'] === null ? 0.0 : max(0.0, (float) $previous['accuracy']);
    $currentAccuracy = $accuracy === null ? 0.0 : max(0.0, $accuracy);
    $effectiveDistance = max(0.0, $distance - $previousAccuracy - $currentAccuracy - 1000.0);
    $travelSpeed = $effectiveDistance / $elapsed;

    if ($travelSpeed > MAX_REASONABLE_TRAVEL_MPS) {
        record_user_log($userId, $groupName, 'location_jump_anomaly', '位置变化异常', [
            'previous_latitude' => (float) $previous['latitude'],
            'previous_longitude' => (float) $previous['longitude'],
            'latitude' => $latitude,
            'longitude' => $longitude,
            'distance_meters' => round($distance, 2),
            'elapsed_seconds' => $elapsed,
            'effective_speed_mps' => round($travelSpeed, 2),
            'previous_accuracy' => $previousAccuracy,
            'current_accuracy' => $currentAccuracy,
        ]);
        error_log(sprintf(
            '[family-location] unusual location jump accepted: user=%d group=%s distance=%.2f elapsed=%d speed=%.2f',
            $userId,
            $groupName,
            $distance,
            $elapsed,
            $travelSpeed
        ));
    }
}

function haversine_distance_meters(float $lat1, float $lon1, float $lat2, float $lon2): float
{
    $earthRadius = 6371000.0;
    $deltaLat = deg2rad($lat2 - $lat1);
    $deltaLon = deg2rad($lon2 - $lon1);
    $a = sin($deltaLat / 2) ** 2
        + cos(deg2rad($lat1)) * cos(deg2rad($lat2)) * sin($deltaLon / 2) ** 2;

    return $earthRadius * 2 * atan2(sqrt($a), sqrt(max(0.0, 1 - $a)));
}

if ($reportLocationFunctionsOnly) {
    return;
}

require_app_user_agent();

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    json_response(['ok' => false, 'message' => 'Method not allowed.'], 405);
}

try {
    $user = require_user();
    rate_limit_or_fail('report_location', 900, 3600, 'user:' . (int) $user['id']);
    $membership = require_user_membership($user, selected_group_name_from_request());
    require_report_device_cookie();

    $data = request_data();
    $p2pEnabled = !empty($membership['p2p_enabled_at']);
    $encryptedPayloadJson = p2p_encrypted_payload_from_request($data['encrypted_payload'] ?? null);
    $p2pKeyVersion = max(0, (int) ($data['p2p_key_version'] ?? ($membership['p2p_key_version'] ?? 0)));
    if ($p2pEnabled && $encryptedPayloadJson === null) {
        json_response(['ok' => false, 'message' => '当前家庭组已开启端到端加密，请使用新版 App 上报。'], 422);
    }
    if (!$p2pEnabled && $encryptedPayloadJson !== null) {
        json_response(['ok' => false, 'message' => '当前家庭组未开启端到端加密。'], 422);
    }

    $locationId = (int) ($data['location_id'] ?? 0);
    $addressDiagnostics = sanitize_address_diagnostics(
        is_array($data['address_diagnostics'] ?? null) ? $data['address_diagnostics'] : null
    );
    $addressDiagnosticsJson = encode_address_diagnostics_or_fail(
        $addressDiagnostics,
        MAX_ADDRESS_DIAGNOSTICS_BYTES
    );
    $addressMismatch = $addressDiagnostics && !empty($addressDiagnostics['mismatch']) ? 1 : 0;

    if ($locationId > 0) {
        $pdo = db();
        $pdo->beginTransaction();

        $checkStmt = $pdo->prepare('
            SELECT id, created_at
            FROM locations
            WHERE id = ?
                AND user_id = ?
                AND group_name = ?
            LIMIT 1
        ');
        $checkStmt->execute([
            $locationId,
            (int) $user['id'],
            (string) $membership['group_name'],
        ]);
        $existingLocation = $checkStmt->fetch();
        if (!$existingLocation) {
            $pdo->rollBack();
            json_response(['ok' => false, 'message' => '位置记录不存在或无权更新。'], 404);
        }

        if (strtotime((string) $existingLocation['created_at']) < time() - LOCATION_DIAGNOSTICS_UPDATE_SECONDS) {
            $pdo->rollBack();
            json_response(['ok' => false, 'message' => '位置诊断更新已过期。'], 422);
        }

        if ($p2pEnabled) {
            $stmt = $pdo->prepare("
                UPDATE locations
                SET encryption_mode = 'p2p-v1',
                    encrypted_payload = ?,
                    p2p_key_version = ?,
                    address_diagnostics = NULL,
                    address_mismatch = 0
                WHERE id = ?
                    AND user_id = ?
                    AND group_name = ?
            ");
            $stmt->execute([
                $encryptedPayloadJson,
                $p2pKeyVersion,
                $locationId,
                (int) $user['id'],
                (string) $membership['group_name'],
            ]);

            $stmt = $pdo->prepare("
                UPDATE latest_group_locations
                SET encryption_mode = 'p2p-v1',
                    encrypted_payload = ?,
                    p2p_key_version = ?,
                    address_diagnostics = NULL,
                    address_mismatch = 0
                WHERE user_id = ?
                    AND group_name = ?
                    AND latest_location_id = ?
            ");
            $stmt->execute([
                $encryptedPayloadJson,
                $p2pKeyVersion,
                (int) $user['id'],
                (string) $membership['group_name'],
                $locationId,
            ]);
        } else {
            $stmt = $pdo->prepare('
                UPDATE locations
                SET address_diagnostics = ?,
                    address_mismatch = ?
                WHERE id = ?
                    AND user_id = ?
                    AND group_name = ?
            ');
            $stmt->execute([
                $addressDiagnosticsJson,
                $addressMismatch,
                $locationId,
                (int) $user['id'],
                (string) $membership['group_name'],
            ]);

            $stmt = $pdo->prepare('
                UPDATE latest_group_locations
                SET address_diagnostics = ?,
                    address_mismatch = ?
                WHERE user_id = ?
                    AND group_name = ?
                    AND latest_location_id = ?
            ');
            $stmt->execute([
                $addressDiagnosticsJson,
                $addressMismatch,
                (int) $user['id'],
                (string) $membership['group_name'],
                $locationId,
            ]);
        }

        $pdo->commit();
        latest_locations_cache_forget_all();
        latest_locations_for_group((string) $membership['group_name']);

        json_response([
            'ok' => true,
            'message' => '位置诊断已更新。',
            'location_id' => $locationId,
            'reported_at' => date('Y-m-d H:i:s'),
        ]);
    }

    if ($p2pEnabled) {
        $pdo = db();
        $userAgent = substr((string) ($_SERVER['HTTP_USER_AGENT'] ?? ''), 0, 255);
        $pdo->beginTransaction();

        $stmt = $pdo->prepare("
            INSERT INTO locations
                (user_id, group_name, role, latitude, longitude, encryption_mode, encrypted_payload, p2p_key_version, user_agent)
            VALUES
                (?, ?, ?, 0, 0, 'p2p-v1', ?, ?, ?)
        ");
        $stmt->execute([
            (int) $user['id'],
            $membership['group_name'],
            normalize_role((string) $membership['role']),
            $encryptedPayloadJson,
            $p2pKeyVersion,
            $userAgent,
        ]);
        $locationId = (int) $pdo->lastInsertId();

        $stmt = $pdo->prepare("
            INSERT INTO latest_group_locations
                (user_id, group_name, role, latitude, longitude, latest_location_id, encryption_mode, encrypted_payload, p2p_key_version, updated_at)
            VALUES
                (?, ?, ?, 0, 0, ?, 'p2p-v1', ?, ?, NOW())
            ON DUPLICATE KEY UPDATE
                group_name = VALUES(group_name),
                role = VALUES(role),
                latitude = 0,
                longitude = 0,
                latest_location_id = VALUES(latest_location_id),
                encryption_mode = VALUES(encryption_mode),
                encrypted_payload = VALUES(encrypted_payload),
                p2p_key_version = VALUES(p2p_key_version),
                updated_at = NOW()
        ");
        $stmt->execute([
            (int) $user['id'],
            $membership['group_name'],
            normalize_role((string) $membership['role']),
            $locationId,
            $encryptedPayloadJson,
            $p2pKeyVersion,
        ]);

        touch_user_presence((int) $user['id'], (string) $membership['group_name']);
        record_user_log((int) $user['id'], (string) $membership['group_name'], 'location_report', '上报端到端加密位置', [
            'location_id' => $locationId,
            'p2p_key_version' => $p2pKeyVersion,
        ]);

        $pdo->commit();
        latest_locations_cache_forget_all();
        latest_locations_for_group((string) $membership['group_name']);

        json_response([
            'ok' => true,
            'message' => '加密位置已上报。',
            'location_id' => $locationId,
            'reported_at' => date('Y-m-d H:i:s'),
        ]);
    }

    $latitude = input_float('latitude');
    $longitude = input_float('longitude');
    $altitude = input_float('altitude');
    $accuracy = input_float('accuracy');
    $heading = input_float('heading');
    $speed = input_float('speed');
    $locationMetaJson = sanitized_location_meta($data);

    if ($latitude === null || $longitude === null) {
        json_response(['ok' => false, 'message' => '定位数据不完整。'], 422);
    }

    if ($latitude < -90 || $latitude > 90 || $longitude < -180 || $longitude > 180) {
        json_response(['ok' => false, 'message' => '定位经纬度不正确。'], 422);
    }
    validate_location_measurements($accuracy, $heading, $speed, $altitude);

    $pdo = db();
    $userAgent = substr((string) ($_SERVER['HTTP_USER_AGENT'] ?? ''), 0, 255);
    assert_location_report_plausible(
        $pdo,
        (int) $user['id'],
        (string) $membership['group_name'],
        $latitude,
        $longitude,
        $accuracy
    );

    $pdo->beginTransaction();

    $stmt = $pdo->prepare('
        INSERT INTO locations
            (user_id, group_name, role, latitude, longitude, altitude, accuracy, heading, speed, location_meta, address_diagnostics, address_mismatch, user_agent)
        VALUES
            (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ');
    $stmt->execute([
        (int) $user['id'],
        $membership['group_name'],
        normalize_role((string) $membership['role']),
        $latitude,
        $longitude,
        $altitude,
        $accuracy,
        $heading,
        $speed,
        $locationMetaJson,
        $addressDiagnosticsJson,
        $addressMismatch,
        $userAgent,
    ]);
    $locationId = (int) $pdo->lastInsertId();

    $stmt = $pdo->prepare('
        INSERT INTO latest_group_locations
            (user_id, group_name, role, latitude, longitude, altitude, accuracy, heading, speed, location_meta, latest_location_id, address_diagnostics, address_mismatch, updated_at)
        VALUES
            (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
        ON DUPLICATE KEY UPDATE
            group_name = VALUES(group_name),
            role = VALUES(role),
            latitude = VALUES(latitude),
            longitude = VALUES(longitude),
            altitude = VALUES(altitude),
            accuracy = VALUES(accuracy),
            heading = VALUES(heading),
            speed = VALUES(speed),
            location_meta = VALUES(location_meta),
            latest_location_id = VALUES(latest_location_id),
            address_diagnostics = VALUES(address_diagnostics),
            address_mismatch = VALUES(address_mismatch),
            updated_at = NOW()
    ');
    $stmt->execute([
        (int) $user['id'],
        $membership['group_name'],
        normalize_role((string) $membership['role']),
        $latitude,
        $longitude,
        $altitude,
        $accuracy,
        $heading,
        $speed,
        $locationMetaJson,
        $locationId,
        $addressDiagnosticsJson,
        $addressMismatch,
    ]);

    touch_user_presence((int) $user['id'], (string) $membership['group_name']);
    record_user_log((int) $user['id'], (string) $membership['group_name'], 'location_report', '上报位置', [
        'location_id' => $locationId,
        'accuracy' => $accuracy,
        'address_mismatch' => $addressMismatch === 1,
    ]);

    $pdo->exec('
        DELETE FROM locations
        WHERE id NOT IN (
            SELECT id FROM (
                SELECT id FROM locations ORDER BY id DESC LIMIT ' . (int) LOCATION_HISTORY_LIMIT . '
            ) keep_rows
        )
    ');

    $pdo->commit();
    latest_locations_cache_forget_all();
    latest_locations_for_group((string) $membership['group_name']);

    json_response([
        'ok' => true,
        'message' => '位置已上报。',
        'location_id' => $locationId,
        'reported_at' => date('Y-m-d H:i:s'),
    ]);
} catch (Throwable $th) {
    if (isset($pdo) && $pdo instanceof PDO && $pdo->inTransaction()) {
        $pdo->rollBack();
    }

    json_response(['ok' => false, 'message' => api_error_message($th)], 500);
}
