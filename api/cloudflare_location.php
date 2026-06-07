<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();
require_user();

function cf_location_header(string $key, int $maxLength = 120): string
{
    $value = trim((string) ($_SERVER[$key] ?? ''));
    if ($value === '') {
        return '';
    }

    $decoded = rawurldecode($value);
    if (function_exists('mb_strlen') && mb_strlen($decoded, 'UTF-8') > $maxLength) {
        return mb_substr($decoded, 0, $maxLength, 'UTF-8');
    }

    if (!function_exists('mb_strlen') && strlen($decoded) > $maxLength * 4) {
        return substr($decoded, 0, $maxLength * 4);
    }

    return $decoded;
}

function cf_location_float(string $key, float $min, float $max): ?float
{
    $value = cf_location_header($key, 40);
    if ($value === '' || !is_numeric($value)) {
        return null;
    }

    $number = (float) $value;
    if (!is_finite($number) || $number < $min || $number > $max) {
        return null;
    }

    return $number;
}

$ip = cf_location_header('HTTP_CF_CONNECTING_IP', 80);
if (!filter_var($ip, FILTER_VALIDATE_IP)) {
    $ip = '';
}

$ipv6 = cf_location_header('HTTP_CF_CONNECTING_IPV6', 80);
if (!filter_var($ipv6, FILTER_VALIDATE_IP, FILTER_FLAG_IPV6)) {
    $ipv6 = '';
}

$countryCode = strtoupper(cf_location_header('HTTP_CF_IPCOUNTRY', 8));
$city = cf_location_header('HTTP_CF_IPCITY', 120);
$region = cf_location_header('HTTP_CF_REGION', 120);
$regionCode = cf_location_header('HTTP_CF_REGION_CODE', 40);
$latitude = cf_location_float('HTTP_CF_IPLATITUDE', -90, 90);
$longitude = cf_location_float('HTTP_CF_IPLONGITUDE', -180, 180);

json_response([
    'ok' => true,
    'available' => $ip !== ''
        || $ipv6 !== ''
        || $countryCode !== ''
        || $city !== ''
        || $region !== ''
        || $latitude !== null
        || $longitude !== null,
    'ip' => $ip,
    'ipv6' => $ipv6,
    'country' => $countryCode,
    'country_code' => $countryCode,
    'city' => $city,
    'region' => $region,
    'region_code' => $regionCode,
    'continent' => cf_location_header('HTTP_CF_IPCONTINENT', 40),
    'latitude' => $latitude,
    'longitude' => $longitude,
    'metro_code' => cf_location_header('HTTP_CF_METRO_CODE', 40),
    'postal_code' => cf_location_header('HTTP_CF_POSTAL_CODE', 40),
    'timezone' => cf_location_header('HTTP_CF_TIMEZONE', 80),
]);
