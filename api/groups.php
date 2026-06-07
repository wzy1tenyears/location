<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    json_response(['ok' => false, 'message' => 'Method not allowed.'], 405);
}

try {
    $user = require_user();
    $data = request_data();
    $action = trim((string) ($data['action'] ?? ''));

    if ($action === 'join_by_code') {
        $groupCode = strtolower(trim((string) ($data['group_code'] ?? '')));
        $pdo = db();
        $joinIp = client_ip_address();
        if (group_join_locked($pdo, (int) $user['id'], $joinIp)) {
            json_response(['ok' => false, 'message' => '组号尝试过多，请 30 分钟后再试。'], 423);
        }

        if (!preg_match('/^[0-9a-z]{6}$/', $groupCode)) {
            if (record_failed_group_join($pdo, (int) $user['id'], $joinIp)) {
                json_response(['ok' => false, 'message' => '组号尝试过多，请 30 分钟后再试。'], 423);
            }
            json_response(['ok' => false, 'message' => '组号格式不正确。'], 422);
        }

        $stmt = $pdo->prepare('SELECT * FROM family_groups WHERE group_code = ? LIMIT 1');
        $stmt->execute([$groupCode]);
        $group = $stmt->fetch();
        if (!$group) {
            if (record_failed_group_join($pdo, (int) $user['id'], $joinIp)) {
                json_response(['ok' => false, 'message' => '组号尝试过多，请 30 分钟后再试。'], 423);
            }
            json_response(['ok' => false, 'message' => '家庭组不存在。'], 404);
        }

        $pdo->beginTransaction();
        $stmt = $pdo->prepare('INSERT IGNORE INTO user_groups (user_id, group_name, role) VALUES (?, ?, ?)');
        $stmt->execute([(int) $user['id'], (string) $group['group_name'], 'guardian']);
        if (empty($user['group_name'])) {
            $stmt = $pdo->prepare("UPDATE users SET group_name = ?, role = 'guardian' WHERE id = ?");
            $stmt->execute([(string) $group['group_name'], (int) $user['id']]);
        }
        $pdo->commit();
        clear_failed_group_join($pdo, (int) $user['id'], $joinIp);

        $freshUser = current_user() ?: $user;
        json_response(['ok' => true, 'user' => public_user_payload($freshUser)]);
    }

    if ($action === 'leave_group') {
        $groupName = selected_group_name_from_request();
        $membership = require_user_membership($user, $groupName);
        $pdo = db();

        $pdo->beginTransaction();

        $stmt = $pdo->prepare('DELETE FROM user_groups WHERE user_id = ? AND group_name = ?');
        $stmt->execute([(int) $user['id'], (string) $membership['group_name']]);

        $stmt = $pdo->prepare('DELETE FROM latest_group_locations WHERE user_id = ? AND group_name = ?');
        $stmt->execute([(int) $user['id'], (string) $membership['group_name']]);

        $stmt = $pdo->prepare('UPDATE family_groups SET owner_user_id = NULL WHERE group_name = ? AND owner_user_id = ?');
        $stmt->execute([(string) $membership['group_name'], (int) $user['id']]);

        $stmt = $pdo->prepare('SELECT group_name, role FROM user_groups WHERE user_id = ? ORDER BY group_name ASC, id ASC LIMIT 1');
        $stmt->execute([(int) $user['id']]);
        $nextMembership = $stmt->fetch();
        if ($nextMembership) {
            $stmt = $pdo->prepare('UPDATE users SET group_name = ?, role = ? WHERE id = ?');
            $stmt->execute([(string) $nextMembership['group_name'], (string) $nextMembership['role'], (int) $user['id']]);
        } else {
            $stmt = $pdo->prepare("UPDATE users SET group_name = '', role = 'guardian' WHERE id = ?");
            $stmt->execute([(int) $user['id']]);
        }

        $pdo->commit();
        latest_locations_cache_forget_all();
        record_user_log((int) $user['id'], (string) $membership['group_name'], 'group_leave', '用户退出家庭组');

        $freshUser = current_user() ?: $user;
        json_response(['ok' => true, 'user' => public_user_payload($freshUser)]);
    }

    if ($action === 'remove_member') {
        $groupName = selected_group_name_from_request();
        $group = require_group_owner($user, $groupName);
        $targetUserId = (int) ($data['target_user_id'] ?? 0);
        if ($targetUserId <= 0) {
            json_response(['ok' => false, 'message' => '成员不存在。'], 422);
        }
        if ($targetUserId === (int) $user['id']) {
            json_response(['ok' => false, 'message' => '管理员不能踢出自己，请先移交管理员或主动退组。'], 422);
        }

        $pdo = db();
        $stmt = $pdo->prepare('SELECT * FROM user_groups WHERE user_id = ? AND group_name = ? LIMIT 1');
        $stmt->execute([$targetUserId, (string) $group['group_name']]);
        $targetMembership = $stmt->fetch();
        if (!$targetMembership) {
            json_response(['ok' => false, 'message' => '成员不在当前家庭组。'], 404);
        }

        $pdo->beginTransaction();
        $stmt = $pdo->prepare('DELETE FROM user_groups WHERE id = ?');
        $stmt->execute([(int) $targetMembership['id']]);

        $stmt = $pdo->prepare('DELETE FROM latest_group_locations WHERE user_id = ? AND group_name = ?');
        $stmt->execute([$targetUserId, (string) $group['group_name']]);

        $stmt = $pdo->prepare('SELECT group_name, role FROM user_groups WHERE user_id = ? ORDER BY group_name ASC, id ASC LIMIT 1');
        $stmt->execute([$targetUserId]);
        $nextMembership = $stmt->fetch();
        if ($nextMembership) {
            $stmt = $pdo->prepare('UPDATE users SET group_name = ?, role = ? WHERE id = ? AND group_name = ?');
            $stmt->execute([
                (string) $nextMembership['group_name'],
                (string) $nextMembership['role'],
                $targetUserId,
                (string) $group['group_name'],
            ]);
        } else {
            $stmt = $pdo->prepare("UPDATE users SET group_name = '', role = 'guardian' WHERE id = ? AND group_name = ?");
            $stmt->execute([$targetUserId, (string) $group['group_name']]);
        }
        $pdo->commit();

        latest_locations_cache_forget_all();
        record_user_log((int) $user['id'], (string) $group['group_name'], 'group_member_remove', '家庭组管理员移除成员', [
            'target_user_id' => $targetUserId,
        ]);
        record_user_log($targetUserId, (string) $group['group_name'], 'group_removed', '被家庭组管理员移出家庭组');

        $freshUser = current_user() ?: $user;
        json_response(['ok' => true, 'user' => public_user_payload($freshUser)]);
    }

    if ($action === 'reset_member_password') {
        $groupName = selected_group_name_from_request();
        $group = require_group_owner($user, $groupName);
        $targetUserId = (int) ($data['target_user_id'] ?? 0);
        $newPassword = trim((string) ($data['new_password'] ?? ''));
        $newPasswordConfirm = trim((string) ($data['new_password_confirm'] ?? ''));
        $confirmed = !empty($data['confirm']);

        if ($targetUserId <= 0) {
            json_response(['ok' => false, 'message' => '成员不存在。'], 422);
        }
        if ($targetUserId === (int) $user['id']) {
            json_response(['ok' => false, 'message' => '请在账号安全里修改自己的密码。'], 422);
        }
        if (!$confirmed) {
            json_response(['ok' => false, 'message' => '请先二次确认重置操作。'], 422);
        }
        if ($newPassword === '' || $newPasswordConfirm === '') {
            json_response(['ok' => false, 'message' => '请填写两遍新密码。'], 422);
        }
        if (strlen($newPassword) < 6) {
            json_response(['ok' => false, 'message' => '新密码至少 6 位。'], 422);
        }
        if (!hash_equals($newPassword, $newPasswordConfirm)) {
            json_response(['ok' => false, 'message' => '两次输入的新密码不一致。'], 422);
        }

        $pdo = db();
        $stmt = $pdo->prepare('SELECT u.* FROM user_groups ug INNER JOIN users u ON u.id = ug.user_id WHERE ug.user_id = ? AND ug.group_name = ? LIMIT 1');
        $stmt->execute([$targetUserId, (string) $group['group_name']]);
        $targetUser = $stmt->fetch();
        if (!$targetUser) {
            json_response(['ok' => false, 'message' => '成员不在当前家庭组。'], 404);
        }

        $stmt = $pdo->prepare('SELECT COUNT(*) FROM user_groups WHERE user_id = ?');
        $stmt->execute([$targetUserId]);
        if ((int) $stmt->fetchColumn() !== 1) {
            json_response(['ok' => false, 'message' => '该成员属于多个家庭组，请前往工单系统申请重置密码。'], 409);
        }

        $stmt = $pdo->prepare('UPDATE users SET password_hash = ? WHERE id = ?');
        $stmt->execute([password_hash($newPassword, PASSWORD_DEFAULT), $targetUserId]);
        clear_failed_login($pdo, $targetUserId);

        record_user_log((int) $user['id'], (string) $group['group_name'], 'member_password_reset', '家庭组管理员重置成员密码', [
            'target_user_id' => $targetUserId,
        ]);
        record_user_log($targetUserId, (string) $group['group_name'], 'password_reset_by_group_owner', '家庭组管理员重置了账号密码');

        $freshUser = current_user() ?: $user;
        json_response(['ok' => true, 'user' => public_user_payload($freshUser)]);
    }

    if ($action === 'rename_group') {
        $groupId = (int) ($data['group_id'] ?? 0);
        $groupName = trim((string) ($data['group_name'] ?? ''));
        if ($groupId <= 0 || $groupName === '') {
            json_response(['ok' => false, 'message' => '家庭组信息不完整。'], 422);
        }

        $pdo = db();
        $stmt = $pdo->prepare('SELECT * FROM family_groups WHERE id = ? LIMIT 1');
        $stmt->execute([$groupId]);
        $group = $stmt->fetch();
        if (!$group || (int) ($group['owner_user_id'] ?? 0) !== (int) $user['id']) {
            json_response(['ok' => false, 'message' => '只有家庭组首个用户可以管理。'], 403);
        }

        $oldGroupName = (string) $group['group_name'];

        $pdo->beginTransaction();
        $stmt = $pdo->prepare('UPDATE family_groups SET display_name = ? WHERE id = ?');
        $stmt->execute([$groupName, $groupId]);
        $pdo->commit();
        latest_locations_cache_forget_all();

        $freshUser = current_user() ?: $user;
        json_response(['ok' => true, 'user' => public_user_payload($freshUser)]);
    }

    json_response(['ok' => false, 'message' => 'Unknown action.'], 400);
} catch (Throwable $th) {
    if (isset($pdo) && $pdo instanceof PDO && $pdo->inTransaction()) {
        $pdo->rollBack();
    }

    json_response(['ok' => false, 'message' => api_error_message($th)], 500);
}
