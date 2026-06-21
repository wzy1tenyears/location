<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

if ($method === 'POST' && stripos((string) ($_SERVER['CONTENT_TYPE'] ?? ''), 'application/json') !== false) {
    require_app_user_agent();
    rate_limit_or_fail('app_challenge_start', 20, 300);

    $purpose = input_string('purpose', 20);
    if (!in_array($purpose, ['login', 'register'], true)) {
        json_response(['ok' => false, 'message' => 'Invalid challenge purpose.'], 422);
    }
    $turnstileConfig = app_challenge_config();
    if (!$turnstileConfig['secret_configured']) {
        json_response([
            'ok' => true,
            'challenge_required' => false,
            'app_challenge_token' => '',
        ]);
    }
    if (!$turnstileConfig['site_configured']) {
        json_response(['ok' => false, 'message' => 'Turnstile Site Key is not configured for App challenge.'], 500);
    }

    $deviceFingerprint = request_device_fingerprint();
    $challengeId = bin2hex(random_bytes(16));
    $secret = bin2hex(random_bytes(32));
    $secretHash = hash('sha256', $secret);
    $expiresAt = date('Y-m-d H:i:s', time() + 300);

    $stmt = db()->prepare('
        INSERT INTO app_challenges (id, secret_hash, device_fingerprint, purpose, expires_at)
        VALUES (?, ?, ?, ?, ?)
    ');
    $stmt->execute([$challengeId, $secretHash, $deviceFingerprint, $purpose, $expiresAt]);

    json_response([
        'ok' => true,
        'challenge_required' => true,
        'challenge_id' => $challengeId,
        'challenge_secret' => $secret,
        'challenge_url' => app_challenge_public_url($challengeId),
        'expires_in' => 300,
    ]);
}

if ($method === 'GET' && isset($_GET['secret'])) {
    require_app_user_agent();
    rate_limit_or_fail('app_challenge_poll', 60, 300);

    $challengeId = trim((string) ($_GET['id'] ?? ''));
    $secret = trim((string) ($_GET['secret'] ?? ''));
    $deviceFingerprint = request_device_fingerprint();
    if (!preg_match('/^[a-f0-9]{32}$/i', $challengeId) || !preg_match('/^[a-f0-9]{64}$/i', $secret)) {
        json_response(['ok' => false, 'message' => 'Invalid challenge token.'], 422);
    }

    $stmt = db()->prepare('
        SELECT purpose, secret_hash, verified_at, consumed_at, expires_at
        FROM app_challenges
        WHERE id = ? AND device_fingerprint = ?
        LIMIT 1
    ');
    $stmt->execute([$challengeId, $deviceFingerprint]);
    $challenge = $stmt->fetch();
    if (!$challenge || !hash_equals((string) $challenge['secret_hash'], hash('sha256', $secret))) {
        json_response(['ok' => false, 'message' => 'Challenge not found.'], 404);
    }
    if (strtotime((string) $challenge['expires_at']) <= time()) {
        json_response(['ok' => false, 'message' => 'Challenge expired.'], 410);
    }
    if (!empty($challenge['consumed_at'])) {
        json_response(['ok' => false, 'message' => 'Challenge already used.'], 409);
    }

    $verified = !empty($challenge['verified_at']);
    json_response([
        'ok' => true,
        'verified' => $verified,
        'app_challenge_token' => $verified ? 'app:' . $challengeId . ':' . $secret : '',
        'purpose' => (string) $challenge['purpose'],
    ]);
}

if ($method === 'POST') {
    rate_limit_or_fail('app_challenge_verify', 20, 300);

    $challengeId = trim((string) ($_POST['challenge_id'] ?? ''));
    $turnstileToken = trim((string) ($_POST['cf-turnstile-response'] ?? ''));
    $message = verify_browser_challenge($challengeId, $turnstileToken);
    render_challenge_page($challengeId, $message);
}

$challengeId = trim((string) ($_GET['id'] ?? ''));
render_challenge_page($challengeId, '');

function verify_browser_challenge(string $challengeId, string $turnstileToken): string
{
    if (!preg_match('/^[a-f0-9]{32}$/i', $challengeId)) {
        return '质询编号无效，请回到 App 重新发起。';
    }
    if ($turnstileToken === '') {
        return '请先完成 Cloudflare 质询。';
    }

    $deviceFingerprint = browser_challenge_device_fingerprint();
    if ($deviceFingerprint === '') {
        return '设备 Cookie 不一致，请回到 App 重新发起。';
    }

    $pdo = db();
    $stmt = $pdo->prepare('SELECT id, expires_at, consumed_at FROM app_challenges WHERE id = ? AND device_fingerprint = ? LIMIT 1');
    $stmt->execute([$challengeId, $deviceFingerprint]);
    $challenge = $stmt->fetch();
    if (!$challenge) {
        return '质询不存在，请回到 App 重新发起。';
    }
    if (!empty($challenge['consumed_at'])) {
        return '质询已使用，请回到 App 重新发起。';
    }
    if (strtotime((string) $challenge['expires_at']) <= time()) {
        return '质询已过期，请回到 App 重新发起。';
    }

    if (!verify_turnstile_site_token($turnstileToken)) {
        return 'Cloudflare 验证失败，请重试。';
    }

    $stmt = $pdo->prepare('UPDATE app_challenges SET verified_at = NOW() WHERE id = ? AND device_fingerprint = ? AND verified_at IS NULL');
    $stmt->execute([$challengeId, $deviceFingerprint]);

    return '验证已完成，请回到 App 继续登录。';
}

function browser_challenge_device_fingerprint(): string
{
    $cookieName = defined('APP_DEVICE_COOKIE_NAME') ? APP_DEVICE_COOKIE_NAME : 'loc_device';
    $deviceCookie = (string) ($_COOKIE[$cookieName] ?? '');
    return preg_match('/^[a-f0-9]{64}$/i', $deviceCookie) ? strtolower($deviceCookie) : '';
}

function verify_turnstile_site_token(string $token): bool
{
    if (!defined('CF_TURNSTILE_SECRET_KEY') || trim((string) CF_TURNSTILE_SECRET_KEY) === '') {
        return true;
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

    return is_array($decoded) && !empty($decoded['success']);
}

function render_challenge_page(string $challengeId, string $message): never
{
    if (!preg_match('/^[a-f0-9]{32}$/i', $challengeId)) {
        http_response_code(400);
        echo 'Invalid challenge.';
        exit;
    }

    $siteKey = defined('CF_TURNSTILE_SITE_KEY') ? trim((string) CF_TURNSTILE_SITE_KEY) : '';
    $safeId = htmlspecialchars($challengeId, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
    $safeMessage = htmlspecialchars($message, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
    $safeSiteKey = htmlspecialchars($siteKey, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
    $verified = app_challenge_is_verified($challengeId);

    header('Content-Type: text/html; charset=utf-8');
    echo <<<HTML
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>CF Challenge</title>
  <script src="https://challenges.cloudflare.com/turnstile/v0/api.js" async defer></script>
  <style>
    html, body { margin: 0; width: 100%; min-height: 100%; background: transparent; overflow: hidden; }
    body { display: grid; place-items: center; font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
    form { margin: 0; padding: 0; display: grid; place-items: center; }
    .message { display: none; }
  </style>
  <script>
    function notifyNativeChallengeComplete() {
      if (window.LocChallenge && window.LocChallenge.complete) {
        window.LocChallenge.complete();
      }
    }
  </script>
</head>
<body data-message="{$safeMessage}">
HTML;
    if ($verified) {
        echo '<script>notifyNativeChallengeComplete();</script></body></html>';
        exit;
    }
    if ($safeSiteKey === '') {
        echo '<div class="message">Turnstile Site Key is not configured.</div></body></html>';
        exit;
    }
    echo <<<HTML
  <form method="post" action="app_challenge.php">
    <input type="hidden" name="challenge_id" value="{$safeId}">
    <div class="cf-turnstile" data-sitekey="{$safeSiteKey}" data-callback="onTurnstileSuccess"></div>
  </form>
  <script>
    function onTurnstileSuccess() { document.forms[0].submit(); }
  </script>
</body>
</html>
HTML;
    exit;
}


function app_challenge_is_verified(string $challengeId): bool
{
    if (!preg_match('/^[a-f0-9]{32}$/i', $challengeId)) {
        return false;
    }
    $stmt = db()->prepare('SELECT verified_at FROM app_challenges WHERE id = ? LIMIT 1');
    $stmt->execute([$challengeId]);
    $challenge = $stmt->fetch();
    return is_array($challenge) && !empty($challenge['verified_at']);
}

function app_challenge_public_url(string $challengeId): string
{
    $path = '/api/app_challenge.php?id=' . rawurlencode($challengeId);

    return public_url($path);
}

function app_challenge_config(): array
{
    $siteKey = defined('CF_TURNSTILE_SITE_KEY') ? trim((string) CF_TURNSTILE_SITE_KEY) : '';
    $secretKey = defined('CF_TURNSTILE_SECRET_KEY') ? trim((string) CF_TURNSTILE_SECRET_KEY) : '';

    return [
        'site_configured' => $siteKey !== '',
        'secret_configured' => $secretKey !== '',
    ];
}
