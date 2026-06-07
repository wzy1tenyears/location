<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();

try {
    $user = current_user();

    if (!$user) {
        json_response(['ok' => true, 'user' => null]);
    }

    require_terms_accepted($user);

    json_response([
        'ok' => true,
        'user' => public_user_payload($user),
        'report_interval_seconds' => user_report_interval_seconds($user),
    ]);
} catch (Throwable $th) {
    json_response(['ok' => false, 'message' => api_error_message($th)], 500);
}
