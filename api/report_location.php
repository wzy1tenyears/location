<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    json_response(['ok' => false, 'message' => 'Method not allowed.'], 405);
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

function sanitize_address_diagnostics(?array $diagnostics): ?array
{
    if (!$diagnostics) {
        return null;
    }

    $sources = [];
    foreach (($diagnostics['sources'] ?? []) as $source) {
        if (!is_array($source)) {
            continue;
        }

        $type = report_string($source['type'] ?? '', 24);
        if (!in_array($type, ['gps', 'ip', 'webrtc'], true)) {
            continue;
        }

        $sources[] = [
            'type' => $type,
            'name' => report_string($source['name'] ?? '', 40),
            'address' => report_string($source['address'] ?? '', 600),
            'city' => report_string($source['city'] ?? '', 80),
            'region' => report_string($source['region'] ?? '', 80),
            'country' => report_string($source['country'] ?? '', 80),
            'ip' => report_string($source['ip'] ?? '', 80),
            'stun_server' => report_string($source['stun_server'] ?? '', 80),
            'stun_scope' => report_string($source['stun_scope'] ?? '', 20),
            'latitude' => report_float($source['latitude'] ?? null, -90, 90),
            'longitude' => report_float($source['longitude'] ?? null, -180, 180),
        ];

        if (count($sources) >= 3) {
            break;
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
        'preferred_city' => report_string($diagnostics['preferred_city'] ?? '', 80),
        'preferred_latitude' => report_float($diagnostics['preferred_latitude'] ?? null, -90, 90),
        'preferred_longitude' => report_float($diagnostics['preferred_longitude'] ?? null, -180, 180),
        'sources' => $sources,
    ];
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

try {
    $user = require_user();
    $membership = require_user_membership($user, selected_group_name_from_request());
    require_report_device_cookie();

    $data = request_data();
    $locationId = (int) ($data['location_id'] ?? 0);
    $addressDiagnostics = sanitize_address_diagnostics(
        is_array($data['address_diagnostics'] ?? null) ? $data['address_diagnostics'] : null
    );
    $addressDiagnosticsJson = $addressDiagnostics === null
        ? null
        : json_encode($addressDiagnostics, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    $addressMismatch = $addressDiagnostics && !empty($addressDiagnostics['mismatch']) ? 1 : 0;

    if (is_string($addressDiagnosticsJson) && strlen($addressDiagnosticsJson) > MAX_ADDRESS_DIAGNOSTICS_BYTES) {
        $addressDiagnosticsJson = substr($addressDiagnosticsJson, 0, MAX_ADDRESS_DIAGNOSTICS_BYTES);
    }

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
