<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();

$root = dirname(__DIR__);
$files = [
    $root . DIRECTORY_SEPARATOR . 'index.php',
    $root . DIRECTORY_SEPARATOR . 'service-worker.js',
    $root . DIRECTORY_SEPARATOR . 'private' . DIRECTORY_SEPARATOR . 'config.php',
    $root . DIRECTORY_SEPARATOR . 'api' . DIRECTORY_SEPARATOR . 'legal_documents.php',
    $root . DIRECTORY_SEPARATOR . 'api' . DIRECTORY_SEPARATOR . 'invite_check.php',
];
$patterns = [
    $root . DIRECTORY_SEPARATOR . 'assets' . DIRECTORY_SEPARATOR . '*.js',
    $root . DIRECTORY_SEPARATOR . 'assets' . DIRECTORY_SEPARATOR . '*.css',
    $root . DIRECTORY_SEPARATOR . 'assets' . DIRECTORY_SEPARATOR . '*.webmanifest',
];

foreach ($patterns as $pattern) {
    foreach (glob($pattern) ?: [] as $file) {
        $files[] = $file;
    }
}

$files = array_values(array_unique(array_filter($files, 'is_file')));
sort($files);

$hash = hash_init('sha256');
$latestMtime = 0;

foreach ($files as $file) {
    clearstatcache(false, $file);
    $mtime = (int) filemtime($file);
    $size = (int) filesize($file);
    $latestMtime = max($latestMtime, $mtime);
    hash_update($hash, str_replace($root, '', $file) . '|' . $mtime . '|' . $size . "\n");
}

json_response([
    'ok' => true,
    'version' => substr(hash_final($hash), 0, 16),
    'updated_at' => date(DATE_ATOM, $latestMtime > 0 ? $latestMtime : time()),
]);
