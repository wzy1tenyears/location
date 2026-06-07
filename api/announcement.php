<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();

try {
    $user = current_user();
    if ($user) {
        require_terms_accepted($user);
    }

    $stmt = db()->query("
        SELECT id, title, body, version, updated_at
        FROM announcements
        WHERE is_active = 1 AND body <> ''
        ORDER BY updated_at DESC, id DESC
        LIMIT 1
    ");
    $announcement = $stmt->fetch();

    json_response([
        'ok' => true,
        'announcement' => $announcement ? [
            'id' => (int) $announcement['id'],
            'title' => (string) $announcement['title'],
            'body' => (string) $announcement['body'],
            'version' => (int) $announcement['version'],
            'updated_at' => format_datetime((string) $announcement['updated_at']),
        ] : null,
    ]);
} catch (Throwable $th) {
    json_response(['ok' => false, 'message' => api_error_message($th)], 500);
}
