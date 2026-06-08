<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    json_response(['ok' => false, 'message' => 'Method not allowed.'], 405);
}

try {
    $user = require_user();
    $data = request_data();
    $report = is_array($data['report'] ?? null) ? $data['report'] : [];
    enforce_device_report_policy($user, $report);
    $report['report_kind'] = 'device_integrity';
    $report['forced_device_report'] = true;

    $reportJson = json_encode($report, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    if (!is_string($reportJson) || strlen($reportJson) > 200000) {
        json_response(['ok' => false, 'message' => '设备数据过大。'], 422);
    }

    $stmt = db()->prepare('SELECT id FROM environment_reports WHERE user_id = ? AND created_at >= CURDATE() AND report_json LIKE ? LIMIT 1');
    $stmt->execute([(int) $user['id'], '%"report_kind":"device_integrity"%']);
    if ($stmt->fetch()) {
        json_response(['ok' => true, 'skipped' => true]);
    }

    $stmt = db()->prepare('INSERT INTO environment_reports (user_id, report_json) VALUES (?, ?)');
    $stmt->execute([(int) $user['id'], $reportJson]);

    json_response(['ok' => true]);
} catch (Throwable $th) {
    json_response(['ok' => false, 'message' => api_error_message($th)], 500);
}
