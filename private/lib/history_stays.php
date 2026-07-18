<?php

declare(strict_types=1);

const HISTORY_STAY_RADIUS_METERS = 25.0;
const HISTORY_EARTH_RADIUS_METERS = 6371008.8;
const HISTORY_DISTANCE_EPSILON_METERS = 0.000001;

/**
 * Build a read-only stay view without changing the retained location rows.
 */
function history_merge_locations(array $rows, float $radiusMeters = HISTORY_STAY_RADIUS_METERS): array
{
    if (!$rows) {
        return [];
    }
    if (!is_finite($radiusMeters) || $radiusMeters <= 0) {
        $radiusMeters = HISTORY_STAY_RADIUS_METERS;
    }

    $partitions = [];
    foreach ($rows as $row) {
        if (!is_array($row)) {
            continue;
        }
        $partitionKey = (string) ($row['group_name'] ?? '')
            . "\0" . (int) ($row['user_id'] ?? 0)
            . "\0" . history_location_coordinate_system($row);
        $partitions[$partitionKey][] = $row;
    }

    $merged = [];
    foreach ($partitions as $partition) {
        usort($partition, static function (array $left, array $right): int {
            $timeCompare = history_compare_datetimes(
                (string) ($left['created_at'] ?? ''),
                (string) ($right['created_at'] ?? '')
            );
            if ($timeCompare !== 0) {
                return $timeCompare;
            }

            return (int) ($left['id'] ?? 0) <=> (int) ($right['id'] ?? 0);
        });

        if (!$partition) {
            continue;
        }

        $anchor = $partition[0];
        $stay = history_new_stay($anchor);
        foreach (array_slice($partition, 1) as $candidate) {
            if (history_locations_share_stay($anchor, $candidate, $radiusMeters)) {
                $stay = history_extend_stay($stay, $candidate);
                continue;
            }

            $merged[] = $stay;
            $anchor = $candidate;
            $stay = history_new_stay($candidate);
        }
        $merged[] = $stay;
    }

    usort($merged, static function (array $left, array $right): int {
        $timeCompare = history_compare_datetimes(
            (string) ($right['last_reported_at'] ?? $right['created_at'] ?? ''),
            (string) ($left['last_reported_at'] ?? $left['created_at'] ?? '')
        );
        if ($timeCompare !== 0) {
            return $timeCompare;
        }

        $idCompare = (int) ($right['id'] ?? 0) <=> (int) ($left['id'] ?? 0);
        if ($idCompare !== 0) {
            return $idCompare;
        }

        $groupCompare = strcmp((string) ($left['group_name'] ?? ''), (string) ($right['group_name'] ?? ''));
        if ($groupCompare !== 0) {
            return $groupCompare;
        }

        return (int) ($left['user_id'] ?? 0) <=> (int) ($right['user_id'] ?? 0);
    });

    return $merged;
}

function history_new_stay(array $row): array
{
    $createdAt = (string) ($row['created_at'] ?? '');
    $diagnostics = history_json_object($row['address_diagnostics'] ?? null);
    if ($diagnostics !== null) {
        $row['address_diagnostics'] = history_encode_json_object($diagnostics);
    }
    $row['first_reported_at'] = $createdAt;
    $row['last_reported_at'] = $createdAt;
    $row['stay_duration_seconds'] = 0;
    $row['report_count'] = 1;
    return $row;
}

function history_extend_stay(array $stay, array $latest): array
{
    $firstReportedAt = (string) ($stay['first_reported_at'] ?? $stay['created_at'] ?? '');
    $previousDiagnostics = history_json_object($stay['address_diagnostics'] ?? null);
    $latestDiagnostics = history_json_object($latest['address_diagnostics'] ?? null);
    $latestHasDiagnostics = $latestDiagnostics !== null
        && history_diagnostic_value_present($latestDiagnostics);
    $mergedDiagnostics = history_merge_address_diagnostics($previousDiagnostics, $latestDiagnostics);
    if ($mergedDiagnostics !== null) {
        $latest['address_diagnostics'] = history_encode_json_object($mergedDiagnostics);
        $latest['address_mismatch'] = $latestHasDiagnostics
            ? (int) ($latest['address_mismatch'] ?? 0)
            : (int) ($stay['address_mismatch'] ?? 0);
    } elseif (is_array($latest['address_diagnostics'] ?? null)) {
        $latest['address_diagnostics'] = history_encode_json_object($latest['address_diagnostics']);
    }

    $lastReportedAt = (string) ($latest['created_at'] ?? '');
    $latest['first_reported_at'] = $firstReportedAt;
    $latest['last_reported_at'] = $lastReportedAt;
    $latest['stay_duration_seconds'] = history_duration_seconds($firstReportedAt, $lastReportedAt);
    $latest['report_count'] = max(1, (int) ($stay['report_count'] ?? 1)) + 1;
    return $latest;
}

