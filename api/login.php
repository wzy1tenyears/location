<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    json_response(['ok' => false, 'message' => 'Method not allowed.'], 405);
}

rate_limit_or_fail('login', 30, 300);

try {
    $username = input_string('username', 64);
    $password = input_string('password', 255);
    $termsAccepted = input_bool('terms_accepted');
    $crossBorderAccepted = input_bool('cross_border_transfer_accepted');
    $turnstileToken = input_string('turnstile_token', 4096);
    $browserFingerprint = input_browser_fingerprint();

    if ($username === '' || $password === '') {
        json_response(['ok' => false, 'message' => '请输入账号和密码。'], 422);
    }

    verify_turnstile_token($turnstileToken);

    if (hash_equals(ADMIN_USERNAME, $username)) {
        $pdo = db();
        $adminIp = client_ip_address();
        if (admin_login_locked($pdo, $adminIp)) {
            json_response(['ok' => false, 'message' => '管理员登录尝试过多，请 30 分钟后再试。'], 423);
        }

        if (!admin_password_matches($password)) {
            if (record_failed_admin_login($pdo, $adminIp)) {
                json_response(['ok' => false, 'message' => '管理员登录尝试过多，请 30 分钟后再试。'], 423);
            }
            json_response(['ok' => false, 'message' => '账号或密码错误。'], 401);
        }

        clear_failed_admin_login($pdo, $adminIp);
        session_regenerate_id(true);
        unset($_SESSION['user_id']);
        $_SESSION['admin_logged_in'] = true;
        record_user_log(null, '', 'admin_login', '管理员登录');
        json_response(['ok' => true, 'redirect' => admin_url_path()]);
    }

    $pdo = db();
    $stmt = $pdo->prepare('SELECT * FROM users WHERE username = ?');
    $stmt->execute([$username]);
    $user = $stmt->fetch();

    if (!$user) {
        json_response(['ok' => false, 'message' => '账号或密码错误。'], 401);
    }

    unlock_if_expired($pdo, $user);

    if (is_login_locked($user)) {
        json_response(['ok' => false, 'message' => '账号已锁定 30 分钟，请稍后再试。'], 423);
    }

    if (!password_verify($password, (string) $user['password_hash'])) {
        record_failed_login($pdo, $user);

        $failedCount = (int) ($user['failed_login_count'] ?? 0) + 1;
        if ($failedCount >= MAX_LOGIN_FAILURES) {
            json_response(['ok' => false, 'message' => '账号已锁定 30 分钟，请稍后再试。'], 423);
        }

        json_response(['ok' => false, 'message' => '账号或密码错误。'], 401);
    }

    if ((int) ($user['is_active'] ?? 1) !== 1) {
        $reason = trim((string) ($user['disabled_reason'] ?? ''));
        json_response([
            'ok' => false,
            'message' => $reason === '' ? '账号已停用，请联系管理员。' : '账号已停用：' . $reason,
        ], 403);
    }

    if (!$termsAccepted) {
        json_response(['ok' => false, 'message' => '请先同意用户协议和隐私条约。'], 403);
    }
    if (!$crossBorderAccepted) {
        json_response(['ok' => false, 'message' => '请先同意用户数据跨境加密传输协议。'], 403);
    }

    $deviceFingerprint = request_device_fingerprint();
    bind_user_device($pdo, $user, $deviceFingerprint, $browserFingerprint);

    session_regenerate_id(true);
    $_SESSION['user_id'] = (int) $user['id'];
    clear_failed_login($pdo, (int) $user['id']);
    $acceptedAt = date('Y-m-d H:i:s');
    $stmt = $pdo->prepare('
        UPDATE users
        SET
            terms_accepted_at = ?,
            user_agreement_accepted_at = ?,
            privacy_policy_accepted_at = ?,
            cross_border_transfer_accepted_at = ?
        WHERE id = ?
    ');
    $stmt->execute([$acceptedAt, $acceptedAt, $acceptedAt, $acceptedAt, (int) $user['id']]);
    $user['terms_accepted_at'] = $acceptedAt;
    $user['user_agreement_accepted_at'] = $acceptedAt;
    $user['privacy_policy_accepted_at'] = $acceptedAt;
    $user['cross_border_transfer_accepted_at'] = $acceptedAt;
    touch_user_presence((int) $user['id'], (string) ($user['group_name'] ?? ''));
    record_user_log((int) $user['id'], (string) ($user['group_name'] ?? ''), 'online', '用户登录');

    json_response([
        'ok' => true,
        'user' => public_user_payload($user),
    ]);
} catch (Throwable $th) {
    json_response(['ok' => false, 'message' => api_error_message($th)], 500);
}

function verify_turnstile_token(string $token): void
{
    if (consume_app_challenge_token($token, 'login')) {
        return;
    }

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

function admin_password_matches(string $password): bool
{
    if (defined('ADMIN_PASSWORD_HASH') && trim((string) ADMIN_PASSWORD_HASH) !== '') {
        return password_verify($password, (string) ADMIN_PASSWORD_HASH);
    }

    return hash_equals((string) ADMIN_PASSWORD, $password);
}
