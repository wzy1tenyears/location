<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_loc_app_page();
require_admin_path();

require_admin();

$message = '';
$error = '';

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

try {
    $pdo = db();
    $userPage = max(1, (int) ($_GET['user_page'] ?? 1));
    $userPerPage = (int) ($_GET['user_per_page'] ?? 20);
    $historyPage = max(1, (int) ($_GET['history_page'] ?? 1));
    $historyPerPage = (int) ($_GET['history_per_page'] ?? 20);
    $historyGroup = trim((string) ($_GET['history_group'] ?? ''));
    $historyUserId = (int) ($_GET['history_user_id'] ?? 0);

    if (!in_array($userPerPage, [10, 20, 50], true)) {
        $userPerPage = 20;
    }
    if (!in_array($historyPerPage, [20, 50, 100], true)) {
        $historyPerPage = 20;
    }

    if ($_SERVER['REQUEST_METHOD'] === 'POST') {
        require_csrf();

        $action = post_string('action', 32);

        if ($action === 'add_family_group') {
            $groupName = post_string('group_name', 100);

            if ($groupName === '') {
                throw new RuntimeException('家庭组名称不能为空。');
            }

            create_family_group_record($pdo, $groupName);
            $message = '家庭组已添加。';
        }

        if ($action === 'save_announcement') {
            $title = post_string('title', 120);
            $body = trim((string) ($_POST['body'] ?? ''));
            $isActive = isset($_POST['is_active']) ? 1 : 0;

            $stmt = $pdo->query('SELECT id FROM announcements ORDER BY id DESC LIMIT 1');
            $announcementId = (int) ($stmt->fetchColumn() ?: 0);
            if ($announcementId > 0) {
                $stmt = $pdo->prepare('
                    UPDATE announcements
                    SET title = ?,
                        body = ?,
                        is_active = ?,
                        version = version + 1
                    WHERE id = ?
                ');
                $stmt->execute([$title, $body, $isActive, $announcementId]);
            } else {
                $stmt = $pdo->prepare('INSERT INTO announcements (title, body, is_active) VALUES (?, ?, ?)');
                $stmt->execute([$title, $body, $isActive]);
            }
            $message = '公告已保存。';
        }

        if ($action === 'add_invite_code') {
            $code = post_string('code', 255);
            $note = post_string('note', 120);
            $inviteType = post_string('invite_type', 32);
            $maxUses = max(1, (int) ($_POST['max_uses'] ?? 1));
            if ($code === '') {
                $alphabet = '0123456789abcdefghijklmnopqrstuvwxyz';
                $code = '';
                for ($index = 0; $index < 6; $index += 1) {
                    $code .= $alphabet[random_int(0, strlen($alphabet) - 1)];
                }
            }
            if (!in_array($inviteType, ['invite', 'group_create'], true)) {
                throw new RuntimeException('邀请码类型不正确。');
            }
            $stmt = $pdo->prepare('INSERT INTO invite_codes (code, note, invite_type, max_uses) VALUES (?, ?, ?, ?)');
            $stmt->execute([$code, $note, $inviteType, $maxUses]);
            $message = '邀请码已添加。';
        }

        if ($action === 'update_invite_note') {
            $inviteId = (int) ($_POST['invite_id'] ?? 0);
            $note = post_string('note', 120);
            $stmt = $pdo->prepare('UPDATE invite_codes SET note = ? WHERE id = ?');
            $stmt->execute([$note, $inviteId]);
            $message = '邀请码备注已保存。';
        }

        if ($action === 'toggle_invite_code') {
            $inviteId = (int) ($_POST['invite_id'] ?? 0);
            $next = (int) ($_POST['next'] ?? 0);
            $stmt = $pdo->prepare('UPDATE invite_codes SET is_active = ? WHERE id = ?');
            $stmt->execute([$next === 1 ? 1 : 0, $inviteId]);
            $message = $next === 1 ? '邀请码已启用。' : '邀请码已停用。';
        }

        if ($action === 'delete_invite_code') {
            $inviteId = (int) ($_POST['invite_id'] ?? 0);
            $stmt = $pdo->prepare('DELETE FROM invite_codes WHERE id = ?');
            $stmt->execute([$inviteId]);
            $message = '邀请码已删除。';
        }

        if ($action === 'update_family_group') {
            $groupId = (int) ($_POST['group_id'] ?? 0);
            $groupName = post_string('group_name', 100);

            if ($groupId <= 0 || $groupName === '') {
                throw new RuntimeException('家庭组不存在或名称为空。');
            }

            $stmt = $pdo->prepare('SELECT * FROM family_groups WHERE id = ? LIMIT 1');
            $stmt->execute([$groupId]);
            $group = $stmt->fetch();

            if (!$group) {
                throw new RuntimeException('家庭组不存在。');
            }

            $stmt = $pdo->prepare('UPDATE family_groups SET display_name = ? WHERE id = ?');
            $stmt->execute([$groupName, $groupId]);
            $message = '家庭组已更新。';
        }

        if ($action === 'delete_family_group') {
            $groupId = (int) ($_POST['group_id'] ?? 0);
            $stmt = $pdo->prepare('SELECT * FROM family_groups WHERE id = ? LIMIT 1');
            $stmt->execute([$groupId]);
            $group = $stmt->fetch();

            if (!$group) {
                throw new RuntimeException('家庭组不存在。');
            }

            $groupName = (string) $group['group_name'];

            $stmt = $pdo->prepare('SELECT DISTINCT user_id FROM user_groups WHERE group_name = ?');
            $stmt->execute([$groupName]);
            $affectedUserIds = array_map('intval', $stmt->fetchAll(PDO::FETCH_COLUMN));

            $pdo->beginTransaction();
            try {
                $stmt = $pdo->prepare('DELETE FROM latest_group_locations WHERE group_name = ?');
                $stmt->execute([$groupName]);

                $stmt = $pdo->prepare('DELETE FROM locations WHERE group_name = ?');
                $stmt->execute([$groupName]);

                $stmt = $pdo->prepare('DELETE FROM user_groups WHERE group_name = ?');
                $stmt->execute([$groupName]);

                $stmt = $pdo->prepare('DELETE FROM family_groups WHERE id = ?');
                $stmt->execute([$groupId]);

                foreach ($affectedUserIds as $affectedUserId) {
                    $stmt = $pdo->prepare('SELECT group_name, role FROM user_groups WHERE user_id = ? ORDER BY group_name ASC, id ASC LIMIT 1');
                    $stmt->execute([$affectedUserId]);
                    $fallbackMembership = $stmt->fetch();

                    if ($fallbackMembership) {
                        $stmt = $pdo->prepare('UPDATE users SET group_name = ?, role = ? WHERE id = ? AND group_name = ?');
                        $stmt->execute([
                            (string) $fallbackMembership['group_name'],
                            (string) $fallbackMembership['role'],
                            $affectedUserId,
                            $groupName,
                        ]);
                        continue;
                    }

                    $stmt = $pdo->prepare("UPDATE users SET group_name = '', role = 'guardian' WHERE id = ? AND group_name = ?");
                    $stmt->execute([$affectedUserId, $groupName]);
                }

                $pdo->commit();
            } catch (Throwable $th) {
                $pdo->rollBack();
                throw $th;
            }

            $message = '家庭组已删除，组内定位记录已清除。';
        }

        if ($action === 'add_user') {
            $username = post_string('username', 64);
            $password = (string) ($_POST['password'] ?? '');
            $displayName = post_string('display_name', 100);
            $groupName = post_string('group_name', 100);
            $role = post_string('role', 16);
            $reportIntervalMinutes = (int) ($_POST['report_interval_minutes'] ?? 5);
            $reportIntervalSeconds = normalize_report_interval_seconds($reportIntervalMinutes * 60);

            if ($username === '' || $password === '' || $groupName === '') {
                throw new RuntimeException('账号、密码和初始家庭组不能为空。');
            }

            $role = validate_role($role);
            ensure_app_username_available($pdo, $username);

            $pdo->beginTransaction();
            ensure_family_group_exists($pdo, $groupName);

            $stmt = $pdo->prepare('
                INSERT INTO users (username, password_hash, display_name, group_name, role, report_interval_seconds)
                VALUES (?, ?, ?, ?, ?, ?)
            ');
            $stmt->execute([
                $username,
                password_hash($password, PASSWORD_DEFAULT),
                $displayName,
                $groupName,
                $role,
                $reportIntervalSeconds,
            ]);

            $userId = (int) $pdo->lastInsertId();
            ensure_family_group_record($pdo, $groupName, $userId);
            $stmt = $pdo->prepare('INSERT INTO user_groups (user_id, group_name, role) VALUES (?, ?, ?)');
            $stmt->execute([$userId, $groupName, $role]);

            $pdo->commit();
            $message = '账号已添加。';
        }

        if ($action === 'update_user') {
            $userId = (int) ($_POST['user_id'] ?? 0);
            $username = post_string('username', 64);
            $displayName = post_string('display_name', 100);
            $reportIntervalMinutes = (int) ($_POST['report_interval_minutes'] ?? 5);
            $reportIntervalSeconds = normalize_report_interval_seconds($reportIntervalMinutes * 60);

            if ($userId <= 0) {
                throw new RuntimeException('账号不存在。');
            }

            if ($username === '') {
                throw new RuntimeException('账号名称不能为空。');
            }

            ensure_app_username_available($pdo, $username, $userId);

            $stmt = $pdo->prepare('
                UPDATE users
                SET username = ?,
                    display_name = ?,
                    report_interval_seconds = ?
                WHERE id = ?
            ');
            $stmt->execute([$username, $displayName, $reportIntervalSeconds, $userId]);
            $message = '账号信息已更新。';
        }

        if ($action === 'add_membership') {
            $userId = (int) ($_POST['user_id'] ?? 0);
            $groupName = post_string('group_name', 100);
            $role = post_string('role', 16);

            if ($userId <= 0 || $groupName === '') {
                throw new RuntimeException('账号或家庭组不能为空。');
            }

            $role = validate_role($role);
            ensure_family_group_exists($pdo, $groupName);

            $stmt = $pdo->prepare('SELECT id FROM user_groups WHERE user_id = ? AND group_name = ? LIMIT 1');
            $stmt->execute([$userId, $groupName]);

            if ($stmt->fetch()) {
                throw new RuntimeException('这个账号已经在该家庭组内。');
            }

            $stmt = $pdo->prepare('INSERT INTO user_groups (user_id, group_name, role) VALUES (?, ?, ?)');
            $stmt->execute([$userId, $groupName, $role]);
            ensure_family_group_record($pdo, $groupName, $userId);
            $message = '家庭组身份已添加。';
        }

        if ($action === 'update_membership') {
            $membershipId = (int) ($_POST['membership_id'] ?? 0);
            $groupName = post_string('group_name', 100);
            $role = post_string('role', 16);

            if ($membershipId <= 0 || $groupName === '') {
                throw new RuntimeException('家庭组身份不存在或名称为空。');
            }

            $role = validate_role($role);

            $stmt = $pdo->prepare('SELECT * FROM user_groups WHERE id = ? LIMIT 1');
            $stmt->execute([$membershipId]);
            $membership = $stmt->fetch();

            if (!$membership) {
                throw new RuntimeException('家庭组身份不存在。');
            }

            $stmt = $pdo->prepare('SELECT id FROM user_groups WHERE user_id = ? AND group_name = ? AND id <> ? LIMIT 1');
            $stmt->execute([(int) $membership['user_id'], $groupName, $membershipId]);

            if ($stmt->fetch()) {
                throw new RuntimeException('这个账号已经在该家庭组内。');
            }

            $oldGroupName = (string) $membership['group_name'];
            $pdo->beginTransaction();
            ensure_family_group_exists($pdo, $groupName);

            if (!hash_equals($oldGroupName, $groupName)) {
                $stmt = $pdo->prepare('DELETE FROM latest_group_locations WHERE user_id = ? AND group_name = ?');
                $stmt->execute([(int) $membership['user_id'], $groupName]);
            }

            $stmt = $pdo->prepare('UPDATE user_groups SET group_name = ?, role = ? WHERE id = ?');
            $stmt->execute([$groupName, $role, $membershipId]);

            $stmt = $pdo->prepare('
                UPDATE latest_group_locations
                SET group_name = ?, role = ?
                WHERE user_id = ? AND group_name = ?
            ');
            $stmt->execute([$groupName, $role, (int) $membership['user_id'], $oldGroupName]);

            $stmt = $pdo->prepare('
                UPDATE locations
                SET group_name = ?, role = ?
                WHERE user_id = ? AND group_name = ?
            ');
            $stmt->execute([$groupName, $role, (int) $membership['user_id'], $oldGroupName]);

            $stmt = $pdo->prepare('
                UPDATE users
                SET group_name = ?, role = ?
                WHERE id = ? AND group_name = ?
            ');
            $stmt->execute([$groupName, $role, (int) $membership['user_id'], $oldGroupName]);

            $pdo->commit();
            $message = '家庭组身份已更新。';
        }

        if ($action === 'delete_membership') {
            $membershipId = (int) ($_POST['membership_id'] ?? 0);
            $stmt = $pdo->prepare('SELECT * FROM user_groups WHERE id = ? LIMIT 1');
            $stmt->execute([$membershipId]);
            $membership = $stmt->fetch();

            if (!$membership) {
                throw new RuntimeException('家庭组身份不存在。');
            }

            $stmt = $pdo->prepare('SELECT COUNT(*) FROM user_groups WHERE user_id = ?');
            $stmt->execute([(int) $membership['user_id']]);

            if ((int) $stmt->fetchColumn() <= 1) {
                throw new RuntimeException('每个账号至少保留一个家庭组。');
            }

            $pdo->beginTransaction();

            $stmt = $pdo->prepare('DELETE FROM user_groups WHERE id = ?');
            $stmt->execute([$membershipId]);

            $stmt = $pdo->prepare('DELETE FROM latest_group_locations WHERE user_id = ? AND group_name = ?');
            $stmt->execute([(int) $membership['user_id'], (string) $membership['group_name']]);

            $stmt = $pdo->prepare('SELECT * FROM user_groups WHERE user_id = ? ORDER BY group_name ASC, id ASC LIMIT 1');
            $stmt->execute([(int) $membership['user_id']]);
            $nextMembership = $stmt->fetch();

            if ($nextMembership) {
                $stmt = $pdo->prepare('
                    UPDATE users
                    SET group_name = ?, role = ?
                    WHERE id = ? AND group_name = ?
                ');
                $stmt->execute([
                    (string) $nextMembership['group_name'],
                    (string) $nextMembership['role'],
                    (int) $membership['user_id'],
                    (string) $membership['group_name'],
                ]);
            }

            $pdo->commit();
            $message = '家庭组身份已删除。';
        }

        if ($action === 'toggle_user') {
            $userId = (int) ($_POST['user_id'] ?? 0);
            $next = (int) ($_POST['next'] ?? 0);
            $stmt = $pdo->prepare('UPDATE users SET is_active = ? WHERE id = ?');
            $stmt->execute([$next === 1 ? 1 : 0, $userId]);
            $message = $next === 1 ? '账号已启用。' : '账号已停用。';
        }

        if ($action === 'delete_user') {
            $userId = (int) ($_POST['user_id'] ?? 0);
            $stmt = $pdo->prepare('DELETE FROM users WHERE id = ?');
            $stmt->execute([$userId]);
            $message = '账号已删除。';
        }

        if ($action === 'reset_password') {
            $userId = (int) ($_POST['user_id'] ?? 0);
            $password = (string) ($_POST['new_password'] ?? '');

            if ($password === '') {
                throw new RuntimeException('新密码不能为空。');
            }

            $stmt = $pdo->prepare('UPDATE users SET password_hash = ? WHERE id = ?');
            $stmt->execute([password_hash($password, PASSWORD_DEFAULT), $userId]);
            clear_failed_login($pdo, $userId);
            $message = '密码已重置。';
        }

        if ($action === 'delete_location') {
            $locationId = (int) ($_POST['location_id'] ?? 0);
            if ($locationId <= 0) {
                throw new RuntimeException('定位记录不存在。');
            }

            $stmt = $pdo->prepare('SELECT * FROM locations WHERE id = ? LIMIT 1');
            $stmt->execute([$locationId]);
            $location = $stmt->fetch();
            if (!$location) {
                throw new RuntimeException('定位记录不存在。');
            }

            $pdo->beginTransaction();

            $stmt = $pdo->prepare('DELETE FROM locations WHERE id = ?');
            $stmt->execute([$locationId]);
            refresh_latest_location($pdo, (int) $location['user_id'], (string) $location['group_name']);

            $pdo->commit();
            $message = '定位记录已删除。';
        }

        if ($message !== '') {
            latest_locations_cache_forget_all();
        }
    }

    $familyGroupsStmt = $pdo->query('
        SELECT
            fg.id,
            fg.group_name,
            fg.display_name,
            fg.group_code,
            fg.owner_user_id,
            fg.created_at,
            fg.updated_at,
            COUNT(ug.id) AS member_count
        FROM family_groups fg
        LEFT JOIN user_groups ug ON ug.group_name = fg.group_name
        GROUP BY fg.id, fg.group_name, fg.display_name, fg.group_code, fg.owner_user_id, fg.created_at, fg.updated_at
        ORDER BY fg.display_name ASC, fg.group_name ASC
    ');
    $familyGroups = $familyGroupsStmt->fetchAll();

    $announcementStmt = $pdo->query('SELECT * FROM announcements ORDER BY id DESC LIMIT 1');
    $announcement = $announcementStmt->fetch() ?: null;

    $inviteCodesStmt = $pdo->query('SELECT * FROM invite_codes ORDER BY created_at DESC, id DESC');
    $inviteCodes = $inviteCodesStmt->fetchAll();

    $userTotal = (int) $pdo->query('SELECT COUNT(*) FROM users')->fetchColumn();
    $userTotalPages = max(1, (int) ceil($userTotal / $userPerPage));

    if ($userPage > $userTotalPages) {
        $userPage = $userTotalPages;
    }

    $userOffset = ($userPage - 1) * $userPerPage;
    $stmt = $pdo->prepare('
        SELECT *
        FROM users
        ORDER BY username ASC
        LIMIT ? OFFSET ?
    ');
    $stmt->bindValue(1, $userPerPage, PDO::PARAM_INT);
    $stmt->bindValue(2, $userOffset, PDO::PARAM_INT);
    $stmt->execute();
    $users = $stmt->fetchAll();

    $membershipsStmt = $pdo->query('
        SELECT
            ug.*,
            ll.latitude,
            ll.longitude,
            ll.altitude,
            ll.accuracy,
            ll.updated_at AS location_updated_at
        FROM user_groups ug
        LEFT JOIN latest_group_locations ll
            ON ll.user_id = ug.user_id
           AND ll.group_name = ug.group_name
        ORDER BY ug.group_name ASC, ug.role ASC, ug.id ASC
    ');

    $membershipsByUser = [];
    foreach ($membershipsStmt->fetchAll() as $membership) {
        $membershipsByUser[(int) $membership['user_id']][] = $membership;
    }

    $allUsersStmt = $pdo->query('
        SELECT id, username, display_name
        FROM users
        ORDER BY username ASC
    ');
    $allUsersForFilter = $allUsersStmt->fetchAll();

    $historyWhere = [];
    $historyParams = [];
    if ($historyGroup !== '') {
        $historyWhere[] = 'l.group_name = ?';
        $historyParams[] = $historyGroup;
    }
    if ($historyUserId > 0) {
        $historyWhere[] = 'l.user_id = ?';
        $historyParams[] = $historyUserId;
    }
    $historyWhereSql = $historyWhere ? ('WHERE ' . implode(' AND ', $historyWhere)) : '';

    $stmt = $pdo->prepare("
        SELECT COUNT(*)
        FROM locations l
        INNER JOIN users u ON u.id = l.user_id
        {$historyWhereSql}
    ");
    $stmt->execute($historyParams);
    $historyTotal = (int) $stmt->fetchColumn();
    $historyTotalPages = max(1, (int) ceil($historyTotal / $historyPerPage));
    if ($historyPage > $historyTotalPages) {
        $historyPage = $historyTotalPages;
    }
    $historyOffset = ($historyPage - 1) * $historyPerPage;

    $stmt = $pdo->prepare("
        SELECT
            l.*,
            u.username,
            u.display_name
        FROM locations l
        INNER JOIN users u ON u.id = l.user_id
        {$historyWhereSql}
        ORDER BY l.created_at DESC, l.id DESC
        LIMIT ? OFFSET ?
    ");
    $bindIndex = 1;
    foreach ($historyParams as $value) {
        $stmt->bindValue($bindIndex, $value, is_int($value) ? PDO::PARAM_INT : PDO::PARAM_STR);
        $bindIndex += 1;
    }
    $stmt->bindValue($bindIndex, $historyPerPage, PDO::PARAM_INT);
    $stmt->bindValue($bindIndex + 1, $historyOffset, PDO::PARAM_INT);
    $stmt->execute();
    $historyLocations = $stmt->fetchAll();
} catch (Throwable $th) {
    if (isset($pdo) && $pdo instanceof PDO && $pdo->inTransaction()) {
        $pdo->rollBack();
    }

    $familyGroups = $familyGroups ?? [];
    $announcement = $announcement ?? null;
    $inviteCodes = $inviteCodes ?? [];
    $users = $users ?? [];
    $membershipsByUser = $membershipsByUser ?? [];
    $environmentReportsByUser = $environmentReportsByUser ?? [];
    $userPage = $userPage ?? 1;
    $userPerPage = $userPerPage ?? 20;
    $userTotal = $userTotal ?? 0;
    $userTotalPages = $userTotalPages ?? 1;
    $allUsersForFilter = $allUsersForFilter ?? [];
    $historyLocations = $historyLocations ?? [];
    $historyPage = $historyPage ?? 1;
    $historyPerPage = $historyPerPage ?? 20;
    $historyTotal = $historyTotal ?? 0;
    $historyTotalPages = $historyTotalPages ?? 1;
    $historyGroup = $historyGroup ?? '';
    $historyUserId = $historyUserId ?? 0;
    $error = $th->getMessage();
}
?>
<!doctype html>
<html lang="zh-CN">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>后台管理 - <?= e(APP_NAME) ?></title>
    <script>
        (function () {
            try {
                var mode = window.localStorage.getItem('theme_mode') || 'system';
                if (mode === 'light' || mode === 'dark') {
                    document.documentElement.dataset.theme = mode;
                }
            } catch (error) {
            }
        })();
    </script>
    <link rel="stylesheet" href="/<?= e(admin_url_path()) ?>assets/admin.css?v=<?= (int) filemtime(__DIR__ . '/assets/admin.css') ?>">
</head>
<body>
    <header class="topbar">
        <h1>定位后台管理</h1>
        <div class="topbar-actions">
            <label class="theme-toggle" for="themeMode">
                <span>&#20027;&#39064;</span>
                <select id="themeMode" aria-label="&#20027;&#39064;">
                    <option value="system">&#36319;&#38543;&#31995;&#32479;</option>
                    <option value="light">&#26126;&#20142;</option>
                    <option value="dark">&#26263;&#33394;</option>
                </select>
            </label>
            <span class="muted">已登录：<?= e(ADMIN_USERNAME) ?></span>
            <a class="button secondary" href="logout.php">退出</a>
        </div>
    </header>

    <main class="container">
        <?php if ($message !== ''): ?>
            <div class="alert success"><?= e($message) ?></div>
        <?php endif; ?>
        <?php if ($error !== ''): ?>
            <div class="alert error"><?= e($error) ?></div>
        <?php endif; ?>

        <datalist id="familyGroupOptions">
            <?php foreach ($familyGroups as $group): ?>
                <option value="<?= e((string) $group['group_name']) ?>"></option>
            <?php endforeach; ?>
        </datalist>

        <div class="grid">
            <section class="panel">
                <h2>公告管理</h2>
                <form method="post">
                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                    <input type="hidden" name="action" value="save_announcement">
                    <div class="field">
                        <label for="announcement_title">公告标题</label>
                        <input id="announcement_title" name="title" value="<?= e((string) ($announcement['title'] ?? '')) ?>">
                    </div>
                    <div class="field">
                        <label for="announcement_body">公告内容</label>
                        <textarea id="announcement_body" name="body" rows="5"><?= e((string) ($announcement['body'] ?? '')) ?></textarea>
                    </div>
                    <label class="check-line">
                        <input name="is_active" type="checkbox" value="1" <?= !empty($announcement['is_active']) ? 'checked' : '' ?>>
                        <span>启用公告</span>
                    </label>
                    <button type="submit">保存公告</button>
                </form>
            </section>

            <section class="panel">
                <h2>邀请码管理</h2>
                <form class="compact-form" method="post">
                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                    <input type="hidden" name="action" value="add_invite_code">
                    <div class="field">
                        <label for="invite_code">邀请码</label>
                        <input id="invite_code" name="code" placeholder="留空自动生成">
                    </div>
                    <div class="field">
                        <label for="invite_note">备注名</label>
                        <input id="invite_note" name="note" placeholder="仅后台可见">
                    </div>
                    <div class="field">
                        <label for="invite_type">类型</label>
                        <select id="invite_type" name="invite_type">
                            <option value="invite">纯邀请</option>
                            <option value="group_create">组创建</option>
                        </select>
                    </div>
                    <div class="field">
                        <label for="invite_max_uses">可使用次数</label>
                        <input id="invite_max_uses" name="max_uses" type="number" min="1" value="1">
                    </div>
                    <button type="submit">添加邀请码</button>
                </form>
                <div class="group-list">
                    <?php if (!$inviteCodes): ?>
                        <div class="muted">暂无邀请码。</div>
                    <?php endif; ?>
                    <?php foreach ($inviteCodes as $invite): ?>
                        <div class="group-row">
                            <div class="group-summary">
                                <strong><?= e((string) $invite['code']) ?></strong>
                                <span class="muted">
                                    <?= ((string) $invite['invite_type'] === 'group_create') ? '组创建' : '纯邀请' ?>
                                    · <?= (int) $invite['used_count'] ?>/<?= (int) $invite['max_uses'] ?>
                                    · <?= ((int) $invite['is_active'] === 1) ? '启用' : '停用' ?>
                                </span>
                                <?php if (!empty($invite['note'])): ?>
                                    <span class="muted">备注：<?= e((string) $invite['note']) ?></span>
                                <?php endif; ?>
                                <?php if (!empty($invite['assigned_group_name'])): ?>
                                    <span class="muted">绑定：<?= e((string) $invite['assigned_group_name']) ?></span>
                                <?php endif; ?>
                            </div>
                            <details class="row-more">
                                <summary>更多操作</summary>
                                <form class="inline-form" method="post">
                                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                    <input type="hidden" name="action" value="update_invite_note">
                                    <input type="hidden" name="invite_id" value="<?= (int) $invite['id'] ?>">
                                    <input name="note" value="<?= e((string) ($invite['note'] ?? '')) ?>" placeholder="备注名（仅后台可见）">
                                    <button class="small secondary" type="submit">保存备注</button>
                                </form>
                                <form class="inline-form" method="post">
                                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                    <input type="hidden" name="action" value="toggle_invite_code">
                                    <input type="hidden" name="invite_id" value="<?= (int) $invite['id'] ?>">
                                    <input type="hidden" name="next" value="<?= ((int) $invite['is_active'] === 1) ? 0 : 1 ?>">
                                    <button class="small secondary" type="submit"><?= ((int) $invite['is_active'] === 1) ? '停用' : '启用' ?></button>
                                </form>
                                <form class="inline-form" method="post" data-confirm="确认删除这个邀请码？">
                                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                    <input type="hidden" name="action" value="delete_invite_code">
                                    <input type="hidden" name="invite_id" value="<?= (int) $invite['id'] ?>">
                                    <button class="small danger" type="submit">删除</button>
                                </form>
                            </details>
                        </div>
                    <?php endforeach; ?>
                </div>
            </section>
        </div>

        <div class="grid">
            <section class="panel">
                <h2>家庭组管理</h2>
                <form class="compact-form" method="post" autocomplete="off">
                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                    <input type="hidden" name="action" value="add_family_group">
                    <div class="field">
                        <label for="new_group_name">家庭组名称</label>
                        <input id="new_group_name" name="group_name" required>
                    </div>
                    <button type="submit">添加家庭组</button>
                </form>

                <div class="group-list">
                    <?php if (!$familyGroups): ?>
                        <div class="muted">还没有家庭组。</div>
                    <?php endif; ?>
                    <?php foreach ($familyGroups as $group): ?>
                        <div class="group-row">
                            <div class="group-summary">
                                <strong><?= e((string) ($group['display_name'] ?: $group['group_name'])) ?></strong>
                                <span class="muted">组号：<?= e((string) ($group['group_code'] ?? '')) ?></span>
                                <span class="muted">成员：<?= (int) $group['member_count'] ?></span>
                            </div>
                            <details class="row-more">
                                <summary>更多操作</summary>
                                <form class="group-form" method="post">
                                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                    <input type="hidden" name="action" value="update_family_group">
                                    <input type="hidden" name="group_id" value="<?= (int) $group['id'] ?>">
                                    <label>
                                        <span>家庭组</span>
                                        <input name="group_name" value="<?= e((string) ($group['display_name'] ?: $group['group_name'])) ?>" required>
                                    </label>
                                    <button class="small" type="submit">保存</button>
                                </form>
                                <form class="inline-form" method="post" data-confirm="确认删除这个家庭组？组内身份和定位记录会一并清除。">
                                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                    <input type="hidden" name="action" value="delete_family_group">
                                    <input type="hidden" name="group_id" value="<?= (int) $group['id'] ?>">
                                    <button class="small danger" type="submit">删除</button>
                                </form>
                            </details>
                        </div>
                    <?php endforeach; ?>
                </div>
            </section>

            <section class="panel">
                <h2>添加 App 账号</h2>
                <form method="post" autocomplete="off">
                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                    <input type="hidden" name="action" value="add_user">

                    <div class="field">
                        <label for="username">登录账号</label>
                        <input id="username" name="username" required>
                    </div>
                    <div class="field">
                        <label for="password">登录密码</label>
                        <input id="password" name="password" type="password" required>
                    </div>
                    <div class="field">
                        <label for="display_name">显示名称</label>
                        <input id="display_name" name="display_name" placeholder="例如：爸爸、孩子手机">
                    </div>
                    <div class="field">
                        <label for="group_name">初始家庭组</label>
                        <input id="group_name" name="group_name" list="familyGroupOptions" required placeholder="同一家庭填同一个组名">
                    </div>
                    <div class="field">
                        <label for="role">初始账号类型</label>
                        <select id="role" name="role" required>
                            <option value="monitor">监测端</option>
                            <option value="guardian">监护端</option>
                        </select>
                    </div>
                    <div class="field">
                        <label for="report_interval_minutes">上报间隔（分钟）</label>
                        <input id="report_interval_minutes" name="report_interval_minutes" type="number" min="1" max="1440" value="5" required>
                    </div>
                    <button type="submit">添加账号</button>
                </form>
            </section>
        </div>

        <section class="panel account-panel">
            <h2>账号列表</h2>
            <form class="pager-form" method="get">
                <label>
                    <span>每页数量</span>
                    <select name="user_per_page" onchange="this.form.submit()">
                        <option value="10" <?= $userPerPage === 10 ? 'selected' : '' ?>>10 条</option>
                        <option value="20" <?= $userPerPage === 20 ? 'selected' : '' ?>>20 条</option>
                        <option value="50" <?= $userPerPage === 50 ? 'selected' : '' ?>>50 条</option>
                    </select>
                </label>
                <input type="hidden" name="user_page" value="<?= (int) $userPage ?>">
                <span class="muted">第 <?= (int) $userPage ?> / <?= (int) $userTotalPages ?> 页，共 <?= (int) $userTotal ?> 个账号</span>
            </form>
            <div class="table-wrap">
                <table>
                    <thead>
                        <tr>
                            <th>账号</th>
                            <th>显示名称</th>
                            <th>上报间隔</th>
                            <th>状态</th>
                            <th>家庭组身份</th>
                            <th>操作</th>
                        </tr>
                    </thead>
                    <tbody>
                        <?php if (!$users): ?>
                            <tr>
                                <td colspan="6" class="muted">还没有账号。</td>
                            </tr>
                        <?php endif; ?>
                        <?php foreach ($users as $user): ?>
                            <?php $userMemberships = $membershipsByUser[(int) $user['id']] ?? []; ?>
                            <?php $userAgreementAcceptedAt = (string) ($user['user_agreement_accepted_at'] ?? ($user['terms_accepted_at'] ?? '')); ?>
                            <?php $privacyPolicyAcceptedAt = (string) ($user['privacy_policy_accepted_at'] ?? ($user['terms_accepted_at'] ?? '')); ?>
                            <?php $crossBorderAcceptedAt = (string) ($user['cross_border_transfer_accepted_at'] ?? ''); ?>
                            <tr>
                                <td><?= e((string) $user['username']) ?></td>
                                <td><?= e((string) $user['display_name']) ?></td>
                                <td><?= membership_minutes($user) ?> 分钟</td>
                                <td><?= ((int) $user['is_active'] === 1) ? '启用' : '停用' ?></td>
                                <td>
                                    <div class="membership-summary">
                                        <?php foreach ($userMemberships as $membership): ?>
                                            <span class="badge <?= e((string) $membership['role']) ?>">
                                                <?= e((string) $membership['group_name']) ?> / <?= e(role_label((string) $membership['role'])) ?>
                                            </span>
                                        <?php endforeach; ?>
                                    </div>
                                    <details class="row-more">
                                        <summary>更多操作</summary>
                                        <div class="membership-list">
                                        <?php foreach ($userMemberships as $membership): ?>
                                            <div class="membership-card">
                                                <form class="membership-form" method="post">
                                                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                                    <input type="hidden" name="action" value="update_membership">
                                                    <input type="hidden" name="membership_id" value="<?= (int) $membership['id'] ?>">
                                                    <label>
                                                        <span>家庭组</span>
                                                        <input name="group_name" list="familyGroupOptions" value="<?= e((string) $membership['group_name']) ?>" required>
                                                    </label>
                                                    <label>
                                                        <span>身份</span>
                                                        <select name="role" required>
                                                            <option value="monitor" <?= normalize_role((string) $membership['role']) === 'monitor' ? 'selected' : '' ?>>监测端</option>
                                                            <option value="guardian" <?= ((string) $membership['role'] === 'guardian') ? 'selected' : '' ?>>监护端</option>
                                                        </select>
                                                    </label>
                                                    <div class="membership-location">
                                                        <?php if ($membership['location_updated_at']): ?>
                                                            <?= e((string) $membership['latitude']) ?>,
                                                            <?= e((string) $membership['longitude']) ?><br>
                                                            <span class="muted"><?= e(format_datetime((string) $membership['location_updated_at'])) ?></span>
                                                        <?php else: ?>
                                                            <span class="muted">暂无位置</span>
                                                        <?php endif; ?>
                                                    </div>
                                                    <button class="small" type="submit">保存身份</button>
                                                </form>
                                                <form class="inline-form" method="post" data-confirm="确认删除这个家庭组身份？">
                                                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                                    <input type="hidden" name="action" value="delete_membership">
                                                    <input type="hidden" name="membership_id" value="<?= (int) $membership['id'] ?>">
                                                    <button class="small danger" type="submit" <?= count($userMemberships) <= 1 ? 'disabled' : '' ?>>删除身份</button>
                                                </form>
                                            </div>
                                        <?php endforeach; ?>

                                        <form class="add-membership-form" method="post">
                                            <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                            <input type="hidden" name="action" value="add_membership">
                                            <input type="hidden" name="user_id" value="<?= (int) $user['id'] ?>">
                                            <input name="group_name" list="familyGroupOptions" placeholder="添加家庭组" required>
                                            <select name="role" required>
                                                <option value="monitor">监测端</option>
                                                <option value="guardian">监护端</option>
                                            </select>
                                            <button class="small secondary" type="submit">添加身份</button>
                                        </form>
                                        </div>
                                    </details>
                                </td>
                                <td>
                                    <details class="row-more">
                                        <summary>更多操作</summary>
                                        <div class="actions">
                                        <form class="edit-form" method="post">
                                            <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                            <input type="hidden" name="action" value="update_user">
                                            <input type="hidden" name="user_id" value="<?= (int) $user['id'] ?>">
                                            <label>
                                                <span>账号名称</span>
                                                <input name="username" value="<?= e((string) $user['username']) ?>" required>
                                            </label>
                                            <label>
                                                <span>显示名称</span>
                                                <input name="display_name" value="<?= e((string) $user['display_name']) ?>">
                                            </label>
                                            <label>
                                                <span>上报间隔（分钟）</span>
                                                <input name="report_interval_minutes" type="number" min="1" max="1440" value="<?= membership_minutes($user) ?>" required>
                                            </label>
                                            <button class="small" type="submit">保存账号</button>
                                        </form>
                                        <form class="inline-form" method="post">
                                            <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                            <input type="hidden" name="action" value="toggle_user">
                                            <input type="hidden" name="user_id" value="<?= (int) $user['id'] ?>">
                                            <input type="hidden" name="next" value="<?= ((int) $user['is_active'] === 1) ? 0 : 1 ?>">
                                            <button class="small secondary" type="submit">
                                                <?= ((int) $user['is_active'] === 1) ? '停用' : '启用' ?>
                                            </button>
                                        </form>
                                        <form class="inline-form" method="post">
                                            <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                            <input type="hidden" name="action" value="reset_password">
                                            <input type="hidden" name="user_id" value="<?= (int) $user['id'] ?>">
                                            <input name="new_password" placeholder="新密码" required>
                                            <button class="small secondary" type="submit">重置</button>
                                        </form>
                                        <form class="inline-form" method="post" data-confirm="确认删除这个账号和定位记录？">
                                            <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                            <input type="hidden" name="action" value="delete_user">
                                            <input type="hidden" name="user_id" value="<?= (int) $user['id'] ?>">
                                            <button class="small danger" type="submit">删除账号</button>
                                        </form>
                                        <div class="environment-box">
                                            <div class="environment-status">
                                                <span>用户协议：</span>
                                                <?php if ($userAgreementAcceptedAt !== ''): ?>
                                                    <strong>已同意</strong>
                                                    <span class="muted"><?= e(format_datetime($userAgreementAcceptedAt)) ?></span>
                                                <?php else: ?>
                                                    <strong>未同意</strong>
                                                <?php endif; ?>
                                            </div>
                                            <div class="environment-status">
                                                <span>隐私条约：</span>
                                                <?php if ($privacyPolicyAcceptedAt !== ''): ?>
                                                    <strong>已同意</strong>
                                                    <span class="muted"><?= e(format_datetime($privacyPolicyAcceptedAt)) ?></span>
                                                <?php else: ?>
                                                    <strong>未同意</strong>
                                                <?php endif; ?>
                                            </div>
                                            <div class="environment-status">
                                                <span>跨境传输协议：</span>
                                                <?php if ($crossBorderAcceptedAt !== ''): ?>
                                                    <strong>已同意</strong>
                                                    <span class="muted"><?= e(format_datetime($crossBorderAcceptedAt)) ?></span>
                                                <?php else: ?>
                                                    <strong>未同意</strong>
                                                <?php endif; ?>
                                            </div>
                                        </div>
                                        </div>
                                    </details>
                                </td>
                            </tr>
                        <?php endforeach; ?>
                    </tbody>
                </table>
            </div>
            <div class="pager-actions">
                <?php $prevPage = max(1, (int) $userPage - 1); ?>
                <?php $nextPage = min((int) $userTotalPages, (int) $userPage + 1); ?>
                <a class="button secondary <?= $userPage <= 1 ? 'disabled-link' : '' ?>" href="?user_page=<?= $prevPage ?>&user_per_page=<?= (int) $userPerPage ?>">上一页</a>
                <a class="button secondary <?= $userPage >= $userTotalPages ? 'disabled-link' : '' ?>" href="?user_page=<?= $nextPage ?>&user_per_page=<?= (int) $userPerPage ?>">下一页</a>
            </div>
        </section>

        <section class="panel account-panel">
            <h2>历史定位记录</h2>
            <form class="pager-form history-filter-form" method="get">
                <input type="hidden" name="user_page" value="<?= (int) $userPage ?>">
                <input type="hidden" name="user_per_page" value="<?= (int) $userPerPage ?>">
                <label>
                    <span>家庭组</span>
                    <select name="history_group">
                        <option value="">全部家庭组</option>
                        <?php foreach ($familyGroups as $group): ?>
                            <option value="<?= e((string) $group['group_name']) ?>" <?= hash_equals((string) $group['group_name'], $historyGroup) ? 'selected' : '' ?>>
                                <?= e((string) $group['group_name']) ?>
                            </option>
                        <?php endforeach; ?>
                    </select>
                </label>
                <label>
                    <span>成员</span>
                    <select name="history_user_id">
                        <option value="0">全部成员</option>
                        <?php foreach ($allUsersForFilter as $filterUser): ?>
                            <?php $filterLabel = trim((string) $filterUser['display_name']) !== '' ? (string) $filterUser['display_name'] : (string) $filterUser['username']; ?>
                            <option value="<?= (int) $filterUser['id'] ?>" <?= (int) $filterUser['id'] === (int) $historyUserId ? 'selected' : '' ?>>
                                <?= e($filterLabel) ?> / <?= e((string) $filterUser['username']) ?>
                            </option>
                        <?php endforeach; ?>
                    </select>
                </label>
                <label>
                    <span>每页数量</span>
                    <select name="history_per_page">
                        <option value="20" <?= $historyPerPage === 20 ? 'selected' : '' ?>>20 条</option>
                        <option value="50" <?= $historyPerPage === 50 ? 'selected' : '' ?>>50 条</option>
                        <option value="100" <?= $historyPerPage === 100 ? 'selected' : '' ?>>100 条</option>
                    </select>
                </label>
                <input type="hidden" name="history_page" value="1">
                <button class="secondary" type="submit">筛选</button>
                <span class="muted">第 <?= (int) $historyPage ?> / <?= (int) $historyTotalPages ?> 页，共 <?= (int) $historyTotal ?> 条</span>
            </form>

            <div class="history-record-list">
                <?php if (!$historyLocations): ?>
                    <div class="history-record-empty muted">暂无历史定位记录。</div>
                <?php endif; ?>
                <?php foreach ($historyLocations as $location): ?>
                    <?php
                    $displayName = trim((string) $location['display_name']) !== '' ? (string) $location['display_name'] : (string) $location['username'];
                    $addressSummary = location_address_summary((string) ($location['address_diagnostics'] ?? ''));
                    $diagnosticSources = location_diagnostics_sources((string) ($location['address_diagnostics'] ?? ''));
                    $coordinateText = (string) $location['latitude'] . ', ' . (string) $location['longitude'];
                    $altitudeText = $location['altitude'] === null ? '' : (string) round((float) $location['altitude']) . 'm';
                    $accuracyText = $location['accuracy'] === null ? '未知' : (string) round((float) $location['accuracy']) . 'm';
                    $headingText = $location['heading'] === null ? '' : (string) round((float) $location['heading']) . '°';
                    $speedText = $location['speed'] === null ? '' : number_format((float) $location['speed'], 2) . ' m/s';
                    $statusText = ((int) $location['address_mismatch'] === 1) ? '位置信息不一致' : '正常';
                    $locationMeta = !empty($location['location_meta']) ? json_decode((string) $location['location_meta'], true) : [];
                    $locationMeta = is_array($locationMeta) ? $locationMeta : [];
                    ?>
                    <article class="history-record-card">
                        <div class="history-record-head">
                            <div class="history-record-main">
                                <div class="history-person-line">
                                    <strong><?= e((string) $location['username']) ?></strong>
                                    <span><?= e($displayName) ?></span>
                                </div>
                                <div class="history-meta-line">
                                    <span class="history-group-pill"><?= e((string) $location['group_name']) ?></span>
                                    <time class="history-time"><?= e(format_datetime((string) $location['created_at'])) ?></time>
                                </div>
                            </div>
                            <form class="inline-form history-delete-form history-card-delete-form" method="post" data-confirm="确认删除这条历史定位记录？">
                                <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                <input type="hidden" name="action" value="delete_location">
                                <input type="hidden" name="location_id" value="<?= (int) $location['id'] ?>">
                                <button class="small danger compact-danger" type="submit">删除</button>
                            </form>
                        </div>
                        <div class="history-record-actions">
                            <details class="row-more history-more">
                                <summary>更多信息</summary>
                                <div class="history-detail-grid">
                                    <div class="history-detail-item">
                                        <span>账号类型</span>
                                        <strong><?= e(role_label((string) $location['role'])) ?></strong>
                                    </div>
                                    <div class="history-detail-item">
                                        <span>状态</span>
                                        <strong><?= e($statusText) ?></strong>
                                    </div>
                                    <div class="history-detail-item full">
                                        <span>坐标</span>
                                        <strong><?= e($coordinateText) ?></strong>
                                    </div>
                                    <?php if ($altitudeText !== ''): ?>
                                    <div class="history-detail-item">
                                        <span>高度</span>
                                        <strong><?= e($altitudeText) ?></strong>
                                    </div>
                                    <?php endif; ?>
                                    <div class="history-detail-item">
                                        <span>精度</span>
                                        <strong><?= e($accuracyText) ?></strong>
                                    </div>
                                    <?php if ($headingText !== ''): ?>
                                    <div class="history-detail-item">
                                        <span>方向</span>
                                        <strong><?= e($headingText) ?></strong>
                                    </div>
                                    <?php endif; ?>
                                    <?php if ($speedText !== ''): ?>
                                    <div class="history-detail-item">
                                        <span>速度</span>
                                        <strong><?= e($speedText) ?></strong>
                                    </div>
                                    <?php endif; ?>
                                    <?php if (!empty($locationMeta['provider'])): ?>
                                    <div class="history-detail-item">
                                        <span>定位来源</span>
                                        <strong><?= e((string) $locationMeta['provider']) ?></strong>
                                    </div>
                                    <?php endif; ?>
                                    <?php if (!empty($locationMeta['location_time'])): ?>
                                    <div class="history-detail-item">
                                        <span>定位时间戳</span>
                                        <strong><?= e((string) $locationMeta['location_time']) ?></strong>
                                    </div>
                                    <?php endif; ?>
                                    <?php foreach ([
                                        'vertical_accuracy' => '垂直精度',
                                        'bearing_accuracy' => '方向精度',
                                        'speed_accuracy' => '速度精度',
                                    ] as $metaKey => $metaLabel): ?>
                                        <?php if (isset($locationMeta[$metaKey]) && is_numeric($locationMeta[$metaKey])): ?>
                                        <div class="history-detail-item">
                                            <span><?= e($metaLabel) ?></span>
                                            <strong><?= e((string) round((float) $locationMeta[$metaKey], 2)) ?></strong>
                                        </div>
                                        <?php endif; ?>
                                    <?php endforeach; ?>
                                    <div class="history-detail-item full">
                                        <span>地址</span>
                                        <strong><?= $addressSummary === '' ? '暂无' : e($addressSummary) ?></strong>
                                    </div>
                                    <?php foreach ($diagnosticSources as $source): ?>
                                    <div class="history-detail-item full">
                                        <span><?= e((string) $source['label']) ?></span>
                                        <strong><?= e((string) $source['address']) ?> / 城市：<?= e((string) $source['city']) ?></strong>
                                    </div>
                                    <?php endforeach; ?>
                                </div>
                            </details>
                        </div>
                    </article>
                <?php endforeach; ?>
            </div>

            <div class="pager-actions">
                <?php $historyPrevPage = max(1, (int) $historyPage - 1); ?>
                <?php $historyNextPage = min((int) $historyTotalPages, (int) $historyPage + 1); ?>
                <a class="button secondary <?= $historyPage <= 1 ? 'disabled-link' : '' ?>" href="<?= e(admin_query(['history_page' => $historyPrevPage])) ?>">上一页</a>
                <a class="button secondary <?= $historyPage >= $historyTotalPages ? 'disabled-link' : '' ?>" href="<?= e(admin_query(['history_page' => $historyNextPage])) ?>">下一页</a>
            </div>
        </section>
    </main>
    <script src="/<?= e(admin_url_path()) ?>assets/admin-theme.js?v=<?= (int) filemtime(__DIR__ . '/assets/admin-theme.js') ?>" defer></script>
    <script src="/assets/popup-select.js" defer></script>
</body>
</html>

