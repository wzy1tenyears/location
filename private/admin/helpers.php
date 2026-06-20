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
        $provider = trim((string) ($source['provider'] ?? ($source['source'] ?? '')));
        if ($provider === '' && $type === 'webrtc' && !empty($source['stun_server'])) {
            $provider = (string) $source['stun_server'];
        }
        if ($provider === '' && $type === 'gps') {
            $provider = '设备定位';
        }

        $items[] = [
            'label' => $label,
            'address' => $address === '' ? '未知' : $address,
            'city' => $city === '' ? '未知' : $city,
            'provider' => location_source_provider_label($provider),
            'response_rows' => location_source_response_rows($source),
        ];
    }

    return $items;
}

function location_source_response_rows(array $source): array
{
    $rows = [];
    $add = static function (string $label, mixed $value) use (&$rows): void {
        if (is_bool($value)) {
            $value = $value ? '是' : '否';
        }
        if ($value === null || $value === '') {
            return;
        }
        $text = is_scalar($value) ? trim((string) $value) : json_encode($value, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        if ($text === '') {
            return;
        }
        $rows[] = ['label' => $label, 'value' => $text];
    };

    $add('类型', $source['type'] ?? '');
    $add('地址解析源', location_source_provider_label((string) ($source['provider'] ?? '')));
    $add('探测来源', $source['source'] ?? '');
    $add('来源区域', $source['source_region'] ?? '');
    $add('国内来源', $source['domestic_source'] ?? null);
    $add('IP', $source['ip'] ?? '');
    $add('IPv4', $source['ipv4'] ?? '');
    $add('IPv6', $source['ipv6'] ?? '');
    $add('服务端 IP', $source['server_ip'] ?? '');
    $add('STUN 服务', $source['stun_server'] ?? '');
    $add('STUN 名称', $source['stun_label'] ?? '');
    $add('STUN 范围', $source['stun_scope'] ?? '');
    $add('候选类型', $source['candidate_type'] ?? '');
    $add('国家', $source['country'] ?? '');
    $add('省份/区域', $source['region'] ?? '');
    $add('城市', $source['city'] ?? '');
    $add('纬度', $source['latitude'] ?? '');
    $add('经度', $source['longitude'] ?? '');

    foreach (($source['variants'] ?? []) as $index => $variant) {
        if (!is_array($variant)) {
            continue;
        }
        $title = 'IP 响应 ' . ((int) $index + 1);
        $parts = array_filter([
            $variant['label'] ?? '',
            $variant['ip'] ?? '',
            $variant['provider'] ?? ($variant['source'] ?? ''),
            $variant['address'] ?? '',
        ], static fn ($value): bool => trim((string) $value) !== '');
        $add($title, implode(' / ', array_map('strval', $parts)));
    }

    foreach (($source['candidates'] ?? []) as $index => $candidate) {
        if (!is_array($candidate)) {
            continue;
        }
        $title = 'WebRTC 响应 ' . ((int) $index + 1);
        $parts = array_filter([
            $candidate['ip'] ?? '',
            $candidate['candidate_type'] ?? '',
            $candidate['stun_label'] ?? '',
            $candidate['stun_server'] ?? '',
            $candidate['stun_scope'] ?? '',
        ], static fn ($value): bool => trim((string) $value) !== '');
        $add($title, implode(' / ', array_map('strval', $parts)));
    }

    return $rows;
}
function location_source_provider_label(string $provider): string
{
    $provider = trim($provider);
    if ($provider === '') {
        return '未知';
    }

    $labels = [
        'meituan' => '美团',
        'amap' => '高德',
        'gaode' => '高德',
        'bigdatacloud' => 'BigDataCloud',
    ];
    $key = strtolower(preg_replace('/\s+/u', '', $provider));

    return $labels[$key] ?? $provider;
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
