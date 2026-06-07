<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();

try {
    $user = require_user();
    $data = request_data();
    $membership = require_user_membership($user, selected_group_name_from_request());
    $page = (int) ($data['page'] ?? 1);
    $perPage = (int) ($data['per_page'] ?? 20);
    $mapPerUser = (int) ($data['map_per_user'] ?? 20);
    $filterUserId = (int) ($data['user_id'] ?? 0);

    if (!in_array($perPage, [20, 50, 100], true)) {
        $perPage = 20;
    }
    if (!in_array($mapPerUser, [20, 50, 100], true)) {
        $mapPerUser = 20;
    }

    if ($page < 1) {
        $page = 1;
    }

    $membersSql = '
        SELECT
            u.id AS user_id,
            u.username,
            u.display_name,
            ug.role
        FROM users u
        INNER JOIN user_groups ug ON ug.user_id = u.id
        WHERE ug.group_name = ? AND u.is_active = 1
    ';
    $membersSql .= ' ORDER BY ug.role ASC, u.username ASC';

    $membersStmt = db()->prepare($membersSql);
    $membersStmt->execute([(string) $membership['group_name']]);
    $allMembers = $membersStmt->fetchAll();
    $members = $allMembers;

    if ($filterUserId > 0) {
        $members = array_values(array_filter(
            $allMembers,
            static fn (array $member): bool => (int) $member['user_id'] === $filterUserId
        ));
    }

    if ($filterUserId > 0 && !$members) {
        throw new RuntimeException('无权查看这个成员。');
    }

    $userFilterSql = $filterUserId > 0 ? ' AND l.user_id = ?' : '';
    $countParams = [(string) $membership['group_name']];
    if ($filterUserId > 0) {
        $countParams[] = $filterUserId;
    }

    $countStmt = db()->prepare('
        SELECT COUNT(*)
        FROM locations l
        INNER JOIN users u ON u.id = l.user_id
        INNER JOIN user_groups ug ON ug.user_id = l.user_id AND ug.group_name = l.group_name
        WHERE l.group_name = ? AND u.is_active = 1' . $userFilterSql . '
    ');
    $countStmt->execute($countParams);
    $total = (int) $countStmt->fetchColumn();
    $totalPages = (int) ceil($total / $perPage);

    $totalPages = max(1, $totalPages);
    if ($page > $totalPages) {
        $page = $totalPages;
    }
    $offset = ($page - 1) * $perPage;

    $historyStmt = db()->prepare('
        SELECT
            l.id,
            l.user_id,
            l.group_name,
            l.role,
            l.latitude,
            l.longitude,
            l.altitude,
            l.accuracy,
            l.heading,
            l.speed,
            l.location_meta,
            l.address_diagnostics,
            l.address_mismatch,
            l.encryption_mode,
            l.encrypted_payload,
            l.p2p_key_version,
            l.created_at,
            u.username,
            u.display_name
        FROM locations l
        INNER JOIN users u ON u.id = l.user_id
        INNER JOIN user_groups ug ON ug.user_id = l.user_id AND ug.group_name = l.group_name
        WHERE l.group_name = ? AND u.is_active = 1' . $userFilterSql . '
        ORDER BY l.created_at DESC, l.id DESC
        LIMIT ? OFFSET ?
    ');

    $historyStmt->bindValue(1, (string) $membership['group_name'], PDO::PARAM_STR);
    $nextParam = 2;
    if ($filterUserId > 0) {
        $historyStmt->bindValue($nextParam, $filterUserId, PDO::PARAM_INT);
        $nextParam += 1;
    }
    $historyStmt->bindValue($nextParam, $perPage, PDO::PARAM_INT);
    $historyStmt->bindValue($nextParam + 1, $offset, PDO::PARAM_INT);
    $historyStmt->execute();
    $rows = $historyStmt->fetchAll();

    $mapStmt = db()->prepare('
        SELECT
            l.id,
            l.user_id,
            l.group_name,
            l.role,
            l.latitude,
            l.longitude,
            l.altitude,
            l.accuracy,
            l.heading,
            l.speed,
            l.location_meta,
            l.address_diagnostics,
            l.address_mismatch,
            l.encryption_mode,
            l.encrypted_payload,
            l.p2p_key_version,
            l.created_at,
            u.username,
            u.display_name
        FROM locations l
        INNER JOIN users u ON u.id = l.user_id
        INNER JOIN user_groups ug ON ug.user_id = l.user_id AND ug.group_name = l.group_name
        WHERE l.group_name = ? AND l.user_id = ? AND u.is_active = 1
        ORDER BY l.created_at DESC, l.id DESC
        LIMIT ?
    ');

    $mapRows = [];
    foreach ($members as $member) {
        if ($mapPerUser === 20) {
            $cachedRows = user_history_locations_cache_get((string) $membership['group_name'], (int) $member['user_id']);
            if (is_array($cachedRows)) {
                $mapRows = array_merge($mapRows, $cachedRows);
                continue;
            }
        }

        $mapStmt->bindValue(1, (string) $membership['group_name'], PDO::PARAM_STR);
        $mapStmt->bindValue(2, (int) $member['user_id'], PDO::PARAM_INT);
        $mapStmt->bindValue(3, $mapPerUser, PDO::PARAM_INT);
        $mapStmt->execute();
        $memberRows = $mapStmt->fetchAll();
        if ($mapPerUser === 20) {
            user_history_locations_cache_set((string) $membership['group_name'], (int) $member['user_id'], $memberRows);
        }
        $mapRows = array_merge($mapRows, $memberRows);
    }

    usort($mapRows, static function (array $left, array $right): int {
        $timeCompare = strcmp((string) $right['created_at'], (string) $left['created_at']);
        if ($timeCompare !== 0) {
            return $timeCompare;
        }

        return (int) $right['id'] <=> (int) $left['id'];
    });

    $historyPayload = static function (array $row): array {
        $diagnostics = null;
        if (!empty($row['address_diagnostics'])) {
            $decoded = json_decode((string) $row['address_diagnostics'], true);
            $diagnostics = is_array($decoded) ? $decoded : null;
        }

        return [
            'id' => (int) $row['id'],
            'user_id' => (int) $row['user_id'],
            'username' => $row['username'],
            'display_name' => $row['display_name'],
            'role' => normalize_role((string) $row['role']),
            'role_label' => role_label((string) $row['role']),
            'group_name' => $row['group_name'],
            'latitude' => (float) $row['latitude'],
            'longitude' => (float) $row['longitude'],
            'altitude' => $row['altitude'] === null ? null : (float) $row['altitude'],
            'accuracy' => $row['accuracy'] === null ? null : (float) $row['accuracy'],
            'heading' => $row['heading'] === null ? null : (float) $row['heading'],
            'speed' => $row['speed'] === null ? null : (float) $row['speed'],
            'location_meta' => !empty($row['location_meta']) ? json_decode((string) $row['location_meta'], true) : null,
            'address_mismatch' => (int) ($row['address_mismatch'] ?? 0) === 1,
            'address_diagnostics' => $diagnostics,
            'encryption_mode' => (string) ($row['encryption_mode'] ?? ''),
            'encrypted_payload' => (string) ($row['encrypted_payload'] ?? ''),
            'p2p_key_version' => (int) ($row['p2p_key_version'] ?? 0),
            'created_at' => format_datetime((string) $row['created_at']),
        ];
    };

    $history = array_map($historyPayload, $rows);
    $mapHistory = array_map($historyPayload, $mapRows);

    json_response([
        'ok' => true,
        'user' => public_user_payload_for_group($user, $membership),
        'selected_group' => group_payload($membership),
        'members' => array_map(static function (array $member): array {
            return [
                'user_id' => (int) $member['user_id'],
                'username' => $member['username'],
                'display_name' => $member['display_name'],
                'role' => normalize_role((string) $member['role']),
                'role_label' => role_label((string) $member['role']),
            ];
        }, $allMembers),
        'history' => $history,
        'map_history' => $mapHistory,
        'pagination' => [
            'page' => $page,
            'per_page' => $perPage,
            'map_per_user' => $mapPerUser,
            'total' => $total,
            'total_pages' => $totalPages,
            'user_id' => $filterUserId,
        ],
        'server_time' => date('Y-m-d H:i:s'),
    ]);
} catch (Throwable $th) {
    json_response(['ok' => false, 'message' => api_error_message($th)], 500);
}
