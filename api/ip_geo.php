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
    $provider = strtolower(input_string('provider', 40));
    if (!filter_var($ip, FILTER_VALIDATE_IP)) {
        json_response(['ok' => false, 'message' => 'IP 地址不正确。'], 422);
    }

    $data = match ($provider) {
        'ip-api' => lookup_ip_api($ip),
        'ip2location' => lookup_ip2location($ip),
        'ipdata' => lookup_ipdata($ip),
        'ipregistry' => lookup_ipregistry($ip),
        default => null,
    };

    if (!$data) {
        json_response(['ok' => false, 'message' => 'IP 查询源未配置或无结果。'], 404);
    }

    json_response(['ok' => true, ...$data]);
} catch (Throwable $th) {
    json_response(['ok' => false, 'message' => api_error_message($th)], 500);
}

function lookup_ip_api(string $ip): ?array
{
    $url = 'http://ip-api.com/json/' . rawurlencode($ip)
        . '?lang=zh-CN&fields=status,message,country,regionName,city,lat,lon,query';
    $data = http_get_json_array($url, 4);
    if (($data['status'] ?? '') !== 'success') {
        return null;
    }

    return ip_geo_payload(
        $ip,
        'ip-api',
        $data['country'] ?? '',
        $data['regionName'] ?? '',
        $data['city'] ?? '',
        $data['lat'] ?? null,
        $data['lon'] ?? null
    );
}

function lookup_ip2location(string $ip): ?array
{
    if (!defined('IP2LOCATION_IO_KEY') || trim((string) IP2LOCATION_IO_KEY) === '') {
        return null;
    }

    $url = 'https://api.ip2location.io/?key=' . rawurlencode(IP2LOCATION_IO_KEY)
        . '&ip=' . rawurlencode($ip)
        . '&format=json';
    $data = http_get_json_array($url, 4);

    return ip_geo_payload(
        $ip,
        'IP2Location.io',
        $data['country_name'] ?? $data['country_code'] ?? '',
        $data['region_name'] ?? '',
        $data['city_name'] ?? '',
        $data['latitude'] ?? null,
        $data['longitude'] ?? null
    );
}

function lookup_ipdata(string $ip): ?array
{
    if (!defined('IPDATA_API_KEY') || trim((string) IPDATA_API_KEY) === '') {
        return null;
    }

    $url = 'https://api.ipdata.co/' . rawurlencode($ip)
        . '?api-key=' . rawurlencode(IPDATA_API_KEY);
    $data = http_get_json_array($url, 4);

    return ip_geo_payload(
        $ip,
        'ipdata.co',
        $data['country_name'] ?? $data['country_code'] ?? '',
        $data['region'] ?? '',
        $data['city'] ?? '',
        $data['latitude'] ?? null,
        $data['longitude'] ?? null
    );
}

function lookup_ipregistry(string $ip): ?array
{
    if (!defined('IPREGISTRY_API_KEY') || trim((string) IPREGISTRY_API_KEY) === '') {
        return null;
    }

    $url = 'https://api.ipregistry.co/' . rawurlencode($ip)
        . '?key=' . rawurlencode(IPREGISTRY_API_KEY);
    $data = http_get_json_array($url, 4);
    $location = is_array($data['location'] ?? null) ? $data['location'] : [];
    $country = is_array($location['country'] ?? null) ? $location['country'] : [];
    $region = is_array($location['region'] ?? null) ? $location['region'] : [];

    return ip_geo_payload(
        $ip,
        'Ipregistry',
        $country['name'] ?? $country['code'] ?? '',
        $region['name'] ?? '',
        $location['city'] ?? '',
        $location['latitude'] ?? null,
        $location['longitude'] ?? null
    );
}

function ip_geo_payload(
    string $ip,
    string $provider,
    mixed $country,
    mixed $region,
    mixed $city,
    mixed $latitude,
    mixed $longitude
): array {
    return [
        'ip' => $ip,
        'provider' => $provider,
        'country' => trim((string) $country),
        'region' => trim((string) $region),
        'city' => trim((string) $city),
        'latitude' => is_numeric($latitude) ? (float) $latitude : null,
        'longitude' => is_numeric($longitude) ? (float) $longitude : null,
    ];
}

function http_get_json_array(string $url, int $timeoutSeconds): array
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
            throw new RuntimeException($error !== '' ? $error : 'IP 查询请求失败。');
        }

        $decoded = json_decode((string) $raw, true);
        return is_array($decoded) ? $decoded : [];
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
        throw new RuntimeException('IP 查询请求失败。');
    }

    $decoded = json_decode($raw, true);
    return is_array($decoded) ? $decoded : [];
}