/**
 * Merge diagnostic snapshots without combining fields from unrelated probe identities.
 */
function history_merge_address_diagnostics(?array $previous, ?array $latest): ?array
{
    if ($previous === null && $latest === null) {
        return null;
    }

    $previous ??= [];
    $latest ??= [];
    $merged = array_replace($previous, $latest);
    history_fill_missing_diagnostic_fields($merged, $previous);
    $bestSources = [];
    foreach ([$previous, $latest] as $diagnostics) {
        $sources = $diagnostics['sources'] ?? [];
        if (!is_array($sources)) {
            continue;
        }
        foreach ($sources as $sourceIndex => $source) {
            if (!is_array($source)) {
                continue;
            }
            $type = strtolower(trim((string) ($source['type'] ?? '')));
            if (!in_array($type, ['gps', 'ip', 'webrtc'], true)) {
                continue;
            }

            $source['type'] = $type;
            $identity = history_diagnostic_source_identity($source, $type, (int) $sourceIndex);
            $bestSources[$identity] = isset($bestSources[$identity])
                ? history_merge_diagnostic_source($bestSources[$identity], $source, $type)
                : $source;
        }
    }

    uksort($bestSources, static function (string $left, string $right) use ($bestSources): int {
        $leftRank = history_diagnostic_source_type_rank((string) ($bestSources[$left]['type'] ?? ''));
        $rightRank = history_diagnostic_source_type_rank((string) ($bestSources[$right]['type'] ?? ''));
        return $leftRank === $rightRank ? strcmp($left, $right) : $leftRank <=> $rightRank;
    });
    $merged['sources'] = array_values($bestSources);

    history_merge_preferred_diagnostics($merged, $previous, $latest);
    return $merged;
}

function history_merge_preferred_diagnostics(array &$merged, array $previous, array $latest): void
{
    $preferredFields = [
        'source',
        'address',
        'detail',
        'poi',
        'district',
        'street',
        'postal_code',
        'city',
        'region',
        'country',
        'coordinate_system',
        'latitude',
        'longitude',
    ];
    $previousPreferred = history_preferred_diagnostic_candidate($previous, $preferredFields);
    $latestPreferred = history_preferred_diagnostic_candidate($latest, $preferredFields);
    if (history_diagnostics_score_compare(
        history_diagnostics_address_precision_score($previousPreferred),
        history_diagnostics_address_precision_score($latestPreferred)
    ) <= 0) {
        return;
    }

    foreach ($preferredFields as $field) {
        $key = 'preferred_' . $field;
        if (array_key_exists($key, $previous) && history_diagnostic_value_present($previous[$key])) {
            $merged[$key] = $previous[$key];
        }
    }
}

function history_preferred_diagnostic_candidate(array $diagnostics, array $fields): array
{
    $candidate = [];
    foreach ($fields as $field) {
        $key = 'preferred_' . $field;
        if (array_key_exists($key, $diagnostics)) {
            $candidate[$field] = $diagnostics[$key];
        }
    }
    return $candidate;
}

function history_merge_diagnostic_source(array $previous, array $latest, string $sourceType): array
{
    $merged = history_diagnostic_package_better($previous, $latest) ? $previous : $latest;
    foreach (['variants', 'candidates'] as $nestedKey) {
        $nested = history_merge_diagnostic_evidence(
            $previous[$nestedKey] ?? null,
            $latest[$nestedKey] ?? null,
            $sourceType,
            $nestedKey
        );
        if ($nested === []) {
            unset($merged[$nestedKey]);
        } else {
            $merged[$nestedKey] = $nested;
        }
    }
    return $merged;
}

function history_merge_diagnostic_evidence(mixed $previous, mixed $latest, string $sourceType, string $nestedKey): array
{
    $selected = [];
    foreach ([$previous, $latest] as $items) {
        if (!is_array($items)) {
            continue;
        }
        foreach ($items as $itemIndex => $item) {
            if (!is_array($item)) {
                continue;
            }
            $identity = history_diagnostic_evidence_identity(
                $item,
                $sourceType,
                $nestedKey,
                (int) $itemIndex
            );
            if (!isset($selected[$identity]) || !history_diagnostic_package_better($selected[$identity], $item)) {
                $selected[$identity] = $item;
            }
        }
    }
    ksort($selected, SORT_STRING);
    return array_values($selected);
}

