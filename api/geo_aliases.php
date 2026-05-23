<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();

try {
    $pdo = db();
    $cityAliases = [];
    $regionAliases = [];

    if (table_exists($pdo, 'china_regions')) {
        $stmt = $pdo->query('SELECT level, name FROM china_regions ORDER BY level ASC, id ASC');
        foreach ($stmt->fetchAll(PDO::FETCH_ASSOC) as $row) {
            $level = (int) ($row['level'] ?? 0);
            $name = (string) ($row['name'] ?? '');
            if ($level === 1) {
                add_geo_alias($regionAliases, $name);
            } elseif ($level === 2 || $level === 3) {
                add_geo_alias($cityAliases, $name);
            }
        }
    }

    json_response([
        'ok' => true,
        'available' => $cityAliases !== [] || $regionAliases !== [],
        'aliases' => [
            'CITY_ALIASES' => $cityAliases,
            'REGION_ALIASES' => $regionAliases,
        ],
        'counts' => [
            'cities' => count($cityAliases),
            'regions' => count($regionAliases),
        ],
    ]);
} catch (Throwable $th) {
    json_response(['ok' => false, 'message' => api_error_message($th)], 500);
}

function add_geo_alias(array &$aliases, string $name): void
{
    $name = trim($name);
    if ($name === '' || $name === '市辖区' || $name === '县') {
        return;
    }

    $aliases[$name] = $name;

    $short = preg_replace('/(特别行政区|自治区|自治州|地区|省|市|县|区)$/u', '', $name);
    $short = is_string($short) ? trim($short) : '';
    if ($short !== '' && $short !== $name) {
        $aliases[$short] = $name;
    }
}
