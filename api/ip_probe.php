<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();
require_user();

function first_forwarded_ip(string $value): string
{
    $parts = array_map('trim', explode(',', $value));
    return (string) ($parts[0] ?? '');
}

$headers = [
    'HTTP_CF_CONNECTING_IP',
    'HTTP_X_REAL_IP',
    'HTTP_X_FORWARDED_FOR',
    'REMOTE_ADDR',
];

$ip = '';
foreach ($headers as $header) {
    $value = (string) ($_SERVER[$header] ?? '');
    if ($value === '') {
        continue;
    }

    $candidate = $header === 'HTTP_X_FORWARDED_FOR' ? first_forwarded_ip($value) : trim($value);
    if (filter_var($candidate, FILTER_VALIDATE_IP)) {
        $ip = $candidate;
        break;
    }
}

json_response([
    'ok' => true,
    'ip' => $ip,
]);