function history_diagnostic_source_identity(array $source, string $sourceType, int $fallbackIndex): string
{
    $networkIdentity = history_diagnostic_network_identity($source);
    $stunServer = $sourceType === 'webrtc'
        ? history_diagnostic_identity_text($source['stun_server'] ?? '')
        : '';
    $sourceIdentity = history_first_diagnostic_identity_text($source, ['source', 'provider', 'name']);
    if ($networkIdentity !== '' || $stunServer !== '' || $sourceIdentity !== '') {
        return history_frame_diagnostic_identity('source', $sourceType, $networkIdentity, $stunServer, $sourceIdentity);
    }
    return history_frame_diagnostic_identity(
        'source-anonymous',
        $sourceType,
        history_diagnostic_anonymous_identity($source, $fallbackIndex)
    );
}

function history_diagnostic_evidence_identity(
    array $item,
    string $sourceType,
    string $nestedKey,
    int $fallbackIndex
): string {
    $networkIdentity = history_diagnostic_network_identity($item);
    $stunServer = history_diagnostic_identity_text($item['stun_server'] ?? '');
    $sourceIdentity = history_first_diagnostic_identity_text($item, ['source', 'provider', 'label']);
    if ($networkIdentity !== '' || $stunServer !== '' || $sourceIdentity !== '') {
        return history_frame_diagnostic_identity(
            'evidence',
            $sourceType,
            $nestedKey,
            $networkIdentity,
            $stunServer,
            $sourceIdentity
        );
    }
    return history_frame_diagnostic_identity(
        'evidence-anonymous',
        $sourceType,
        $nestedKey,
        history_diagnostic_anonymous_identity($item, $fallbackIndex)
    );
}

function history_frame_diagnostic_identity(string ...$parts): string
{
    $framed = '';
    foreach ($parts as $part) {
        $framed .= strlen($part) . ':' . $part;
    }
    return $framed;
}

function history_diagnostic_network_identity(array $value): string
{
    foreach (['ip', 'server_ip', 'ipv4', 'ipv6'] as $key) {
        $candidate = history_diagnostic_identity_text($value[$key] ?? '');
        if ($candidate !== '') {
            return trim($candidate, '[]');
        }
    }
    return '';
}

function history_first_diagnostic_identity_text(array $value, array $keys): string
{
    foreach ($keys as $key) {
        $candidate = history_diagnostic_identity_text($value[$key] ?? '');
        if ($candidate !== '') {
            return $candidate;
        }
    }
    return '';
}

function history_diagnostic_identity_text(mixed $value): string
{
    return strtolower(trim((string) $value));
}

function history_diagnostic_anonymous_identity(array $value, int $fallbackIndex): string
{
    unset($value['variants'], $value['candidates']);
    $encoded = json_encode(
        $value,
        JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE
    );
    return is_string($encoded) && $encoded !== '' ? $encoded : (string) $fallbackIndex;
}

function history_diagnostic_source_type_rank(string $sourceType): int
{
    return match (strtolower(trim($sourceType))) {
        'gps' => 0,
        'ip' => 1,
        'webrtc' => 2,
        default => 3,
    };
}

function history_diagnostic_package_better(array $candidate, array $selected): bool
{
    return history_diagnostics_score_compare(
        history_diagnostics_address_precision_score($candidate),
        history_diagnostics_address_precision_score($selected)
    ) > 0;
}

function history_fill_missing_diagnostic_fields(array &$destination, array $fallback): void
{
    foreach ($fallback as $key => $value) {
        if (!history_diagnostic_value_present($destination[$key] ?? null)
            && history_diagnostic_value_present($value)) {
            $destination[$key] = $value;
        }
    }
}

function history_diagnostic_value_present(mixed $value): bool
{
    if ($value === null) {
        return false;
    }
    if (is_string($value)) {
        return trim($value) !== '';
    }
    if (is_array($value)) {
        foreach ($value as $nested) {
            if (history_diagnostic_value_present($nested)) {
                return true;
            }
        }
        return false;
    }
    return true;
}

function history_diagnostics_source_precision_score(array $source): array
{
    $best = history_diagnostics_address_precision_score($source);
    foreach (['variants', 'candidates'] as $field) {
        $items = $source[$field] ?? [];
        if (!is_array($items)) {
            continue;
        }
        foreach ($items as $item) {
            if (!is_array($item)) {
                continue;
            }
            $score = history_diagnostics_address_precision_score($item);
            if (history_diagnostics_score_compare($score, $best) > 0) {
                $best = $score;
            }
        }
    }
    return $best;
}

