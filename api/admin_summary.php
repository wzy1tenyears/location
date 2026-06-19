<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();

if (!is_admin_logged_in()) {
    json_response(['ok' => false, 'message' => '请先登录后台。'], 401);
}

try {
    $pdo = db();

    $stats = [
        'users' => (int) $pdo->query('SELECT COUNT(*) FROM users')->fetchColumn(),
        'active_users' => (int) $pdo->query('SELECT COUNT(*) FROM users WHERE is_active = 1')->fetchColumn(),
        'groups' => (int) $pdo->query('SELECT COUNT(*) FROM family_groups')->fetchColumn(),
        'locations' => (int) $pdo->query('SELECT COUNT(*) FROM locations')->fetchColumn(),
        'online_users' => 0,
        'open_tickets' => 0,
    ];

    $onlineCutoff = date('Y-m-d H:i:s', time() - 90);
    $onlineStmt = $pdo->prepare('SELECT COUNT(*) FROM user_presence WHERE last_seen_at >= ?');
    $onlineStmt->execute([$onlineCutoff]);
    $stats['online_users'] = (int) $onlineStmt->fetchColumn();

    $ticketStmt = $pdo->query("SELECT COUNT(*) FROM support_tickets WHERE status <> 'closed'");
    $stats['open_tickets'] = (int) $ticketStmt->fetchColumn();

    $groupsStmt = $pdo->query('
        SELECT
            fg.id,
            fg.group_name,
            fg.display_name,
            fg.group_code,
            fg.owner_user_id,
            fg.created_at,
            fg.updated_at,
            COUNT(ug.id) AS member_count
        FROM family_groups fg
        LEFT JOIN user_groups ug ON ug.group_name = fg.group_name
        GROUP BY fg.id, fg.group_name, fg.display_name, fg.group_code, fg.owner_user_id, fg.created_at, fg.updated_at
        ORDER BY fg.display_name ASC, fg.group_name ASC
        LIMIT 100
    ');

    $groups = array_map(static function (array $group): array {
        return [
            'id' => (int) $group['id'],
            'group_name' => (string) $group['group_name'],
            'display_name' => (string) ($group['display_name'] ?: $group['group_name']),
            'group_code' => (string) ($group['group_code'] ?? ''),
            'owner_user_id' => isset($group['owner_user_id']) ? (int) $group['owner_user_id'] : 0,
            'member_count' => (int) $group['member_count'],
            'created_at' => format_datetime((string) $group['created_at']),
            'updated_at' => format_datetime((string) $group['updated_at']),
        ];
    }, $groupsStmt->fetchAll());

    $usersStmt = $pdo->query('
        SELECT
            u.id,
            u.username,
            u.display_name,
            u.group_name,
            u.role,
            u.is_active,
            u.debug_mode,
            u.report_interval_seconds,
            up.last_seen_at,
            up.last_group_name,
            up.last_ip
        FROM users u
        LEFT JOIN user_presence up ON up.user_id = u.id
        ORDER BY u.id DESC
        LIMIT 100
    ');

    $users = array_map(static function (array $user) use ($onlineCutoff): array {
        $lastSeen = (string) ($user['last_seen_at'] ?? '');
        return [
            'id' => (int) $user['id'],
            'username' => (string) $user['username'],
            'display_name' => (string) $user['display_name'],
            'group_name' => (string) ($user['group_name'] ?? ''),
            'role' => normalize_role((string) $user['role']),
            'role_label' => role_label((string) $user['role']),
            'is_active' => (int) $user['is_active'] === 1,
            'debug_mode' => (int) ($user['debug_mode'] ?? 0) === 1,
            'report_interval_seconds' => (int) ($user['report_interval_seconds'] ?? 0),
            'last_seen_at' => format_datetime($lastSeen),
            'last_group_name' => (string) ($user['last_group_name'] ?? ''),
            'last_ip' => (string) ($user['last_ip'] ?? ''),
            'online' => $lastSeen !== '' && $lastSeen >= $onlineCutoff,
        ];
    }, $usersStmt->fetchAll());

    $locationsStmt = $pdo->query('
        SELECT
            ll.latest_location_id AS id,
            ll.user_id,
            ll.group_name,
            ug.role AS role,
            ll.latitude,
            ll.longitude,
            ll.altitude,
            ll.accuracy,
            ll.heading,
            ll.speed,
            ll.location_meta,
            ll.address_diagnostics,
            ll.address_mismatch,
            ll.encryption_mode,
            ll.encrypted_payload,
            ll.p2p_key_version,
            ll.updated_at,
            u.username,
            u.display_name
        FROM latest_group_locations ll
        INNER JOIN users u ON u.id = ll.user_id
        INNER JOIN user_groups ug ON ug.user_id = ll.user_id AND ug.group_name = ll.group_name
        WHERE u.is_active = 1
        ORDER BY ll.updated_at DESC
        LIMIT 100
    ');

    $locations = array_map(static function (array $location): array {
        $payload = location_payload($location) ?? [];
        $payload['id'] = isset($location['id']) ? (int) $location['id'] : 0;
        return $payload;
    }, $locationsStmt->fetchAll());

    $announcementStmt = $pdo->query('SELECT * FROM announcements ORDER BY id DESC LIMIT 1');
    $announcement = $announcementStmt->fetch() ?: null;
    $announcementPayload = $announcement ? [
        'id' => (int) $announcement['id'],
        'title' => (string) $announcement['title'],
        'body' => (string) $announcement['body'],
        'is_active' => (int) $announcement['is_active'] === 1,
        'version' => (int) $announcement['version'],
        'updated_at' => format_datetime((string) $announcement['updated_at']),
    ] : null;

    $invitesStmt = $pdo->query('
        SELECT *
        FROM invite_codes
        ORDER BY id DESC
        LIMIT 50
    ');
    $invites = array_map(static function (array $invite): array {
        return [
            'id' => (int) $invite['id'],
            'code' => (string) $invite['code'],
            'note' => (string) ($invite['note'] ?? ''),
            'invite_type' => (string) ($invite['invite_type'] ?? 'invite'),
            'invite_type_label' => (string) ($invite['invite_type'] ?? 'invite') === 'group_create' ? '组创建' : '纯邀请',
            'allow_group_owner' => (int) ($invite['allow_group_owner'] ?? 0) === 1,
            'max_uses' => (int) ($invite['max_uses'] ?? 1),
            'used_count' => (int) ($invite['used_count'] ?? 0),
            'is_active' => (int) ($invite['is_active'] ?? 0) === 1,
            'assigned_group_name' => (string) ($invite['assigned_group_name'] ?? ''),
            'created_at' => format_datetime((string) $invite['created_at']),
        ];
    }, $invitesStmt->fetchAll());

    $ticketsStmt = $pdo->query('
        SELECT
            t.*,
            u.username,
            u.display_name,
            latest.message AS last_message,
            latest.created_at AS last_message_at
        FROM support_tickets t
        INNER JOIN users u ON u.id = t.user_id
        LEFT JOIN support_ticket_messages latest ON latest.id = (
            SELECT id FROM support_ticket_messages WHERE ticket_id = t.id ORDER BY id DESC LIMIT 1
        )
        ORDER BY t.updated_at DESC, t.id DESC
        LIMIT 50
    ');
    $tickets = array_map(static function (array $ticket): array {
        return [
            'id' => (int) $ticket['id'],
            'user_id' => (int) $ticket['user_id'],
            'username' => (string) $ticket['username'],
            'display_name' => (string) $ticket['display_name'],
            'group_name' => (string) $ticket['group_name'],
            'subject' => (string) $ticket['subject'],
            'status' => (string) $ticket['status'],
            'status_label' => (string) $ticket['status'] === 'closed' ? '已关闭' : '处理中',
            'last_message' => (string) ($ticket['last_message'] ?? ''),
            'last_message_at' => format_datetime((string) ($ticket['last_message_at'] ?? '')),
            'created_at' => format_datetime((string) $ticket['created_at']),
            'updated_at' => format_datetime((string) $ticket['updated_at']),
        ];
    }, $ticketsStmt->fetchAll());

    json_response([
        'ok' => true,
        'stats' => $stats,
        'groups' => $groups,
        'users' => $users,
        'locations' => $locations,
        'announcement' => $announcementPayload,
        'invites' => $invites,
        'tickets' => $tickets,
        'server_time' => date('Y-m-d H:i:s'),
    ]);
} catch (Throwable $th) {
    json_response(['ok' => false, 'message' => api_error_message($th)], 500);
}
