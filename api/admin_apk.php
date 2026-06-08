<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();

if (!is_admin_logged_in()) {
    json_response([
        'ok' => false,
        'message' => '请先登录后台。',
    ], 401);
}

$apkFile = dirname(__DIR__) . DIRECTORY_SEPARATOR . 'private' . DIRECTORY_SEPARATOR . ANDROID_ADMIN_APK_FILENAME;
if (!is_file($apkFile)) {
    json_response([
        'ok' => false,
        'message' => '后台更新包不存在。',
    ], 404);
}

$filename = basename(ANDROID_ADMIN_APK_FILENAME);
header('Content-Type: application/vnd.android.package-archive');
header('Content-Disposition: attachment; filename="' . $filename . '"');
header('Content-Length: ' . (string) filesize($apkFile));
header('Cache-Control: private, no-store, max-age=0');
header('X-Content-Type-Options: nosniff');
readfile($apkFile);