function history_diagnostics_address_precision_score(array $item): array
{
    $specificity = 0;
    $structuredFields = 0;
    foreach (['country', 'region', 'city', 'postal_code', 'district', 'street', 'detail', 'poi'] as $field) {
        if (trim((string) ($item[$field] ?? '')) !== '') {
            $structuredFields += 1;
        }
    }
    foreach ([
        'country' => 1,
        'region' => 2,
        'city' => 3,
        'postal_code' => 4,
        'district' => 5,
        'street' => 6,
    ] as $field => $fieldSpecificity) {
        if (trim((string) ($item[$field] ?? '')) !== '') {
            $specificity = max($specificity, $fieldSpecificity);
        }
    }
    if (trim((string) ($item['detail'] ?? '')) !== '' || trim((string) ($item['poi'] ?? '')) !== '') {
        $specificity = max($specificity, 7);
    }
    $address = trim((string) ($item['address'] ?? ''));
    if ($address !== '') {
        $specificity = max($specificity, strlen($address) >= 24 ? 5 : 4);
    }

    return [
        $specificity,
        $address !== '' ? 1 : 0,
        $structuredFields,
        min(600, strlen($address)),
        ($item['latitude'] ?? null) !== null && ($item['longitude'] ?? null) !== null ? 1 : 0,
        trim((string) ($item['coordinate_system'] ?? '')) !== '' ? 1 : 0,
    ];
}

function history_diagnostics_score_compare(array $left, array $right): int
{
    $length = max(count($left), count($right));
    for ($index = 0; $index < $length; $index += 1) {
        $leftValue = (int) ($left[$index] ?? 0);
        $rightValue = (int) ($right[$index] ?? 0);
        if ($leftValue !== $rightValue) {
            return $leftValue <=> $rightValue;
        }
    }
    return 0;
}

function history_encode_json_object(array $value): string
{
    if ($value === []) {
        return '{}';
    }
    $json = json_encode(
        $value,
        JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE
    );
    if (!is_string($json)) {
        throw new RuntimeException('Failed to encode address diagnostics.');
    }
    return $json;
}

function history_locations_share_stay(array $anchor, array $candidate, float $radiusMeters): bool
{
    if (!history_location_is_mergeable($anchor) || !history_location_is_mergeable($candidate)) {
        return false;
    }

    $anchorSystem = history_location_coordinate_system($anchor);
    $candidateSystem = history_location_coordinate_system($candidate);
    if ($anchorSystem === '' || $candidateSystem === '' || $anchorSystem !== $candidateSystem) {
        return false;
    }

    return history_haversine_meters(
        (float) $anchor['latitude'],
        (float) $anchor['longitude'],
        (float) $candidate['latitude'],
        (float) $candidate['longitude']
    ) <= $radiusMeters + HISTORY_DISTANCE_EPSILON_METERS;
}

function history_location_is_mergeable(array $row): bool
{
    if (strcasecmp(trim((string) ($row['encryption_mode'] ?? '')), 'p2p-v1') === 0
        || trim((string) ($row['encrypted_payload'] ?? '')) !== '') {
        return false;
    }

    $latitude = $row['latitude'] ?? null;
    $longitude = $row['longitude'] ?? null;
    if (!is_numeric($latitude) || !is_numeric($longitude)) {
        return false;
    }

    $latitude = (float) $latitude;
    $longitude = (float) $longitude;
    if (!is_finite($latitude) || !is_finite($longitude)
        || $latitude < -90 || $latitude > 90
        || $longitude < -180 || $longitude > 180) {
        return false;
    }

    return $latitude != 0.0 || $longitude != 0.0;
}

function history_location_coordinate_system(array $row): string
{
    $meta = history_json_object($row['location_meta'] ?? null);
    if ($meta !== null && trim((string) ($meta['coordinate_system'] ?? '')) !== '') {
        return history_normalize_coordinate_system((string) $meta['coordinate_system']);
    }

    $diagnostics = history_json_object($row['address_diagnostics'] ?? null);
    if ($diagnostics !== null) {
        foreach (($diagnostics['sources'] ?? []) as $source) {
            if (!is_array($source) || strcasecmp(trim((string) ($source['type'] ?? '')), 'gps') !== 0) {
                continue;
            }
            if (trim((string) ($source['coordinate_system'] ?? '')) !== '') {
                return history_normalize_coordinate_system((string) $source['coordinate_system']);
            }
        }

        if (trim((string) ($diagnostics['preferred_coordinate_system'] ?? '')) !== '') {
            return history_normalize_coordinate_system((string) $diagnostics['preferred_coordinate_system']);
        }
    }

    return 'wgs84';
}

