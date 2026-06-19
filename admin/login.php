<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_admin_path();

if (is_admin_logged_in()) {
    redirect('/' . admin_url_path());
}

$error = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    require_csrf();

    $username = post_string('username', 64);
    $password = post_string('password', 255);

    if ($username === '' || $password === '') {
        $error = '请输入后台账号和密码。';
    } elseif (!hash_equals(ADMIN_USERNAME, $username)) {
        $error = '账号或密码错误。';
    } else {
        $pdo = db();
        $adminIp = client_ip_address();
        if (admin_login_locked($pdo, $adminIp)) {
            $error = '管理员登录尝试过多，请 30 分钟后再试。';
        } elseif (!web_admin_password_matches($password)) {
            if (record_failed_admin_login($pdo, $adminIp)) {
                $error = '管理员登录尝试过多，请 30 分钟后再试。';
            } else {
                $error = '账号或密码错误。';
            }
        } else {
            clear_failed_admin_login($pdo, $adminIp);
            session_regenerate_id(true);
            unset($_SESSION['user_id']);
            $_SESSION['admin_logged_in'] = true;
            record_user_log(null, '', 'admin_web_login', '管理员网页登录');
            redirect('/' . admin_url_path());
        }
    }
}

function web_admin_password_matches(string $password): bool
{
    if (defined('ADMIN_PASSWORD_HASH') && trim((string) ADMIN_PASSWORD_HASH) !== '') {
        return password_verify($password, (string) ADMIN_PASSWORD_HASH);
    }

    return hash_equals((string) ADMIN_PASSWORD, $password);
}
?>
<!doctype html>
<html lang="zh-CN">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>后台登录</title>
    <link rel="stylesheet" href="/<?= e(admin_url_path()) ?>assets/admin.css?v=<?= (int) filemtime(__DIR__ . '/assets/admin.css') ?>">
</head>
<body class="login-page">
    <main class="login-panel">
        <h1>后台登录</h1>
        <?php if ($error !== ''): ?>
            <div class="alert error"><?= e($error) ?></div>
        <?php endif; ?>
        <form method="post" class="login-form">
            <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
            <label>
                <span>管理员账号</span>
                <input name="username" autocomplete="username" required autofocus>
            </label>
            <label>
                <span>管理员密码</span>
                <input name="password" type="password" autocomplete="current-password" required>
            </label>
            <button type="submit">登录后台</button>
        </form>
    </main>
</body>
</html>
