<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    json_response(['ok' => false, 'message' => 'Method not allowed.'], 405);
}

try {
    $user = require_user();
    $groupName = selected_group_name_from_request();
    $membership = user_membership_for_group($user, $groupName);
    $effectiveGroupName = $membership ? (string) $membership['group_name'] : '';

    touch_user_presence((int) $user['id'], $effectiveGroupName);
    record_user_log((int) $user['id'], $effectiveGroupName, 'online', '用户心跳');

    json_response([
        'ok' => true,
        'server_time' => date('Y-m-d H:i:s'),
    ]);
} catch (Throwable $th) {
    json_response(['ok' => false, 'message' => api_error_message($th)], 500);
}
