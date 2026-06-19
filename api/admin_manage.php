<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();

if (!is_admin_logged_in()) {
    json_response(['ok' => false, 'message' => '请先登录后台。'], 401);
}

function admin_manage_input(): array
{
    $raw = file_get_contents('php://input') ?: '{}';
    $data = json_decode($raw, true);
    return is_array($data) ? $data : [];
}

function admin_manage_string(array $data, string $key, int $maxLength = 255): string
{
    $value = trim((string) ($data[$key] ?? ''));
    if (mb_strlen($value) > $maxLength) {
        $value = mb_substr($value, 0, $maxLength);
    }
    return $value;
}

try {
    $pdo = db();
    $data = admin_manage_input();
    $action = admin_manage_string($data, 'action', 64);
    $message = '';

    if ($action === 'update_security_settings') {
        foreach (app_setting_keys() as $settingKey) {
            set_app_setting($settingKey, !empty($data[$settingKey]) ? '1' : '0');
        }
        $message = '安全策略已保存。';
    } elseif ($action === 'add_family_group') {
        $groupName = admin_manage_string($data, 'group_name', 100);
        if ($groupName === '') {
            throw new RuntimeException('家庭组名称不能为空。');
        }
        create_family_group_record($pdo, $groupName);
        $message = '家庭组已添加。';
    } elseif ($action === 'update_family_group') {
        $groupId = (int) ($data['group_id'] ?? 0);
        $groupName = admin_manage_string($data, 'group_name', 100);
        if ($groupId <= 0 || $groupName === '') {
            throw new RuntimeException('家庭组不存在或名称为空。');
        }
        $stmt = $pdo->prepare('SELECT id FROM family_groups WHERE id = ? LIMIT 1');
        $stmt->execute([$groupId]);
        if (!$stmt->fetch()) {
            throw new RuntimeException('家庭组不存在。');
        }
        $stmt = $pdo->prepare('UPDATE family_groups SET display_name = ? WHERE id = ?');
        $stmt->execute([$groupName, $groupId]);
        $message = '家庭组已更新。';
    } elseif ($action === 'update_group_owner') {
        $groupId = (int) ($data['group_id'] ?? 0);
        $ownerUserId = (int) ($data['owner_user_id'] ?? 0);
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
    } elseif ($action === 'delete_family_group') {
        $groupId = (int) ($data['group_id'] ?? 0);
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
                $stmt->execute([(string) $fallbackMembership['group_name'], (string) $fallbackMembership['role'], $affectedUserId, $groupName]);
                continue;
            }
            $stmt = $pdo->prepare("UPDATE users SET group_name = '', role = 'guardian' WHERE id = ? AND group_name = ?");
            $stmt->execute([$affectedUserId, $groupName]);
        }
        $pdo->commit();
        $message = '家庭组已删除，组内定位记录已清除。';
    } elseif ($action === 'save_announcement') {
        $title = admin_manage_string($data, 'title', 120);
        $body = trim((string) ($data['body'] ?? ''));
        $isActive = !empty($data['is_active']) ? 1 : 0;
        $stmt = $pdo->query('SELECT id FROM announcements ORDER BY id DESC LIMIT 1');
        $announcementId = (int) ($stmt->fetchColumn() ?: 0);
        if ($announcementId > 0) {
            $stmt = $pdo->prepare('UPDATE announcements SET title = ?, body = ?, is_active = ?, version = version + 1 WHERE id = ?');
            $stmt->execute([$title, $body, $isActive, $announcementId]);
        } else {
            $stmt = $pdo->prepare('INSERT INTO announcements (title, body, is_active) VALUES (?, ?, ?)');
            $stmt->execute([$title, $body, $isActive]);
        }
        $message = '公告已保存。';
    } elseif ($action === 'add_invite_code') {
        $code = strtolower(admin_manage_string($data, 'code', 64));
        $note = admin_manage_string($data, 'note', 120);
        $inviteType = admin_manage_string($data, 'invite_type', 32);
        $allowGroupOwner = !empty($data['allow_group_owner']) ? 1 : 0;
        $maxUses = max(1, min(9999, (int) ($data['max_uses'] ?? 1)));
        if ($code === '') {
            $alphabet = '0123456789abcdefghijklmnopqrstuvwxyz';
            for ($index = 0; $index < 6; $index += 1) {
                $code .= $alphabet[random_int(0, strlen($alphabet) - 1)];
            }
        }
        if (!preg_match('/^[0-9a-z]{4,64}$/', $code)) {
            throw new RuntimeException('邀请码只能包含小写字母和数字。');
        }
        if (!in_array($inviteType, ['invite', 'group_create'], true)) {
            throw new RuntimeException('邀请码类型不正确。');
        }
        $stmt = $pdo->prepare('INSERT INTO invite_codes (code, note, invite_type, allow_group_owner, max_uses) VALUES (?, ?, ?, ?, ?)');
        $stmt->execute([$code, $note, $inviteType, $allowGroupOwner, $maxUses]);
        $message = '邀请码已添加：' . $code;
    } elseif ($action === 'update_invite_note') {
        $inviteId = (int) ($data['invite_id'] ?? 0);
        $note = admin_manage_string($data, 'note', 120);
        if ($inviteId <= 0) {
            throw new RuntimeException('邀请码不存在。');
        }
        $stmt = $pdo->prepare('UPDATE invite_codes SET note = ? WHERE id = ?');
        $stmt->execute([$note, $inviteId]);
        $message = '邀请码备注已保存。';
    } elseif ($action === 'delete_invite_code') {
        $inviteId = (int) ($data['invite_id'] ?? 0);
        if ($inviteId <= 0) {
            throw new RuntimeException('邀请码不存在。');
        }
        $stmt = $pdo->prepare('DELETE FROM invite_codes WHERE id = ?');
        $stmt->execute([$inviteId]);
        $message = '邀请码已删除。';
    } elseif ($action === 'toggle_invite_code') {
        $inviteId = (int) ($data['invite_id'] ?? 0);
        $next = !empty($data['next']) ? 1 : 0;
        if ($inviteId <= 0) {
            throw new RuntimeException('邀请码不存在。');
        }
        $stmt = $pdo->prepare('UPDATE invite_codes SET is_active = ? WHERE id = ?');
        $stmt->execute([$next, $inviteId]);
        $message = $next === 1 ? '邀请码已启用。' : '邀请码已停用。';
    } elseif ($action === 'update_user') {
        $userId = (int) ($data['user_id'] ?? 0);
        $username = admin_manage_string($data, 'username', 64);
        $displayName = admin_manage_string($data, 'display_name', 100);
        $reportIntervalSeconds = normalize_report_interval_seconds((int) ($data['report_interval_seconds'] ?? DEFAULT_REPORT_INTERVAL_SECONDS));
        $debugMode = !empty($data['debug_mode']) ? 1 : 0;
        if ($userId <= 0 || $username === '') {
            throw new RuntimeException('账号不存在或名称为空。');
        }
        ensure_app_username_available($pdo, $username, $userId);
        $stmt = $pdo->prepare('UPDATE users SET username = ?, display_name = ?, report_interval_seconds = ?, debug_mode = ? WHERE id = ?');
        $stmt->execute([$username, $displayName, $reportIntervalSeconds, $debugMode, $userId]);
        $message = '账号信息已更新。';
    } elseif ($action === 'toggle_user') {
        $userId = (int) ($data['user_id'] ?? 0);
        $next = !empty($data['next']) ? 1 : 0;
        $disabledReason = $next === 1 ? '' : admin_manage_string($data, 'disabled_reason', 255);
        if ($userId <= 0) {
            throw new RuntimeException('账号不存在。');
        }
        $stmt = $pdo->prepare('UPDATE users SET is_active = ?, disabled_reason = ? WHERE id = ?');
        $stmt->execute([$next, $disabledReason, $userId]);
        record_user_log($userId, '', $next === 1 ? 'user_enable' : 'user_disable', $disabledReason);
        $message = $next === 1 ? '账号已启用。' : '账号已停用。';
    } elseif ($action === 'reset_password') {
        $userId = (int) ($data['user_id'] ?? 0);
        $password = (string) ($data['new_password'] ?? '');
        if ($userId <= 0 || $password === '') {
            throw new RuntimeException('账号或新密码不能为空。');
        }
        $stmt = $pdo->prepare('UPDATE users SET password_hash = ? WHERE id = ?');
        $stmt->execute([password_hash($password, PASSWORD_DEFAULT), $userId]);
        clear_failed_login($pdo, $userId);
        $message = '密码已重置。';
    } elseif ($action === 'reply_ticket') {
        $ticketId = (int) ($data['ticket_id'] ?? 0);
        $reply = admin_manage_string($data, 'reply', 2000);
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
    } elseif ($action === 'update_ticket_status') {
        $ticketId = (int) ($data['ticket_id'] ?? 0);
        $status = admin_manage_string($data, 'status', 16);
        if ($ticketId <= 0 || !in_array($status, ['open', 'closed'], true)) {
            throw new RuntimeException('工单状态不正确。');
        }
        $stmt = $pdo->prepare('UPDATE support_tickets SET status = ?, updated_at = NOW() WHERE id = ?');
        $stmt->execute([$status, $ticketId]);
        $message = $status === 'closed' ? '工单已关闭。' : '工单已重新打开。';
    } else {
        throw new RuntimeException('暂不支持的后台操作。');
    }

    record_user_log(null, '', 'admin_' . $action, $message);
    latest_locations_cache_forget_all();
    json_response(['ok' => true, 'message' => $message]);
} catch (Throwable $th) {
    if (isset($pdo) && $pdo instanceof PDO && $pdo->inTransaction()) {
        $pdo->rollBack();
    }
    json_response(['ok' => false, 'message' => api_error_message($th)], 500);
}
