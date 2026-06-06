<?php

declare(strict_types=1);

function ensure_app_username_available(PDO $pdo, string $username, int $ignoreUserId = 0): void
{
    if (hash_equals(ADMIN_USERNAME, $username)) {
        throw new RuntimeException('不能使用后台账号作为 App 账号。');
    }

    $stmt = $pdo->prepare('SELECT id FROM users WHERE username = ? AND id <> ? LIMIT 1');
    $stmt->execute([$username, $ignoreUserId]);

    if ($stmt->fetch()) {
        throw new RuntimeException('账号名称已存在，请换一个。');
    }
}

function ensure_family_group_available(PDO $pdo, string $groupName, int $ignoreGroupId = 0): void
{
    $stmt = $pdo->prepare('SELECT id FROM family_groups WHERE group_name = ? AND id <> ? LIMIT 1');
    $stmt->execute([$groupName, $ignoreGroupId]);

    if ($stmt->fetch()) {
        throw new RuntimeException('家庭组名称已存在，请换一个。');
    }
}

function ensure_family_group_exists(PDO $pdo, string $groupName): void
{
    ensure_family_group_record($pdo, $groupName);
}

function validate_role(string $role): string
{
    $normalized = normalize_role($role);
    if (!is_valid_role($normalized)) {
        throw new RuntimeException('账号类型不正确。');
    }

    return $normalized;
}

function membership_minutes(array $user): int
{
    return (int) ceil(user_report_interval_seconds($user) / 60);
}

function membership_seconds(array $user): int
{
    return user_report_interval_seconds($user);
}

function admin_family_group_label(array $group): string
{
    $name = trim((string) ($group['display_name'] ?? ''));
    if ($name === '') {
        $name = trim((string) ($group['group_name'] ?? ''));
    }
    if ($name === '') {
        $name = '未命名家庭组';
    }

    $code = trim((string) ($group['group_code'] ?? ''));
    return $name . ' #' . ($code === '' ? '未生成' : $code);
}

function admin_query(array $overrides = []): string
{
    $params = array_merge($_GET, $overrides);
    foreach ($params as $key => $value) {
        if ($value === '' || $value === null) {
            unset($params[$key]);
        }
    }

    return '?' . http_build_query($params);
}

function location_address_summary(?string $json): string
{
    if (!$json) {
        return '';
    }

    $diagnostics = json_decode($json, true);
    if (!is_array($diagnostics)) {
        return '';
    }

    if (!empty($diagnostics['preferred_address'])) {
        return (string) $diagnostics['preferred_address'];
    }

    $sources = $diagnostics['sources'] ?? [];
    if (!is_array($sources)) {
        return '';
    }

    foreach ($sources as $source) {
        if (is_array($source) && ($source['type'] ?? '') === 'gps' && !empty($source['address'])) {
            return (string) $source['address'];
        }
    }

    foreach ($sources as $source) {
        if (is_array($source) && !empty($source['address'])) {
            return (string) $source['address'];
        }
    }

    return '';
}

function location_diagnostics_sources(?string $json): array
{
    if (!$json) {
        return [];
    }

    $diagnostics = json_decode($json, true);
    if (!is_array($diagnostics) || !is_array($diagnostics['sources'] ?? null)) {
        return [];
    }

    $labels = [
        'gps' => '定位记录',
        'ip' => 'IP 检测',
        'webrtc' => 'WebRTC 检测',
    ];
    $items = [];

    foreach ($diagnostics['sources'] as $source) {
        if (!is_array($source)) {
            continue;
        }

        $type = (string) ($source['type'] ?? '');
        $label = $labels[$type] ?? ((string) ($source['name'] ?? '地址'));
        $address = trim((string) ($source['address'] ?? ($source['ip'] ?? '')));
        $city = trim((string) ($source['city'] ?? ''));

        $items[] = [
            'label' => $label,
            'address' => $address === '' ? '未知' : $address,
            'city' => $city === '' ? '未知' : $city,
        ];
    }

    return $items;
}

function installed_apps_from_environment_report(?string $json): array
{
    if (!$json) {
        return [];
    }

    $report = json_decode($json, true);
    if (!is_array($report)) {
        return [];
    }

    $apps = $report['installed_apps'] ?? [];
    if (!is_array($apps)) {
        return [];
    }

    $items = [];
    foreach ($apps as $app) {
        if (!is_array($app)) {
            continue;
        }

        $packageName = trim((string) ($app['package_name'] ?? ''));
        if ($packageName === '') {
            continue;
        }

        $items[] = [
            'package_name' => $packageName,
            'label' => trim((string) ($app['label'] ?? '')),
            'version_name' => trim((string) ($app['version_name'] ?? '')),
            'system' => !empty($app['system']),
        ];
    }

    usort($items, static function (array $left, array $right): int {
        return strcmp($left['package_name'], $right['package_name']);
    });

    return $items;
}

