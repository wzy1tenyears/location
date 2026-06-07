<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();

try {
    $user = require_user();
    $pdo = db();

    if ($_SERVER['REQUEST_METHOD'] === 'GET') {
        $ticketId = (int) ($_GET['ticket_id'] ?? 0);
        if ($ticketId > 0) {
            $stmt = $pdo->prepare('SELECT * FROM support_tickets WHERE id = ? AND user_id = ? LIMIT 1');
            $stmt->execute([$ticketId, (int) $user['id']]);
            $ticket = $stmt->fetch();
            if (!$ticket) {
                json_response(['ok' => false, 'message' => '工单不存在。'], 404);
            }

            json_response([
                'ok' => true,
                'ticket' => ticket_payload($ticket),
                'messages' => ticket_messages($pdo, $ticketId),
            ]);
        }

        $stmt = $pdo->prepare('
            SELECT
                t.*,
                last_message.message AS last_message,
                last_message.created_at AS last_message_at
            FROM support_tickets t
            LEFT JOIN (
                SELECT m1.*
                FROM support_ticket_messages m1
                INNER JOIN (
                    SELECT ticket_id, MAX(id) AS latest_id
                    FROM support_ticket_messages
                    GROUP BY ticket_id
                ) latest ON latest.latest_id = m1.id
            ) last_message ON last_message.ticket_id = t.id
            WHERE t.user_id = ?
            ORDER BY t.updated_at DESC, t.id DESC
            LIMIT 50
        ');
        $stmt->execute([(int) $user['id']]);
        json_response([
            'ok' => true,
            'tickets' => array_map('ticket_payload', $stmt->fetchAll()),
        ]);
    }

    if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
        json_response(['ok' => false, 'message' => 'Method not allowed.'], 405);
    }

    $data = request_data();
    $action = trim((string) ($data['action'] ?? ''));

    if ($action === 'create') {
        $membership = user_membership_for_group($user, selected_group_name_from_request());
        $ticketGroupName = $membership ? (string) $membership['group_name'] : '';
        $subject = input_string('subject', 120);
        $message = input_string('message', 2000);
        if ($subject === '' || $message === '') {
            json_response(['ok' => false, 'message' => '请填写标题和内容。'], 422);
        }

        $pdo->beginTransaction();
        $stmt = $pdo->prepare('INSERT INTO support_tickets (user_id, group_name, subject) VALUES (?, ?, ?)');
        $stmt->execute([(int) $user['id'], $ticketGroupName, $subject]);
        $ticketId = (int) $pdo->lastInsertId();
        $stmt = $pdo->prepare("INSERT INTO support_ticket_messages (ticket_id, sender_type, message) VALUES (?, 'user', ?)");
        $stmt->execute([$ticketId, $message]);
        $pdo->commit();

        record_user_log((int) $user['id'], $ticketGroupName, 'ticket_create', $subject);
        json_response(['ok' => true, 'ticket_id' => $ticketId]);
    }

    if ($action === 'reply') {
        $ticketId = (int) ($data['ticket_id'] ?? 0);
        $message = input_string('message', 2000);
        if ($ticketId <= 0 || $message === '') {
            json_response(['ok' => false, 'message' => '回复内容不能为空。'], 422);
        }

        $stmt = $pdo->prepare('SELECT * FROM support_tickets WHERE id = ? AND user_id = ? LIMIT 1');
        $stmt->execute([$ticketId, (int) $user['id']]);
        $ticket = $stmt->fetch();
        if (!$ticket) {
            json_response(['ok' => false, 'message' => '工单不存在。'], 404);
        }
        if ((string) $ticket['status'] === 'closed') {
            json_response(['ok' => false, 'message' => '工单已关闭。'], 409);
        }

        $pdo->beginTransaction();
        $stmt = $pdo->prepare("INSERT INTO support_ticket_messages (ticket_id, sender_type, message) VALUES (?, 'user', ?)");
        $stmt->execute([$ticketId, $message]);
        $stmt = $pdo->prepare("UPDATE support_tickets SET updated_at = NOW() WHERE id = ?");
        $stmt->execute([$ticketId]);
        $pdo->commit();

        record_user_log((int) $user['id'], (string) $ticket['group_name'], 'ticket_reply', (string) $ticket['subject']);
        json_response(['ok' => true]);
    }

    if ($action === 'close') {
        $ticketId = (int) ($data['ticket_id'] ?? 0);
        $stmt = $pdo->prepare("UPDATE support_tickets SET status = 'closed' WHERE id = ? AND user_id = ?");
        $stmt->execute([$ticketId, (int) $user['id']]);
        json_response(['ok' => true]);
    }

    json_response(['ok' => false, 'message' => 'Unknown action.'], 400);
} catch (Throwable $th) {
    if (isset($pdo) && $pdo instanceof PDO && $pdo->inTransaction()) {
        $pdo->rollBack();
    }
    json_response(['ok' => false, 'message' => api_error_message($th)], 500);
}

function ticket_payload(array $ticket): array
{
    return [
        'id' => (int) $ticket['id'],
        'group_name' => (string) $ticket['group_name'],
        'subject' => (string) $ticket['subject'],
        'status' => (string) $ticket['status'],
        'status_label' => (string) $ticket['status'] === 'closed' ? '已关闭' : '处理中',
        'last_message' => (string) ($ticket['last_message'] ?? ''),
        'last_message_at' => format_datetime((string) ($ticket['last_message_at'] ?? '')),
        'created_at' => format_datetime((string) $ticket['created_at']),
        'updated_at' => format_datetime((string) $ticket['updated_at']),
    ];
}

function ticket_messages(PDO $pdo, int $ticketId): array
{
    $stmt = $pdo->prepare('SELECT * FROM support_ticket_messages WHERE ticket_id = ? ORDER BY created_at ASC, id ASC');
    $stmt->execute([$ticketId]);

    return array_map(static function (array $message): array {
        return [
            'id' => (int) $message['id'],
            'sender_type' => (string) $message['sender_type'],
            'sender_label' => (string) $message['sender_type'] === 'admin' ? '后台' : '我',
            'message' => (string) $message['message'],
            'created_at' => format_datetime((string) $message['created_at']),
        ];
    }, $stmt->fetchAll());
}
