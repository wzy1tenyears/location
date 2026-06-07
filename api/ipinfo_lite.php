<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    json_response(['ok' => false, 'message' => 'Method not allowed.'], 405);
}

try {
    require_user();

    $ip = input_string('ip', 80);
    if (!filter_var($ip, FILTER_VALIDATE_IP)) {
        json_response(['ok' => false, 'message' => 'IP 地址不正确。'], 422);
    }

    if (!defined('IPINFO_LITE_TOKEN') || IPINFO_LITE_TOKEN === '') {
        json_response(['ok' => false, 'message' => 'IPinfo Lite 未配置。'], 500);
    }

    $url = 'https://api.ipinfo.io/lite/' . rawurlencode($ip)
        . '?token=' . rawurlencode(IPINFO_LITE_TOKEN);
    $raw = http_get_json($url, 4);
    $data = json_decode($raw, true);
    if (!is_array($data)) {
        json_response(['ok' => false, 'message' => 'IPinfo Lite 返回格式不正确。'], 502);
    }

    json_response([
        'ok' => true,
        'ip' => $ip,
        'country' => (string) ($data['country'] ?? $data['country_code'] ?? ''),
        'region' => (string) ($data['region'] ?? ''),
        'city' => (string) ($data['city'] ?? ''),
        'latitude' => $data['latitude'] ?? null,
        'longitude' => $data['longitude'] ?? null,
        'provider' => 'IPinfo Lite',
    ]);
} catch (Throwable $th) {
    json_response(['ok' => false, 'message' => api_error_message($th)], 500);
}

function http_get_json(string $url, int $timeoutSeconds): string
{
    if (function_exists('curl_init')) {
        $curl = curl_init($url);
        if ($curl === false) {
            throw new RuntimeException('无法初始化请求。');
        }

        curl_setopt_array($curl, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT => $timeoutSeconds,
            CURLOPT_CONNECTTIMEOUT => $timeoutSeconds,
            CURLOPT_HTTPHEADER => [
                'Accept: application/json',
                'User-Agent: loc-app-server',
            ],
        ]);

        $raw = curl_exec($curl);
        $status = (int) curl_getinfo($curl, CURLINFO_RESPONSE_CODE);
        $error = curl_error($curl);
        curl_close($curl);

        if ($raw === false || $status < 200 || $status >= 300) {
            throw new RuntimeException($error !== '' ? $error : 'IPinfo Lite 请求失败。');
        }

        return (string) $raw;
    }

    $context = stream_context_create([
        'http' => [
            'method' => 'GET',
            'timeout' => $timeoutSeconds,
            'header' => "Accept: application/json\r\nUser-Agent: loc-app-server\r\n",
        ],
    ]);

    $raw = @file_get_contents($url, false, $context);
    if ($raw === false) {
        throw new RuntimeException('IPinfo Lite 请求失败。');
    }

    return $raw;
}
