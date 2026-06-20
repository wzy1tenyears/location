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

$currentVersionCode = (int) ($_GET['version_code'] ?? 0);
$apkFile = dirname(__DIR__) . DIRECTORY_SEPARATOR . 'private' . DIRECTORY_SEPARATOR . ANDROID_ADMIN_APK_FILENAME;
$apkPath = '/api/admin_apk.php';
$apkExpires = time() + 600;
$apkTokenPayload = 'admin-apk|' . ANDROID_ADMIN_VERSION_CODE . '|' . $apkExpires;
$apkToken = hash_hmac('sha256', $apkTokenPayload, DB_PASS);
$apkUrl = public_url($apkPath)
    . '?v=' . rawurlencode((string) ANDROID_ADMIN_VERSION_CODE)
    . '&expires=' . rawurlencode((string) $apkExpires)
    . '&token=' . rawurlencode($apkToken);
$apkExists = is_file($apkFile);

json_response([
    'ok' => true,
    'latest_version_code' => ANDROID_ADMIN_VERSION_CODE,
    'latest_version_name' => ANDROID_ADMIN_VERSION_NAME,
    'current_version_code' => $currentVersionCode,
    'update_required' => $apkExists && $currentVersionCode > 0 && ANDROID_ADMIN_VERSION_CODE > $currentVersionCode,
    'force_update' => ANDROID_ADMIN_FORCE_UPDATE,
    'apk_url' => $apkUrl,
    'apk_exists' => $apkExists,
]);
