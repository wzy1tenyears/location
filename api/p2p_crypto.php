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
    $action = trim((string) ($data['action'] ?? 'status'));
    $membership = require_user_membership($user, selected_group_name_from_request());
    $groupName = (string) $membership['group_name'];
    $pdo = db();

    if ($action === 'publish_key') {
        $publicKey = normalize_public_jwk($data['public_key_jwk'] ?? null);
        $stmt = $pdo->prepare('
            INSERT INTO p2p_user_keys (user_id, public_key_jwk)
            VALUES (?, ?)
            ON DUPLICATE KEY UPDATE public_key_jwk = VALUES(public_key_jwk), updated_at = NOW()
        ');
        $stmt->execute([(int) $user['id'], $publicKey]);
        record_user_log((int) $user['id'], $groupName, 'p2p_public_key_update', '更新端到端加密公钥');
        json_response(p2p_status_payload($pdo, $user, $groupName));
    }

    if ($action === 'consent') {
        $consent = !empty($data['consent']);
        $stmt = $pdo->prepare('
            INSERT INTO p2p_group_members (group_name, user_id, consent_at)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE consent_at = VALUES(consent_at), updated_at = NOW()
        ');
        $stmt->execute([$groupName, (int) $user['id'], $consent ? date('Y-m-d H:i:s') : null]);
        record_user_log((int) $user['id'], $groupName, $consent ? 'p2p_consent' : 'p2p_consent_revoke', $consent ? '同意端到端加密' : '取消端到端加密同意');
        json_response(p2p_status_payload($pdo, $user, $groupName));
    }

    if ($action === 'enable_group') {
        $group = p2p_require_group_initiator($user, $groupName);
        if (!empty($group['p2p_enabled_at'])) {
            json_response(['ok' => false, 'message' => '该家庭组已经开启端到端加密。'], 409);
        }

        $keyVersion = max(1, (int) ($data['key_version'] ?? time()));
        $wrappedKeys = is_array($data['wrapped_keys'] ?? null) ? $data['wrapped_keys'] : [];
        $members = p2p_group_member_rows($pdo, $groupName);
        if (!$members) {
            json_response(['ok' => false, 'message' => '家庭组没有可用成员。'], 422);
        }

        foreach ($members as $member) {
            $memberId = (int) $member['user_id'];
            if (empty($member['public_key_jwk']) || empty($member['consent_at'])) {
                json_response(['ok' => false, 'message' => '需要组内所有成员先在各自 App 内同意并生成密钥。'], 409);
            }
            if (!p2p_valid_wrapped_key($wrappedKeys[(string) $memberId] ?? null)) {
                json_response(['ok' => false, 'message' => '组密钥分发数据不完整。'], 422);
            }
        }

        $pdo->beginTransaction();
        $stmt = $pdo->prepare('
            UPDATE family_groups
            SET p2p_enabled_at = NOW(),
                p2p_enabled_by = ?,
                p2p_key_version = ?
            WHERE group_name = ?
        ');
        $stmt->execute([(int) $user['id'], $keyVersion, $groupName]);

        $stmt = $pdo->prepare('
            INSERT INTO p2p_group_members (group_name, user_id, wrapped_group_key, key_version)
            VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                wrapped_group_key = VALUES(wrapped_group_key),
                key_version = VALUES(key_version),
                updated_at = NOW()
        ');
        foreach ($members as $member) {
            $memberId = (int) $member['user_id'];
            $stmt->execute([$groupName, $memberId, (string) $wrappedKeys[(string) $memberId], $keyVersion]);
        }

        $pdo->commit();
        latest_locations_cache_forget_all();
        record_user_log((int) $user['id'], $groupName, 'p2p_enable', '开启家庭组端到端加密', [
            'key_version' => $keyVersion,
        ]);
        json_response(p2p_status_payload($pdo, $user, $groupName));
    }

    json_response(p2p_status_payload($pdo, $user, $groupName));
} catch (Throwable $th) {
    if (isset($pdo) && $pdo instanceof PDO && $pdo->inTransaction()) {
        $pdo->rollBack();
    }
    json_response(['ok' => false, 'message' => api_error_message($th)], 500);
}

function normalize_public_jwk(mixed $value): string
{
    if (!is_array($value)) {
        json_response(['ok' => false, 'message' => '公钥格式不正确。'], 422);
    }

    $required = ['kty', 'key_ops', 'ext', 'n', 'e'];
    foreach ($required as $field) {
        if (!array_key_exists($field, $value)) {
            json_response(['ok' => false, 'message' => '公钥字段不完整。'], 422);
        }
    }

    if (($value['kty'] ?? '') !== 'RSA') {
        json_response(['ok' => false, 'message' => '仅支持 RSA-OAEP 公钥。'], 422);
    }

    $json = json_encode($value, JSON_UNESCAPED_SLASHES);
    if (!is_string($json) || strlen($json) > 12000) {
        json_response(['ok' => false, 'message' => '公钥数据过大。'], 422);
    }

    return $json;
}

function p2p_valid_wrapped_key(mixed $value): bool
{
    return is_string($value)
        && strlen($value) >= 80
        && strlen($value) <= 20000
        && preg_match('/^[A-Za-z0-9+\/=_-]+$/', $value) === 1;
}

function p2p_group_member_rows(PDO $pdo, string $groupName): array
{
    $stmt = $pdo->prepare('
        SELECT
            u.id AS user_id,
            u.username,
            u.display_name,
            ug.role,
            puk.public_key_jwk,
            pgm.consent_at,
            pgm.wrapped_group_key,
            pgm.key_version
        FROM user_groups ug
        INNER JOIN users u ON u.id = ug.user_id
        LEFT JOIN p2p_user_keys puk ON puk.user_id = u.id
        LEFT JOIN p2p_group_members pgm ON pgm.group_name = ug.group_name AND pgm.user_id = u.id
        WHERE ug.group_name = ? AND u.is_active = 1
        ORDER BY ug.role ASC, u.username ASC
    ');
    $stmt->execute([$groupName]);

    return $stmt->fetchAll();
}

function p2p_status_payload(PDO $pdo, array $user, string $groupName): array
{
    $stmt = $pdo->prepare('SELECT * FROM family_groups WHERE group_name = ? LIMIT 1');
    $stmt->execute([$groupName]);
    $group = $stmt->fetch() ?: [];
    $members = p2p_group_member_rows($pdo, $groupName);

    $userWrappedKey = null;
    foreach ($members as $member) {
        if ((int) $member['user_id'] === (int) $user['id']) {
            $userWrappedKey = $member['wrapped_group_key'] ?: null;
            break;
        }
    }

    return [
        'ok' => true,
        'group_name' => $groupName,
        'enabled' => !empty($group['p2p_enabled_at']),
        'key_version' => (int) ($group['p2p_key_version'] ?? 0),
        'is_owner' => empty($group['owner_user_id']) || (int) ($group['owner_user_id'] ?? 0) === (int) $user['id'],
        'wrapped_group_key' => $userWrappedKey,
        'members' => array_map(static function (array $member): array {
            $publicKey = null;
            if (!empty($member['public_key_jwk'])) {
                $decoded = json_decode((string) $member['public_key_jwk'], true);
                $publicKey = is_array($decoded) ? $decoded : null;
            }

            return [
                'user_id' => (int) $member['user_id'],
                'username' => (string) $member['username'],
                'display_name' => (string) $member['display_name'],
                'role' => normalize_role((string) $member['role']),
                'role_label' => role_label((string) $member['role']),
                'has_public_key' => $publicKey !== null,
                'consented' => !empty($member['consent_at']),
                'public_key_jwk' => $publicKey,
            ];
        }, $members),
    ];
}

function p2p_require_group_initiator(array $user, string $groupName): array
{
    $membership = require_user_membership($user, $groupName);
    $stmt = db()->prepare('SELECT * FROM family_groups WHERE group_name = ? LIMIT 1');
    $stmt->execute([(string) $membership['group_name']]);
    $group = $stmt->fetch();
    if (!$group) {
        json_response(['ok' => false, 'message' => '家庭组不存在。'], 404);
    }

    $ownerUserId = (int) ($group['owner_user_id'] ?? 0);
    if ($ownerUserId > 0 && $ownerUserId !== (int) $user['id']) {
        json_response(['ok' => false, 'message' => '只有家庭组管理员可以开启端到端加密。'], 403);
    }

    return $group;
}
