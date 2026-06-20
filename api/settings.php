<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();

try {
    $user = require_user();
    $membership = require_user_membership($user, selected_group_name_from_request());
    if ($_SERVER['REQUEST_METHOD'] === 'POST') {
        $data = request_data();
        $action = trim((string) ($data['action'] ?? ''));

        if ($action === 'change_password') {
            $currentPassword = input_string('current_password', 255);
            $newPassword = input_string('new_password', 255);
            $newPasswordConfirm = input_string('new_password_confirm', 255);

            if ($currentPassword === '' || $newPassword === '' || $newPasswordConfirm === '') {
                json_response(['ok' => false, 'message' => '请填写完整密码信息。'], 422);
            }
            if (!password_verify($currentPassword, (string) $user['password_hash'])) {
                json_response(['ok' => false, 'message' => '当前密码不正确。'], 403);
            }
            if (strlen($newPassword) < 6) {
                json_response(['ok' => false, 'message' => '新密码至少 6 位。'], 422);
            }
            if (!hash_equals($newPassword, $newPasswordConfirm)) {
                json_response(['ok' => false, 'message' => '两次输入的新密码不一致。'], 422);
            }
            if (password_verify($newPassword, (string) $user['password_hash'])) {
                json_response(['ok' => false, 'message' => '新密码不能与当前密码相同。'], 422);
            }

            $stmt = db()->prepare('UPDATE users SET password_hash = ? WHERE id = ?');
            $stmt->execute([password_hash($newPassword, PASSWORD_DEFAULT), (int) $user['id']]);
            session_regenerate_id(true);
        } else {
            $environmentDataConsent = input_bool('environment_data_consent');
            $stmt = db()->prepare('UPDATE users SET environment_data_consent_at = ? WHERE id = ?');
            $stmt->execute([$environmentDataConsent ? date('Y-m-d H:i:s') : null, (int) $user['id']]);
            $user['environment_data_consent_at'] = $environmentDataConsent ? date('Y-m-d H:i:s') : null;
        }
    }

    json_response([
        'ok' => true,
        'user' => public_user_payload_for_group($user, $membership),
        'selected_group' => group_payload($membership),
        'report_interval_seconds' => user_report_interval_seconds($user),
        'server_time' => date('Y-m-d H:i:s'),
    ]);
} catch (Throwable $th) {
    json_response(['ok' => false, 'message' => api_error_message($th)], 500);
}
