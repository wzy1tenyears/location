<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();

try {
    $data = request_data();
    $code = trim((string) ($_GET['code'] ?? ($data['code'] ?? '')));

    if (!preg_match('/^[0-9a-zA-Z]{1,255}$/', $code)) {
        json_response(['ok' => false, 'message' => '邀请码格式不正确。'], 422);
    }

    $stmt = db()->prepare('SELECT * FROM invite_codes WHERE code = ? AND is_active = 1 LIMIT 1');
    $stmt->execute([$code]);
    $invite = $stmt->fetch();

    if (!$invite || (int) $invite['used_count'] >= (int) $invite['max_uses']) {
        json_response(['ok' => false, 'message' => '邀请码无效或次数已用完。'], 403);
    }

    $requiresGroupName = false;
    $requiresGroupCode = false;
    if ((string) $invite['invite_type'] === 'group_create') {
        $requiresGroupName = trim((string) ($invite['assigned_group_name'] ?? '')) === '';
    } else {
        $requiresGroupCode = true;
    }

    json_response([
        'ok' => true,
        'requires_group_name' => $requiresGroupName,
        'requires_group_code' => $requiresGroupCode,
        'message' => '邀请码可用。',
    ]);
} catch (Throwable $th) {
    json_response(['ok' => false, 'message' => api_error_message($th)], 500);
}