function history_normalize_coordinate_system(string $value): string
{
    $normalized = strtolower((string) preg_replace('/[^a-z0-9]/i', '', $value));
    return match ($normalized) {
        'wgs84', 'gps' => 'wgs84',
        'gcj02', 'gcj', 'amap', 'gaode' => 'gcj02',
        'bd09', 'baidu' => 'bd09',
        default => '',
    };
}

function history_haversine_meters(float $latitudeA, float $longitudeA, float $latitudeB, float $longitudeB): float
{
    $latitudeARadians = deg2rad($latitudeA);
    $latitudeBRadians = deg2rad($latitudeB);
    $latitudeDelta = deg2rad($latitudeB - $latitudeA);
    $longitudeDelta = deg2rad($longitudeB - $longitudeA);

    $haversine = sin($latitudeDelta / 2) ** 2
        + cos($latitudeARadians) * cos($latitudeBRadians) * sin($longitudeDelta / 2) ** 2;
    $haversine = min(1.0, max(0.0, $haversine));
    return 2 * HISTORY_EARTH_RADIUS_METERS * atan2(sqrt($haversine), sqrt(1 - $haversine));
}

function history_diagnostics_have_place(mixed $value): bool
{
    $diagnostics = history_json_object($value);
    return $diagnostics !== null && history_value_contains_place($diagnostics);
}

function history_value_contains_place(mixed $value): bool
{
    if (!is_array($value)) {
        return false;
    }

    static $placeFields = [
        'preferred_address' => true,
        'preferred_detail' => true,
        'preferred_poi' => true,
        'preferred_district' => true,
        'preferred_street' => true,
        'preferred_city' => true,
        'preferred_region' => true,
        'preferred_country' => true,
        'preferred_postal_code' => true,
        'address' => true,
        'detail' => true,
        'poi' => true,
        'district' => true,
        'street' => true,
        'city' => true,
        'region' => true,
        'country' => true,
        'postal_code' => true,
    ];

    foreach ($value as $key => $item) {
        if (is_string($key) && isset($placeFields[$key]) && trim((string) $item) !== '') {
            return true;
        }
        if (is_array($item) && history_value_contains_place($item)) {
            return true;
        }
    }

    return false;
}

function history_json_object(mixed $value): ?array
{
    if (is_array($value)) {
        return $value;
    }
    if (!is_string($value) || trim($value) === '') {
        return null;
    }

    $decoded = json_decode($value, true);
    return is_array($decoded) ? $decoded : null;
}

function history_duration_seconds(string $first, string $last): int
{
    $firstTimestamp = strtotime($first);
    $lastTimestamp = strtotime($last);
    if ($firstTimestamp === false || $lastTimestamp === false) {
        return 0;
    }

    return max(0, $lastTimestamp - $firstTimestamp);
}

function history_compare_datetimes(string $left, string $right): int
{
    $leftTimestamp = strtotime($left);
    $rightTimestamp = strtotime($right);
    if ($leftTimestamp !== false && $rightTimestamp !== false && $leftTimestamp !== $rightTimestamp) {
        return $leftTimestamp <=> $rightTimestamp;
    }

    return strcmp($left, $right);
}

function history_paginate(array $locations, int $page, int $perPage): array
{
    if ($perPage <= 0) {
        $perPage = 20;
    }
    $total = count($locations);
    $totalPages = max(1, (int) ceil($total / $perPage));
    $page = min(max(1, $page), $totalPages);
    $rows = array_slice($locations, ($page - 1) * $perPage, $perPage);

    return [
        'rows' => $rows,
        'page' => $page,
        'total' => $total,
        'total_pages' => $totalPages,
    ];
}

function history_limit_per_user(array $locations, int $limit): array
{
    if ($limit <= 0) {
        return [];
    }

    $counts = [];
    $limited = [];
    foreach ($locations as $location) {
        $key = (string) ($location['group_name'] ?? '') . "\0" . (int) ($location['user_id'] ?? 0);
        if (($counts[$key] ?? 0) >= $limit) {
            continue;
        }
        $counts[$key] = ($counts[$key] ?? 0) + 1;
        $limited[] = $location;
    }
    return $limited;
}

function history_compose_view(
    array $rawRows,
    int $page,
    int $perPage,
    int $mapPerUser,
    float $radiusMeters = HISTORY_STAY_RADIUS_METERS
): array {
    $merged = history_merge_locations($rawRows, $radiusMeters);
    $pagination = history_paginate($merged, $page, $perPage);
    return [
        'rows' => $pagination['rows'],
        'map_rows' => history_limit_per_user($merged, $mapPerUser),
        'page' => $pagination['page'],
        'total' => $pagination['total'],
        'total_pages' => $pagination['total_pages'],
    ];
}
