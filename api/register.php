<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    json_response(['ok' => false, 'message' => 'Method not allowed.'], 405);
}

try {
    $data = request_data();
    $username = input_string('username', 64);
    $password = input_string('password', 255);
    $displayName = input_string('display_name', 100);
    $inviteCode = trim((string) ($data['invite_code'] ?? ''));
    $groupName = trim((string) ($data['group_name'] ?? ''));
    $groupCode = strtolower(trim((string) ($data['group_code'] ?? '')));
    $termsAccepted = input_bool('terms_accepted');
    $crossBorderAccepted = input_bool('cross_border_transfer_accepted');
    $turnstileToken = input_string('turnstile_token', 4096);

    verify_register_turnstile_token($turnstileToken);

    if (!preg_match('/^(?=.*[A-Za-z])(?=.*\d)[A-Za-z\d_]{6,64}$/', $username)) {
        json_response(['ok' => false, 'message' => '用户名需至少 6 位，并同时包含英文和数字。'], 422);
    }
    if (strlen($password) < 6) {
        json_response(['ok' => false, 'message' => '密码至少 6 位。'], 422);
    }
    if ($inviteCode === '') {
        json_response(['ok' => false, 'message' => '请输入邀请码。'], 422);
    }
    if (!$termsAccepted || !$crossBorderAccepted) {
        json_response(['ok' => false, 'message' => '请先同意全部协议。'], 403);
    }
    if (hash_equals(ADMIN_USERNAME, $username)) {
        json_response(['ok' => false, 'message' => '用户名不可用。'], 409);
    }

    $pdo = db();
    $stmt = $pdo->prepare('SELECT id FROM users WHERE username = ? LIMIT 1');
    $stmt->execute([$username]);
    if ($stmt->fetch()) {
        json_response(['ok' => false, 'message' => '用户名已存在。'], 409);
    }

    $stmt = $pdo->prepare('SELECT * FROM invite_codes WHERE code = ? AND is_active = 1 LIMIT 1');
    $stmt->execute([$inviteCode]);
    $invite = $stmt->fetch();
    if (!$invite || (int) $invite['used_count'] >= (int) $invite['max_uses']) {
        json_response(['ok' => false, 'message' => '邀请码无效或次数已用完。'], 403);
    }

    $role = 'guardian';
    $pdo->beginTransaction();

    if ((string) $invite['invite_type'] === 'group_create') {
        $assignedGroupName = trim((string) ($invite['assigned_group_name'] ?? ''));
        if ($assignedGroupName === '') {
            if ($groupName === '') {
                json_response(['ok' => false, 'message' => '请填写要创建的家庭组名称。'], 422);
            }
            $assignedGroupName = $groupName;
            $stmt = $pdo->prepare('SELECT id FROM family_groups WHERE group_name = ? LIMIT 1');
            $stmt->execute([$assignedGroupName]);
            if ($stmt->fetch()) {
                json_response(['ok' => false, 'message' => '家庭组名称已存在，请更换。'], 409);
            }
        }
        ensure_family_group_record($pdo, $assignedGroupName);
    } else {
        if (!preg_match('/^[0-9a-z]{6}$/', $groupCode)) {
            json_response(['ok' => false, 'message' => '请填写 6 位家庭组号。'], 422);
        }
        $stmt = $pdo->prepare('SELECT group_name FROM family_groups WHERE group_code = ? LIMIT 1');
        $stmt->execute([$groupCode]);
        $group = $stmt->fetch();
        if (!$group) {
            json_response(['ok' => false, 'message' => '家庭组号不存在。'], 404);
        }
        $assignedGroupName = (string) $group['group_name'];
    }

    $stmt = $pdo->prepare("
        INSERT INTO users
            (username, password_hash, display_name, group_name, role, terms_accepted_at, user_agreement_accepted_at, privacy_policy_accepted_at, cross_border_transfer_accepted_at)
        VALUES
            (?, ?, ?, ?, ?, NOW(), NOW(), NOW(), NOW())
    ");
    $stmt->execute([
        $username,
        password_hash($password, PASSWORD_DEFAULT),
        $displayName !== '' ? $displayName : $username,
        $assignedGroupName,
        $role,
    ]);
    $userId = (int) $pdo->lastInsertId();

    if ((string) $invite['invite_type'] === 'group_create' && trim((string) ($invite['assigned_group_name'] ?? '')) === '') {
        ensure_family_group_record($pdo, $assignedGroupName, $userId);
        $stmt = $pdo->prepare('UPDATE invite_codes SET assigned_group_name = ? WHERE id = ?');
        $stmt->execute([$assignedGroupName, (int) $invite['id']]);
    }

    $stmt = $pdo->prepare('INSERT INTO user_groups (user_id, group_name, role) VALUES (?, ?, ?)');
    $stmt->execute([$userId, $assignedGroupName, $role]);

    $stmt = $pdo->prepare('UPDATE invite_codes SET used_count = used_count + 1 WHERE id = ?');
    $stmt->execute([(int) $invite['id']]);

    $pdo->commit();

    session_regenerate_id(true);
    $_SESSION['user_id'] = $userId;

    $stmt = $pdo->prepare('SELECT * FROM users WHERE id = ? LIMIT 1');
    $stmt->execute([$userId]);
    $user = $stmt->fetch();

    json_response(['ok' => true, 'user' => public_user_payload($user)]);
} catch (Throwable $th) {
    if (isset($pdo) && $pdo instanceof PDO && $pdo->inTransaction()) {
        $pdo->rollBack();
    }

    json_response(['ok' => false, 'message' => api_error_message($th)], 500);
}

function verify_register_turnstile_token(string $token): void
{
    if (!defined('CF_TURNSTILE_SECRET_KEY') || trim((string) CF_TURNSTILE_SECRET_KEY) === '') {
        return;
    }

    if ($token === '') {
        json_response(['ok' => false, 'message' => '请先完成人机验证。'], 403);
    }

    $payload = http_build_query([
        'secret' => CF_TURNSTILE_SECRET_KEY,
        'response' => $token,
        'remoteip' => $_SERVER['REMOTE_ADDR'] ?? '',
    ]);
    $context = stream_context_create([
        'http' => [
            'method' => 'POST',
            'header' => "Content-Type: application/x-www-form-urlencoded\r\n",
            'content' => $payload,
            'timeout' => 8,
        ],
    ]);

    $response = @file_get_contents('https://challenges.cloudflare.com/turnstile/v0/siteverify', false, $context);
    $decoded = is_string($response) ? json_decode($response, true) : null;
    if (!is_array($decoded) || empty($decoded['success'])) {
        json_response(['ok' => false, 'message' => '人机验证失败，请重试。'], 403);
    }
}
