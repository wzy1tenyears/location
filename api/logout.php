<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();

if (!empty($_SESSION['user_id'])) {
    $userId = (int) $_SESSION['user_id'];
    record_user_log($userId, '', 'offline', '用户退出登录');
}

unset($_SESSION['user_id']);
json_response(['ok' => true]);
