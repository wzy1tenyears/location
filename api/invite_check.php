<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();

rate_limit_or_fail('invite_check', 30, 600);

request_data();
json_response([
    'ok' => true,
    'requires_group_name' => true,
    'requires_group_code' => true,
    'message' => '邀请码将在提交注册时验证。',
]);
