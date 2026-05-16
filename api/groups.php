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
        if (!preg_match('/^[0-9a-z]{6}$/', $groupCode)) {
            json_response(['ok' => false, 'message' => '组号格式不正确。'], 422);
        }

        $pdo = db();
        $stmt = $pdo->prepare('SELECT * FROM family_groups WHERE group_code = ? LIMIT 1');
        $stmt->execute([$groupCode]);
        $group = $stmt->fetch();
        if (!$group) {
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
