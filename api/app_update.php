<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();

$currentVersionCode = (int) ($_GET['version_code'] ?? 0);
$apkPath = '/' . ltrim(ANDROID_APK_FILENAME, '/');
$apkFile = dirname(__DIR__) . DIRECTORY_SEPARATOR . ANDROID_APK_FILENAME;
$scheme = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') ? 'https' : 'http';
$host = (string) ($_SERVER['HTTP_HOST'] ?? '');
$apkUrl = ($host === '' ? $apkPath : $scheme . '://' . $host . $apkPath)
    . '?v=' . rawurlencode((string) ANDROID_VERSION_CODE);
$apkExists = is_file($apkFile);

json_response([
    'ok' => true,
    'latest_version_code' => ANDROID_VERSION_CODE,
    'latest_version_name' => ANDROID_VERSION_NAME,
    'current_version_code' => $currentVersionCode,
    'update_required' => $apkExists && $currentVersionCode > 0 && ANDROID_VERSION_CODE > $currentVersionCode,
    'force_update' => ANDROID_FORCE_UPDATE,
    'apk_url' => $apkUrl,
    'apk_exists' => $apkExists,
]);