function format_bytes(?int $bytes): string
{
    if ($bytes === null || $bytes <= 0) {
        return '';
    }

    $gb = $bytes / 1024 / 1024 / 1024;
    if ($gb >= 1) {
        return number_format($gb, 1) . ' GB';
    }

    return number_format($bytes / 1024 / 1024, 0) . ' MB';
}

function device_info_from_environment_report(?string $json): array
{
    if (!$json) {
        return [];
    }

    $report = json_decode($json, true);
    if (!is_array($report)) {
        return [];
    }

    $items = [];
    $model = trim(implode(' ', array_filter([
        (string) ($report['manufacturer'] ?? ''),
        (string) ($report['model'] ?? ''),
    ])));
    if ($model !== '') {
        $items[] = ['设备', $model];
    }
    if (!empty($report['android_release']) || !empty($report['android_sdk'])) {
        $items[] = ['系统', 'Android ' . (string) ($report['android_release'] ?? '') . ' / SDK ' . (string) ($report['android_sdk'] ?? '')];
    }
    if (!empty($report['app_version_name']) || !empty($report['app_version_code'])) {
        $items[] = ['App', (string) ($report['app_version_name'] ?? '') . ' / ' . (string) ($report['app_version_code'] ?? '')];
    }

    $memoryTotal = format_bytes(isset($report['memory_total_bytes']) ? (int) $report['memory_total_bytes'] : null);
    $memoryAvailable = format_bytes(isset($report['memory_available_bytes']) ? (int) $report['memory_available_bytes'] : null);
    if ($memoryTotal !== '' || $memoryAvailable !== '') {
        $items[] = ['内存', trim($memoryAvailable . ' 可用 / ' . $memoryTotal . ' 总量')];
    }

    $storageTotal = format_bytes(isset($report['storage_total_bytes']) ? (int) $report['storage_total_bytes'] : null);
    $storageAvailable = format_bytes(isset($report['storage_available_bytes']) ? (int) $report['storage_available_bytes'] : null);
    if ($storageTotal !== '' || $storageAvailable !== '') {
        $items[] = ['存储', trim($storageAvailable . ' 可用 / ' . $storageTotal . ' 总量')];
    }

    foreach ([
        'adb_enabled' => 'ADB',
        'root_detected' => 'Root',
        'mock_location_risk' => '模拟定位风险',
        'fake_location_detected' => '定位伪装应用',
        'reqable_detected' => 'Reqable',
    ] as $key => $label) {
        if (array_key_exists($key, $report)) {
            $items[] = [$label, !empty($report[$key]) ? '是' : '否'];
        }
    }

    if (!empty($report['suspicious_packages']) && is_array($report['suspicious_packages'])) {
        $items[] = ['风险包名', implode(', ', array_map('strval', $report['suspicious_packages']))];
    }

    return $items;
}

function refresh_latest_location(PDO $pdo, int $userId, string $groupName): void
{
    $stmt = $pdo->prepare('
        SELECT *
        FROM locations
        WHERE user_id = ? AND group_name = ?
        ORDER BY created_at DESC, id DESC
        LIMIT 1
    ');
    $stmt->execute([$userId, $groupName]);
    $row = $stmt->fetch();

    if (!$row) {
        $stmt = $pdo->prepare('DELETE FROM latest_group_locations WHERE user_id = ? AND group_name = ?');
        $stmt->execute([$userId, $groupName]);
        return;
    }

    $stmt = $pdo->prepare('
        INSERT INTO latest_group_locations
            (user_id, group_name, role, latitude, longitude, altitude, accuracy, heading, speed, location_meta, latest_location_id, address_diagnostics, address_mismatch, updated_at)
        VALUES
            (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
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
            updated_at = VALUES(updated_at)
    ');
    $stmt->execute([
        (int) $row['user_id'],
        (string) $row['group_name'],
        (string) $row['role'],
        $row['latitude'],
        $row['longitude'],
        $row['altitude'],
        $row['accuracy'],
        $row['heading'],
        $row['speed'],
        $row['location_meta'],
        (int) $row['id'],
        $row['address_diagnostics'],
        (int) $row['address_mismatch'],
        (string) $row['created_at'],
    ]);
}
