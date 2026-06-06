<?php

declare(strict_types=1);

try {
    $pdo = db();
    $userPage = max(1, (int) ($_GET['user_page'] ?? 1));
    $userPerPage = (int) ($_GET['user_per_page'] ?? 20);
    $historyPage = max(1, (int) ($_GET['history_page'] ?? 1));
    $historyPerPage = (int) ($_GET['history_per_page'] ?? 20);
    $historyGroup = trim((string) ($_GET['history_group'] ?? ''));
    $historyUserId = (int) ($_GET['history_user_id'] ?? 0);
    $logGroup = trim((string) ($_GET['log_group'] ?? ''));
    $logUserId = (int) ($_GET['log_user_id'] ?? 0);
    $logType = trim((string) ($_GET['log_type'] ?? ''));
    $logPage = max(1, (int) ($_GET['log_page'] ?? 1));
    $logPerPage = (int) ($_GET['log_per_page'] ?? 20);

    if (!in_array($userPerPage, [10, 20, 50], true)) {
        $userPerPage = 20;
    }
    if (!in_array($historyPerPage, [20, 50, 100], true)) {
        $historyPerPage = 20;
    }
    if (!in_array($logPerPage, [20, 50, 100], true)) {
        $logPerPage = 20;
    }

    if ($_SERVER['REQUEST_METHOD'] === 'POST') {
        require_csrf();

        $action = post_string('action', 32);

        if ($action === 'update_security_settings') {
            foreach (app_setting_keys() as $key) {
                set_app_setting($key, isset($_POST[$key]) ? '1' : '0');
            }
            $message = '安全策略已保存。';
        }

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
            $allowGroupOwner = isset($_POST['allow_group_owner']) ? 1 : 0;
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
            $stmt = $pdo->prepare('INSERT INTO invite_codes (code, note, invite_type, allow_group_owner, max_uses) VALUES (?, ?, ?, ?, ?)');
            $stmt->execute([$code, $note, $inviteType, $allowGroupOwner, $maxUses]);
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

        if ($action === 'update_group_owner') {
            $groupId = (int) ($_POST['group_id'] ?? 0);
            $ownerUserId = (int) ($_POST['owner_user_id'] ?? 0);

            $stmt = $pdo->prepare('SELECT * FROM family_groups WHERE id = ? LIMIT 1');
            $stmt->execute([$groupId]);
            $group = $stmt->fetch();
            if (!$group) {
                throw new RuntimeException('家庭组不存在。');
            }

            if ($ownerUserId > 0) {
                $stmt = $pdo->prepare('SELECT id FROM user_groups WHERE user_id = ? AND group_name = ? LIMIT 1');
                $stmt->execute([$ownerUserId, (string) $group['group_name']]);
                if (!$stmt->fetch()) {
                    throw new RuntimeException('只能设置组内成员为管理员。');
                }
            }

            $stmt = $pdo->prepare('UPDATE family_groups SET owner_user_id = ? WHERE id = ?');
            $stmt->execute([$ownerUserId > 0 ? $ownerUserId : null, $groupId]);
            record_user_log($ownerUserId > 0 ? $ownerUserId : null, (string) $group['group_name'], 'group_owner_update', '后台更改家庭组管理员');
            $message = '家庭组管理员已更新。';
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
            $reportIntervalSeconds = normalize_report_interval_seconds((int) ($_POST['report_interval_seconds'] ?? DEFAULT_REPORT_INTERVAL_SECONDS));

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
            $reportIntervalSeconds = normalize_report_interval_seconds((int) ($_POST['report_interval_seconds'] ?? DEFAULT_REPORT_INTERVAL_SECONDS));
            $debugMode = isset($_POST['debug_mode']) ? 1 : 0;

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
                    report_interval_seconds = ?,
                    debug_mode = ?
                WHERE id = ?
            ');
            $stmt->execute([$username, $displayName, $reportIntervalSeconds, $debugMode, $userId]);
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
            $disabledReason = $next === 1 ? '' : post_string('disabled_reason', 255);
            $stmt = $pdo->prepare('UPDATE users SET is_active = ?, disabled_reason = ? WHERE id = ?');
            $stmt->execute([$next === 1 ? 1 : 0, $disabledReason, $userId]);
            record_user_log($userId, '', $next === 1 ? 'user_enable' : 'user_disable', $disabledReason);
            $message = $next === 1 ? '账号已启用。' : '账号已停用。';
        }

        if ($action === 'delete_user') {
            $userId = (int) ($_POST['user_id'] ?? 0);
            $stmt = $pdo->prepare('DELETE FROM users WHERE id = ?');
            $stmt->execute([$userId]);
            $message = '账号已删除。';
        }

        if ($action === 'delete_user_device') {
            $deviceId = (int) ($_POST['device_id'] ?? 0);
            if ($deviceId <= 0) {
                throw new RuntimeException('设备绑定不存在。');
            }

            $stmt = $pdo->prepare('DELETE FROM user_devices WHERE id = ?');
            $stmt->execute([$deviceId]);
            $message = '设备绑定已删除。';
        }

        if ($action === 'reply_ticket') {
            $ticketId = (int) ($_POST['ticket_id'] ?? 0);
            $reply = post_string('reply', 2000);
            if ($ticketId <= 0 || $reply === '') {
                throw new RuntimeException('工单回复不能为空。');
            }

            $pdo->beginTransaction();
            $stmt = $pdo->prepare("INSERT INTO support_ticket_messages (ticket_id, sender_type, message) VALUES (?, 'admin', ?)");
            $stmt->execute([$ticketId, $reply]);
            $stmt = $pdo->prepare("UPDATE support_tickets SET status = 'open', updated_at = NOW() WHERE id = ?");
            $stmt->execute([$ticketId]);
            $pdo->commit();
            $message = '工单已回复。';
        }

        if ($action === 'update_ticket_status') {
            $ticketId = (int) ($_POST['ticket_id'] ?? 0);
            $status = post_string('status', 16);
            if (!in_array($status, ['open', 'closed'], true)) {
                throw new RuntimeException('工单状态不正确。');
            }
            $stmt = $pdo->prepare('UPDATE support_tickets SET status = ?, updated_at = NOW() WHERE id = ?');
            $stmt->execute([$status, $ticketId]);
            $message = $status === 'closed' ? '工单已关闭。' : '工单已重新打开。';
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
            record_user_log(null, '', 'admin_' . $action, $message);
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

    $groupMembersByGroup = [];
    $groupMembersStmt = $pdo->query('
        SELECT
            ug.group_name,
            u.id,
            u.username,
            u.display_name
        FROM user_groups ug
        INNER JOIN users u ON u.id = ug.user_id
        ORDER BY ug.group_name ASC, u.username ASC
    ');
    foreach ($groupMembersStmt->fetchAll() as $member) {
        $groupMembersByGroup[(string) $member['group_name']][] = $member;
    }

    $onlineCutoff = date('Y-m-d H:i:s', time() - 90);
    $onlineUsersStmt = $pdo->prepare('
        SELECT
            up.*,
            u.username,
            u.display_name
        FROM user_presence up
        INNER JOIN users u ON u.id = up.user_id
        WHERE up.last_seen_at >= ?
        ORDER BY up.last_seen_at DESC
        LIMIT 50
    ');
    $onlineUsersStmt->execute([$onlineCutoff]);
    $onlineUsers = $onlineUsersStmt->fetchAll();
    $onlineUserCount = count($onlineUsers);

    $logWhere = [];
    $logParams = [];
    if ($logGroup !== '') {
        $logWhere[] = 'ul.group_name = ?';
        $logParams[] = $logGroup;
    }
    if ($logUserId > 0) {
        $logWhere[] = 'ul.user_id = ?';
        $logParams[] = $logUserId;
    }
    if ($logType !== '') {
        if ($logType === 'session') {
            $logWhere[] = "ul.event_type IN ('online', 'offline')";
        } else {
            $logWhere[] = 'ul.event_type = ?';
            $logParams[] = $logType;
        }
    }
    $logWhereSql = $logWhere ? ('WHERE ' . implode(' AND ', $logWhere)) : '';
    $logCountStmt = $pdo->prepare("
        SELECT COUNT(*)
        FROM user_logs ul
        LEFT JOIN users u ON u.id = ul.user_id
        {$logWhereSql}
    ");
    $logCountStmt->execute($logParams);
    $logTotal = (int) $logCountStmt->fetchColumn();
    $logTotalPages = max(1, (int) ceil($logTotal / $logPerPage));
    if ($logPage > $logTotalPages) {
        $logPage = $logTotalPages;
    }
    $logOffset = ($logPage - 1) * $logPerPage;
    $recentUserLogsStmt = $pdo->prepare("
        SELECT
            ul.*,
            u.username,
            u.display_name
        FROM user_logs ul
        LEFT JOIN users u ON u.id = ul.user_id
        {$logWhereSql}
        ORDER BY ul.created_at DESC, ul.id DESC
        LIMIT ? OFFSET ?
    ");
    foreach ($logParams as $index => $param) {
        $recentUserLogsStmt->bindValue($index + 1, $param, is_int($param) ? PDO::PARAM_INT : PDO::PARAM_STR);
    }
    $recentUserLogsStmt->bindValue(count($logParams) + 1, $logPerPage, PDO::PARAM_INT);
    $recentUserLogsStmt->bindValue(count($logParams) + 2, $logOffset, PDO::PARAM_INT);
    $recentUserLogsStmt->execute();
    $recentUserLogs = $recentUserLogsStmt->fetchAll();

    $logTypesStmt = $pdo->query('SELECT DISTINCT event_type FROM user_logs ORDER BY event_type ASC');
    $logTypes = array_map('strval', $logTypesStmt->fetchAll(PDO::FETCH_COLUMN));

    $supportTicketsStmt = $pdo->query('
        SELECT
            t.*,
            u.username,
            u.display_name
        FROM support_tickets t
        INNER JOIN users u ON u.id = t.user_id
        ORDER BY t.status ASC, t.updated_at DESC, t.id DESC
        LIMIT 80
    ');
    $supportTickets = $supportTicketsStmt->fetchAll();
    $ticketMessagesByTicket = [];
    if ($supportTickets) {
        $ticketIds = array_map(static fn (array $ticket): int => (int) $ticket['id'], $supportTickets);
        $placeholders = implode(',', array_fill(0, count($ticketIds), '?'));
        $ticketMessagesStmt = $pdo->prepare("SELECT * FROM support_ticket_messages WHERE ticket_id IN ({$placeholders}) ORDER BY created_at ASC, id ASC");
        foreach ($ticketIds as $index => $ticketId) {
            $ticketMessagesStmt->bindValue($index + 1, $ticketId, PDO::PARAM_INT);
        }
        $ticketMessagesStmt->execute();
        foreach ($ticketMessagesStmt->fetchAll() as $ticketMessage) {
            $ticketMessagesByTicket[(int) $ticketMessage['ticket_id']][] = $ticketMessage;
        }
    }

    $announcementStmt = $pdo->query('SELECT * FROM announcements ORDER BY id DESC LIMIT 1');
    $announcement = $announcementStmt->fetch() ?: null;
    $securitySettings = security_policy_settings();

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

    $devicesByUser = [];
    $devicesStmt = $pdo->query('SELECT * FROM user_devices ORDER BY last_seen_at DESC, id DESC');
    foreach ($devicesStmt->fetchAll() as $device) {
        $devicesByUser[(int) $device['user_id']][] = $device;
    }

    $environmentReportsByUser = [];
    $environmentReportsStmt = $pdo->prepare('
        SELECT er.*
        FROM environment_reports er
        INNER JOIN (
            SELECT user_id, MAX(id) AS latest_id
            FROM environment_reports
            WHERE report_json LIKE ?
            GROUP BY user_id
        ) latest ON latest.latest_id = er.id
    ');
    $environmentReportsStmt->execute(['%"installed_apps"%']);
    foreach ($environmentReportsStmt->fetchAll() as $report) {
        $environmentReportsByUser[(int) $report['user_id']] = $report;
    }

    $deviceReportsByUser = [];
    $deviceReportsStmt = $pdo->prepare('
        SELECT er.*
        FROM environment_reports er
        INNER JOIN (
            SELECT user_id, MAX(id) AS latest_id
            FROM environment_reports
            WHERE report_json LIKE ?
            GROUP BY user_id
        ) latest ON latest.latest_id = er.id
    ');
    $deviceReportsStmt->execute(['%"report_kind":"device_integrity"%']);
    foreach ($deviceReportsStmt->fetchAll() as $report) {
        $deviceReportsByUser[(int) $report['user_id']] = $report;
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
    $groupMembersByGroup = $groupMembersByGroup ?? [];
    $onlineUsers = $onlineUsers ?? [];
    $onlineUserCount = $onlineUserCount ?? 0;
    $recentUserLogs = $recentUserLogs ?? [];
    $logTypes = $logTypes ?? [];
    $supportTickets = $supportTickets ?? [];
    $ticketMessagesByTicket = $ticketMessagesByTicket ?? [];
    $logGroup = $logGroup ?? '';
    $logUserId = $logUserId ?? 0;
    $logType = $logType ?? '';
    $logPage = $logPage ?? 1;
    $logPerPage = $logPerPage ?? 20;
    $logTotal = $logTotal ?? 0;
    $logTotalPages = $logTotalPages ?? 1;
    $announcement = $announcement ?? null;
    $securitySettings = $securitySettings ?? security_policy_settings();
    $inviteCodes = $inviteCodes ?? [];
    $users = $users ?? [];
    $membershipsByUser = $membershipsByUser ?? [];
    $devicesByUser = $devicesByUser ?? [];
    $environmentReportsByUser = $environmentReportsByUser ?? [];
    $deviceReportsByUser = $deviceReportsByUser ?? [];
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
