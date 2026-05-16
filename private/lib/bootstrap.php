<?php

declare(strict_types=1);

require_once __DIR__ . '/../config.php';

date_default_timezone_set('Asia/Shanghai');

ini_set('session.gc_maxlifetime', (string) SESSION_LIFETIME_SECONDS);
session_set_cookie_params([
    'lifetime' => SESSION_LIFETIME_SECONDS,
    'path' => '/',
    'secure' => !empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off',
    'httponly' => true,
    'samesite' => 'Lax',
]);
session_name('family_location_session');
session_start();

function db(): PDO
{
    static $pdo = null;

    if ($pdo instanceof PDO) {
        return $pdo;
    }

    $dsn = sprintf('mysql:host=%s;dbname=%s;charset=%s', DB_HOST, DB_NAME, DB_CHARSET);
    $pdo = new PDO($dsn, DB_USER, DB_PASS, [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_EMULATE_PREPARES => false,
    ]);

    ensure_schema($pdo);

    return $pdo;
}

function redis_client()
{
    static $redis = null;
    static $disabled = false;

    if ($disabled) {
        return null;
    }

    if ($redis instanceof Redis) {
        return $redis;
    }

    if (!redis_cache_configured() || !class_exists('Redis')) {
        $disabled = true;
        return null;
    }

    try {
        $client = new Redis();
        if (!$client->connect((string) REDIS_HOST, (int) REDIS_PORT, 1.0)) {
            throw new RuntimeException('Redis connect failed.');
        }

        $redisUsername = trim((string) REDIS_USERNAME);
        $redisPassword = (string) REDIS_PASSWORD;
        if ($redisUsername !== '' && $redisPassword !== '') {
            if (!$client->auth([$redisUsername, $redisPassword])) {
                throw new RuntimeException('Redis auth failed.');
            }
        } elseif ($redisPassword !== '') {
            if (!$client->auth($redisPassword)) {
                throw new RuntimeException('Redis auth failed.');
            }
        }

        $db = max(0, (int) REDIS_DB);
        if (!$client->select($db)) {
            throw new RuntimeException('Redis select db failed.');
        }

        $redis = $client;
        return $redis;
    } catch (Throwable $error) {
        error_log('[family-location] Redis disabled: ' . $error->getMessage());
        $disabled = true;
        return null;
    }
}

function redis_cache_configured(): bool
{
    return defined('REDIS_HOST')
        && defined('REDIS_PORT')
        && defined('REDIS_DB')
        && defined('REDIS_USERNAME')
        && defined('REDIS_PASSWORD')
        && trim((string) REDIS_HOST) !== ''
        && (int) REDIS_PORT > 0
        && (int) REDIS_DB >= 0;
}

function redis_cache_prefix(): string
{
    return 'family_location:' . hash('sha256', DB_NAME) . ':';
}

function latest_locations_cache_version(): string
{
    $redis = redis_client();
    if (!$redis) {
        return '0';
    }

    $version = $redis->get(redis_cache_prefix() . 'latest_locations_version');
    return is_string($version) && $version !== '' ? $version : '0';
}

function latest_locations_cache_key(string $groupName): string
{
    return redis_cache_prefix()
        . 'latest_locations:'
        . latest_locations_cache_version()
        . ':'
        . hash('sha256', $groupName);
}

function latest_locations_cache_get(string $groupName): ?array
{
    $redis = redis_client();
    if (!$redis) {
        return null;
    }

    $payload = $redis->get(latest_locations_cache_key($groupName));
    if (!is_string($payload) || $payload === '') {
        return null;
    }

    $decoded = json_decode($payload, true);
    return is_array($decoded) ? $decoded : null;
}

function latest_locations_cache_set(string $groupName, array $locations): void
{
    $redis = redis_client();
    if (!$redis) {
        return;
    }

    $ttl = max(1, (int) (defined('REDIS_CACHE_TTL_SECONDS') ? REDIS_CACHE_TTL_SECONDS : 15));
    $redis->setex(
        latest_locations_cache_key($groupName),
        $ttl,
        json_encode($locations, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES)
    );
}

function latest_locations_cache_forget_all(): void
{
    $redis = redis_client();
    if (!$redis) {
        return;
    }

    $redis->incr(redis_cache_prefix() . 'latest_locations_version');
    $redis->incr(redis_cache_prefix() . 'user_history_locations_version');
}

function user_history_locations_cache_version(): string
{
    $redis = redis_client();
    if (!$redis) {
        return '0';
    }

    $version = $redis->get(redis_cache_prefix() . 'user_history_locations_version');
    return is_string($version) && $version !== '' ? $version : '0';
}

function user_history_locations_cache_key(string $groupName, int $userId): string
{
    return redis_cache_prefix()
        . 'user_history_locations:'
        . user_history_locations_cache_version()
        . ':'
        . hash('sha256', $groupName)
        . ':'
        . $userId;
}

function user_history_locations_cache_get(string $groupName, int $userId): ?array
{
    $redis = redis_client();
    if (!$redis) {
        return null;
    }

    $payload = $redis->get(user_history_locations_cache_key($groupName, $userId));
    if (!is_string($payload) || $payload === '') {
        return null;
    }

    $decoded = json_decode($payload, true);
    return is_array($decoded) ? $decoded : null;
}

function user_history_locations_cache_set(string $groupName, int $userId, array $locations): void
{
    $redis = redis_client();
    if (!$redis) {
        return;
    }

    $ttl = max(1, (int) (defined('REDIS_USER_HISTORY_TTL_SECONDS') ? REDIS_USER_HISTORY_TTL_SECONDS : 86400));
    $redis->setex(
        user_history_locations_cache_key($groupName, $userId),
        $ttl,
        json_encode(array_slice($locations, 0, 20), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES)
    );
}

function generate_group_code(PDO $pdo): string
{
    $alphabet = '0123456789abcdefghijklmnopqrstuvwxyz';
    for ($attempt = 0; $attempt < 80; $attempt += 1) {
        $code = '';
        for ($index = 0; $index < 6; $index += 1) {
            $code .= $alphabet[random_int(0, strlen($alphabet) - 1)];
        }

        $stmt = $pdo->prepare('SELECT id FROM family_groups WHERE group_code = ? LIMIT 1');
        $stmt->execute([$code]);
        if (!$stmt->fetch()) {
            return $code;
        }
    }

    throw new RuntimeException('Unable to generate group code.');
}

function ensure_family_group_codes(PDO $pdo): void
{
    $stmt = $pdo->query("SELECT id FROM family_groups WHERE group_code IS NULL OR group_code = ''");
    foreach ($stmt->fetchAll() as $row) {
        $code = generate_group_code($pdo);
        $update = $pdo->prepare('UPDATE family_groups SET group_code = ? WHERE id = ?');
        $update->execute([$code, (int) $row['id']]);
    }
}

function ensure_family_group_owners(PDO $pdo): void
{
    $stmt = $pdo->query("
        SELECT fg.id, first_member.user_id
        FROM family_groups fg
        LEFT JOIN (
            SELECT group_name, MIN(id) AS first_membership_id
            FROM user_groups
            GROUP BY group_name
        ) first_link ON first_link.group_name = fg.group_name
        LEFT JOIN user_groups first_member ON first_member.id = first_link.first_membership_id
        WHERE fg.owner_user_id IS NULL AND first_member.user_id IS NOT NULL
    ");
    $update = $pdo->prepare('UPDATE family_groups SET owner_user_id = ? WHERE id = ? AND owner_user_id IS NULL');
    foreach ($stmt->fetchAll() as $row) {
        $update->execute([(int) $row['user_id'], (int) $row['id']]);
    }
}

function ensure_schema(PDO $pdo): void
{
    static $done = false;

    if ($done) {
        return;
    }

    $pdo->exec("
        CREATE TABLE IF NOT EXISTS users (
            id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            username VARCHAR(64) NOT NULL UNIQUE,
            password_hash VARCHAR(255) NOT NULL,
            display_name VARCHAR(100) NOT NULL DEFAULT '',
            group_name VARCHAR(100) NOT NULL,
            role ENUM('monitor', 'guardian') NOT NULL,
            report_interval_seconds INT UNSIGNED NOT NULL DEFAULT 300,
            is_active TINYINT(1) NOT NULL DEFAULT 1,
            failed_login_count TINYINT UNSIGNED NOT NULL DEFAULT 0,
            login_locked_at DATETIME NULL,
            terms_accepted_at DATETIME NULL,
            user_agreement_accepted_at DATETIME NULL,
            privacy_policy_accepted_at DATETIME NULL,
            cross_border_transfer_accepted_at DATETIME NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            INDEX idx_users_group_role (group_name, role)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    ");

    $pdo->exec("
        CREATE TABLE IF NOT EXISTS family_groups (
            id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            group_name VARCHAR(100) NOT NULL UNIQUE,
            display_name VARCHAR(100) NOT NULL DEFAULT '',
            group_code VARCHAR(6) NULL UNIQUE,
            owner_user_id INT UNSIGNED NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    ");

    $pdo->exec("
        CREATE TABLE IF NOT EXISTS user_groups (
            id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            user_id INT UNSIGNED NOT NULL,
            group_name VARCHAR(100) NOT NULL,
            role ENUM('monitor', 'guardian') NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            UNIQUE KEY uniq_user_group (user_id, group_name),
            INDEX idx_user_groups_group_role (group_name, role),
            CONSTRAINT fk_user_groups_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    ");

    $pdo->exec("
        CREATE TABLE IF NOT EXISTS locations (
            id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            user_id INT UNSIGNED NOT NULL,
            group_name VARCHAR(100) NOT NULL,
            role ENUM('monitor', 'guardian') NOT NULL,
            latitude DECIMAL(10, 7) NOT NULL,
            longitude DECIMAL(10, 7) NOT NULL,
            altitude FLOAT NULL,
            accuracy FLOAT NULL,
            heading FLOAT NULL,
            speed FLOAT NULL,
            location_meta LONGTEXT NULL,
            address_diagnostics LONGTEXT NULL,
            address_mismatch TINYINT(1) NOT NULL DEFAULT 0,
            user_agent VARCHAR(255) NOT NULL DEFAULT '',
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            INDEX idx_locations_group_created (group_name, created_at),
            INDEX idx_locations_user_created (user_id, created_at),
            CONSTRAINT fk_locations_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    ");

    $pdo->exec("
        CREATE TABLE IF NOT EXISTS latest_group_locations (
            user_id INT UNSIGNED NOT NULL,
            group_name VARCHAR(100) NOT NULL,
            role ENUM('monitor', 'guardian') NOT NULL,
            latitude DECIMAL(10, 7) NOT NULL,
            longitude DECIMAL(10, 7) NOT NULL,
            altitude FLOAT NULL,
            accuracy FLOAT NULL,
            heading FLOAT NULL,
            speed FLOAT NULL,
            location_meta LONGTEXT NULL,
            latest_location_id BIGINT UNSIGNED NULL,
            address_diagnostics LONGTEXT NULL,
            address_mismatch TINYINT(1) NOT NULL DEFAULT 0,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (user_id, group_name),
            CONSTRAINT fk_latest_group_locations_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            INDEX idx_latest_location_id (latest_location_id),
            INDEX idx_latest_group_role (group_name, role),
            INDEX idx_latest_updated (updated_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    ");

    add_column_if_missing($pdo, 'users', 'failed_login_count', 'TINYINT UNSIGNED NOT NULL DEFAULT 0');
    add_column_if_missing($pdo, 'users', 'login_locked_at', 'DATETIME NULL');
    add_column_if_missing($pdo, 'users', 'terms_accepted_at', 'DATETIME NULL');
    add_column_if_missing($pdo, 'users', 'user_agreement_accepted_at', 'DATETIME NULL');
    add_column_if_missing($pdo, 'users', 'privacy_policy_accepted_at', 'DATETIME NULL');
    add_column_if_missing($pdo, 'users', 'cross_border_transfer_accepted_at', 'DATETIME NULL');
    add_column_if_missing($pdo, 'users', 'report_interval_seconds', 'INT UNSIGNED NOT NULL DEFAULT ' . DEFAULT_REPORT_INTERVAL_SECONDS);
    add_column_if_missing($pdo, 'family_groups', 'display_name', "VARCHAR(100) NOT NULL DEFAULT ''");
    add_column_if_missing($pdo, 'family_groups', 'group_code', 'VARCHAR(6) NULL UNIQUE');
    add_column_if_missing($pdo, 'family_groups', 'owner_user_id', 'INT UNSIGNED NULL');
    $pdo->exec("UPDATE family_groups SET display_name = group_name WHERE display_name = ''");
    if (table_exists($pdo, 'invite_codes') && column_exists($pdo, 'invite_codes', 'code') && strtolower(column_type($pdo, 'invite_codes', 'code')) !== 'varchar(255)') {
        $pdo->exec('ALTER TABLE invite_codes MODIFY code VARCHAR(255) NOT NULL');
    }
    add_column_if_missing($pdo, 'locations', 'altitude', 'FLOAT NULL');
    add_column_if_missing($pdo, 'locations', 'location_meta', 'LONGTEXT NULL');
    add_column_if_missing($pdo, 'locations', 'address_diagnostics', 'LONGTEXT NULL');
    add_column_if_missing($pdo, 'locations', 'address_mismatch', 'TINYINT(1) NOT NULL DEFAULT 0');
    add_column_if_missing($pdo, 'latest_group_locations', 'latest_location_id', 'BIGINT UNSIGNED NULL');
    add_column_if_missing($pdo, 'latest_group_locations', 'altitude', 'FLOAT NULL');
    add_column_if_missing($pdo, 'latest_group_locations', 'location_meta', 'LONGTEXT NULL');
    add_column_if_missing($pdo, 'latest_group_locations', 'address_diagnostics', 'LONGTEXT NULL');
    add_column_if_missing($pdo, 'latest_group_locations', 'address_mismatch', 'TINYINT(1) NOT NULL DEFAULT 0');
    $pdo->exec('
        UPDATE users
        SET
            user_agreement_accepted_at = COALESCE(user_agreement_accepted_at, terms_accepted_at),
            privacy_policy_accepted_at = COALESCE(privacy_policy_accepted_at, terms_accepted_at)
        WHERE terms_accepted_at IS NOT NULL
    ');
    migrate_role_columns($pdo);

    $pdo->exec("
        INSERT IGNORE INTO family_groups (group_name)
        SELECT DISTINCT group_name
        FROM users
        WHERE group_name <> ''
    ");

    $pdo->exec("
        INSERT IGNORE INTO user_groups (user_id, group_name, role)
        SELECT id, group_name, role
        FROM users
        WHERE group_name <> ''
    ");

    $pdo->exec("
        INSERT IGNORE INTO family_groups (group_name)
        SELECT DISTINCT group_name
        FROM user_groups
        WHERE group_name <> ''
    ");
    ensure_family_group_codes($pdo);
    ensure_family_group_owners($pdo);

    if (table_exists($pdo, 'latest_locations')) {
        $pdo->exec("
            INSERT IGNORE INTO latest_group_locations
                (user_id, group_name, role, latitude, longitude, accuracy, heading, speed, updated_at)
            SELECT
                user_id,
                group_name,
                CASE WHEN role = 'parent' THEN 'monitor' ELSE role END,
                latitude,
                longitude,
                accuracy,
                heading,
                speed,
                updated_at
            FROM latest_locations
        ");
    }

    $pdo->exec("
        CREATE TABLE IF NOT EXISTS announcements (
            id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            title VARCHAR(120) NOT NULL DEFAULT '',
            body TEXT NOT NULL,
            is_active TINYINT(1) NOT NULL DEFAULT 1,
            version INT UNSIGNED NOT NULL DEFAULT 1,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            INDEX idx_announcements_active_updated (is_active, updated_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    ");

    $pdo->exec("
        CREATE TABLE IF NOT EXISTS invite_codes (
            id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            code VARCHAR(255) NOT NULL UNIQUE,
            note VARCHAR(120) NOT NULL DEFAULT '',
            invite_type ENUM('invite', 'group_create') NOT NULL DEFAULT 'invite',
            max_uses INT UNSIGNED NOT NULL DEFAULT 1,
            used_count INT UNSIGNED NOT NULL DEFAULT 0,
            assigned_group_name VARCHAR(100) NULL,
            is_active TINYINT(1) NOT NULL DEFAULT 1,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            INDEX idx_invite_codes_active (is_active, invite_type)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    ");
    add_column_if_missing($pdo, 'invite_codes', 'note', "VARCHAR(120) NOT NULL DEFAULT ''");

    $done = true;
}

function create_family_group_record(PDO $pdo, string $displayName, ?int $ownerUserId = null): array
{
    $displayName = trim($displayName);
    if ($displayName === '') {
        throw new RuntimeException('家庭组名称不能为空。');
    }

    $code = generate_group_code($pdo);
    $groupName = family_group_internal_name($pdo, $displayName, $code);
    $stmt = $pdo->prepare('INSERT INTO family_groups (group_name, display_name, group_code, owner_user_id) VALUES (?, ?, ?, ?)');
    $stmt->execute([$groupName, $displayName, $code, $ownerUserId]);

    $stmt = $pdo->prepare('SELECT * FROM family_groups WHERE group_name = ? LIMIT 1');
    $stmt->execute([$groupName]);
    $group = $stmt->fetch();
    if (!$group) {
        throw new RuntimeException('家庭组创建失败。');
    }

    return $group;
}

function family_group_internal_name(PDO $pdo, string $displayName, string $code): string
{
    $base = $displayName;
    if (function_exists('mb_strlen') && mb_strlen($base, 'UTF-8') > 93) {
        $base = mb_substr($base, 0, 93, 'UTF-8');
    } elseif (!function_exists('mb_strlen') && strlen($base) > 93) {
        $base = substr($base, 0, 93);
    }

    $candidate = $base;
    $stmt = $pdo->prepare('SELECT id FROM family_groups WHERE group_name = ? LIMIT 1');
    $stmt->execute([$candidate]);
    if (!$stmt->fetch()) {
        return $candidate;
    }

    return $base . '#' . $code;
}

function ensure_family_group_record(PDO $pdo, string $groupName, ?int $ownerUserId = null): array
{
    $groupName = trim($groupName);
    if ($groupName === '') {
        throw new RuntimeException('家庭组名称不能为空。');
    }

    $stmt = $pdo->prepare('INSERT IGNORE INTO family_groups (group_name, display_name, group_code, owner_user_id) VALUES (?, ?, ?, ?)');
    $stmt->execute([$groupName, $groupName, generate_group_code($pdo), $ownerUserId]);

    $stmt = $pdo->prepare('SELECT * FROM family_groups WHERE group_name = ? LIMIT 1');
    $stmt->execute([$groupName]);
    $group = $stmt->fetch();
    if (!$group) {
        throw new RuntimeException('家庭组不存在。');
    }

    if (empty($group['group_code'])) {
        $code = generate_group_code($pdo);
        $update = $pdo->prepare('UPDATE family_groups SET group_code = ? WHERE id = ?');
        $update->execute([$code, (int) $group['id']]);
        $group['group_code'] = $code;
    }

    if (empty($group['display_name'])) {
        $update = $pdo->prepare('UPDATE family_groups SET display_name = ? WHERE id = ?');
        $update->execute([$groupName, (int) $group['id']]);
        $group['display_name'] = $groupName;
    }

    if ($ownerUserId !== null && empty($group['owner_user_id'])) {
        $update = $pdo->prepare('UPDATE family_groups SET owner_user_id = ? WHERE id = ?');
        $update->execute([$ownerUserId, (int) $group['id']]);
        $group['owner_user_id'] = $ownerUserId;
    }

    return $group;
}

function table_exists(PDO $pdo, string $table): bool
{
    $stmt = $pdo->prepare("
        SELECT COUNT(*)
        FROM INFORMATION_SCHEMA.TABLES
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = ?
    ");
    $stmt->execute([$table]);

    return (int) $stmt->fetchColumn() > 0;
}

function column_exists(PDO $pdo, string $table, string $column): bool
{
    $stmt = $pdo->prepare("
        SELECT COUNT(*)
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = ?
          AND COLUMN_NAME = ?
    ");
    $stmt->execute([$table, $column]);

    return (int) $stmt->fetchColumn() > 0;
}

function column_type(PDO $pdo, string $table, string $column): string
{
    $stmt = $pdo->prepare("
        SELECT COLUMN_TYPE
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = ?
          AND COLUMN_NAME = ?
        LIMIT 1
    ");
    $stmt->execute([$table, $column]);

    return (string) ($stmt->fetchColumn() ?: '');
}

function migrate_role_columns(PDO $pdo): void
{
    foreach (['users', 'user_groups', 'locations', 'latest_group_locations', 'latest_locations'] as $table) {
        migrate_role_column($pdo, $table);
    }
}

function migrate_role_column(PDO $pdo, string $table): void
{
    if (!table_exists($pdo, $table) || !column_exists($pdo, $table, 'role')) {
        return;
    }

    $type = strtolower(column_type($pdo, $table, 'role'));
    if (str_contains($type, "'parent'")) {
        $pdo->exec(sprintf("ALTER TABLE `%s` MODIFY `role` ENUM('parent', 'monitor', 'guardian') NOT NULL", $table));
        $pdo->exec(sprintf("UPDATE `%s` SET `role` = 'monitor' WHERE `role` = 'parent'", $table));
    }

    $type = strtolower(column_type($pdo, $table, 'role'));
    if ($type !== "enum('monitor','guardian')" && $type !== "enum('monitor', 'guardian')") {
        $pdo->exec(sprintf("ALTER TABLE `%s` MODIFY `role` ENUM('monitor', 'guardian') NOT NULL", $table));
    }
}

function add_column_if_missing(PDO $pdo, string $table, string $column, string $definition): void
{
    assert_safe_identifier($table);
    assert_safe_identifier($column);

    $stmt = $pdo->prepare("
        SELECT COUNT(*)
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = ?
          AND COLUMN_NAME = ?
    ");
    $stmt->execute([$table, $column]);

    if ((int) $stmt->fetchColumn() > 0) {
        return;
    }

    $pdo->exec(sprintf('ALTER TABLE `%s` ADD COLUMN `%s` %s', $table, $column, $definition));
}

function assert_safe_identifier(string $identifier): void
{
    if (!preg_match('/^[A-Za-z0-9_]+$/', $identifier)) {
        throw new RuntimeException('Unsafe SQL identifier.');
    }
}

function e(string $value): string
{
    return htmlspecialchars($value, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
}

function redirect(string $url): never
{
    header('Location: ' . $url);
    exit;
}

function admin_base_path(): string
{
    if (!defined('ADMIN_PATH')) {
        throw new RuntimeException('ADMIN_PATH is not configured.');
    }

    $path = trim((string) ADMIN_PATH, " \t\n\r\0\x0B/");
    if ($path === '' || !preg_match('/^[A-Za-z0-9_-]+$/', $path)) {
        throw new RuntimeException('ADMIN_PATH must contain only letters, numbers, underscores or hyphens.');
    }

    return $path;
}

function admin_source_dir(): string
{
    if (!defined('ADMIN_SOURCE_DIR')) {
        throw new RuntimeException('ADMIN_SOURCE_DIR is not configured.');
    }

    $dir = trim((string) ADMIN_SOURCE_DIR, " \t\n\r\0\x0B/");
    if ($dir === '' || !preg_match('/^[A-Za-z0-9_-]+$/', $dir)) {
        throw new RuntimeException('ADMIN_SOURCE_DIR must contain only letters, numbers, underscores or hyphens.');
    }

    return $dir;
}

function admin_url_path(): string
{
    return admin_base_path() . '/';
}

function require_admin_path(): void
{
    $configured = '/' . admin_base_path();
    $requestPath = (string) (parse_url((string) ($_SERVER['REQUEST_URI'] ?? ''), PHP_URL_PATH) ?: '');
    $scriptPath = (string) ($_SERVER['SCRIPT_NAME'] ?? '');

    if (path_matches_admin_base($requestPath, $configured) || path_matches_admin_base($scriptPath, $configured)) {
        return;
    }

    http_response_code(404);
    exit('Not found.');
}

function path_matches_admin_base(string $path, string $configured): bool
{
    return $path === $configured || str_starts_with($path, $configured . '/');
}

function csrf_token(): string
{
    if (empty($_SESSION['csrf_token'])) {
        $_SESSION['csrf_token'] = bin2hex(random_bytes(32));
    }

    return $_SESSION['csrf_token'];
}

function require_csrf(): void
{
    $token = $_POST['csrf_token'] ?? '';
    if (!is_string($token) || !hash_equals(csrf_token(), $token)) {
        http_response_code(400);
        exit('CSRF token invalid.');
    }
}

function is_admin_logged_in(): bool
{
    return !empty($_SESSION['admin_logged_in']);
}

function require_admin(): void
{
    if (!is_admin_logged_in()) {
        redirect('/');
    }
}

function current_user(): ?array
{
    if (empty($_SESSION['user_id'])) {
        return null;
    }

    $stmt = db()->prepare('SELECT * FROM users WHERE id = ? AND is_active = 1');
    $stmt->execute([(int) $_SESSION['user_id']]);
    $user = $stmt->fetch();

    return $user ?: null;
}

function require_user(): array
{
    require_app_user_agent();

    $user = current_user();
    if (!$user) {
        json_response(['ok' => false, 'message' => '请先登录。'], 401);
    }

    require_terms_accepted($user);

    return $user;
}

function require_terms_accepted(array $user): void
{
    if (user_terms_accepted($user) && user_cross_border_transfer_accepted($user)) {
        return;
    }

    json_response(['ok' => false, 'message' => '请先同意用户协议、隐私条约和用户数据跨境加密传输协议。'], 403);
}

function user_terms_accepted(array $user): bool
{
    return user_agreement_accepted($user) && user_privacy_policy_accepted($user);
}

function user_agreement_accepted(array $user): bool
{
    return !empty($user['user_agreement_accepted_at']) || !empty($user['terms_accepted_at']);
}

function user_privacy_policy_accepted(array $user): bool
{
    return !empty($user['privacy_policy_accepted_at']) || !empty($user['terms_accepted_at']);
}

function user_cross_border_transfer_accepted(array $user): bool
{
    return !empty($user['cross_border_transfer_accepted_at']);
}

function require_app_user_agent(): void
{
    $ua = (string) ($_SERVER['HTTP_USER_AGENT'] ?? '');
    if (stripos($ua, APP_USER_AGENT_TOKEN) === false) {
        json_response(['ok' => false, 'message' => 'Only loc-app client is allowed.'], 403);
    }
}

function require_loc_app_page(): void
{
    $ua = (string) ($_SERVER['HTTP_USER_AGENT'] ?? '');
    if (stripos($ua, APP_USER_AGENT_TOKEN) !== false) {
        return;
    }

    http_response_code(403);
    exit('Forbidden.');
}

function require_report_device_cookie(): string
{
    $cookieName = defined('APP_DEVICE_COOKIE_NAME') ? APP_DEVICE_COOKIE_NAME : 'loc_device';
    $deviceCookie = (string) ($_COOKIE[$cookieName] ?? '');

    if (!preg_match('/^[a-f0-9]{64}$/i', $deviceCookie)) {
        json_response(['ok' => false, 'message' => '请使用新版 App 上报位置。'], 403);
    }

    return strtolower($deviceCookie);
}

function json_response(array $data, int $status = 200): never
{
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function post_string(string $key, int $maxLength = 255): string
{
    $value = trim((string) ($_POST[$key] ?? ''));

    if (function_exists('mb_strlen') && mb_strlen($value, 'UTF-8') > $maxLength) {
        $value = mb_substr($value, 0, $maxLength, 'UTF-8');
    } elseif (!function_exists('mb_strlen') && strlen($value) > $maxLength * 4) {
        $value = substr($value, 0, $maxLength * 4);
    }

    return $value;
}

function request_data(): array
{
    static $data = null;

    if (is_array($data)) {
        return $data;
    }

    $contentType = (string) ($_SERVER['CONTENT_TYPE'] ?? '');
    if (stripos($contentType, 'application/json') !== false) {
        $raw = file_get_contents('php://input');
        $decoded = json_decode($raw === false ? '' : $raw, true);
        $data = is_array($decoded) ? $decoded : [];
        return $data;
    }

    $data = $_POST;
    return $data;
}

function input_string(string $key, int $maxLength = 255): string
{
    $data = request_data();
    $value = trim((string) ($data[$key] ?? ''));

    if (function_exists('mb_strlen') && mb_strlen($value, 'UTF-8') > $maxLength) {
        $value = mb_substr($value, 0, $maxLength, 'UTF-8');
    } elseif (!function_exists('mb_strlen') && strlen($value) > $maxLength * 4) {
        $value = substr($value, 0, $maxLength * 4);
    }

    return $value;
}

function input_float(string $key): ?float
{
    $data = request_data();
    if (!isset($data[$key]) || $data[$key] === '') {
        return null;
    }

    if (!is_numeric($data[$key])) {
        return null;
    }

    return (float) $data[$key];
}

function input_bool(string $key): bool
{
    $data = request_data();
    $value = $data[$key] ?? false;

    if (is_bool($value)) {
        return $value;
    }

    if (is_numeric($value)) {
        return (int) $value === 1;
    }

    return in_array(strtolower(trim((string) $value)), ['1', 'true', 'yes', 'on'], true);
}

function format_datetime(?string $value): string
{
    if (!$value) {
        return '';
    }

    return date('Y-m-d H:i:s', strtotime($value));
}

function role_label(string $role): string
{
    return normalize_role($role) === 'monitor' ? '监测端' : '监护端';
}

function normalize_role(string $role): string
{
    return $role === 'parent' ? 'monitor' : $role;
}

function is_valid_role(string $role): bool
{
    return in_array(normalize_role($role), ['monitor', 'guardian'], true);
}

function group_payload(array $group): array
{
    return [
        'id' => (int) ($group['id'] ?? 0),
        'group_name' => $group['group_name'],
        'display_name' => $group['group_display_name'] ?? ($group['display_name'] ?? $group['group_name']),
        'group_code' => $group['group_code'] ?? '',
        'owner_user_id' => isset($group['owner_user_id']) ? (int) $group['owner_user_id'] : 0,
        'role' => normalize_role((string) $group['role']),
        'role_label' => role_label((string) $group['role']),
    ];
}

function location_payload(?array $row): ?array
{
    if (!$row) {
        return null;
    }

    $diagnostics = null;
    if (!empty($row['address_diagnostics'])) {
        $decoded = json_decode((string) $row['address_diagnostics'], true);
        $diagnostics = is_array($decoded) ? $decoded : null;
    }

    return [
        'user_id' => (int) $row['user_id'],
        'username' => $row['username'],
        'display_name' => $row['display_name'],
        'role' => normalize_role((string) $row['role']),
        'role_label' => role_label($row['role']),
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
        'updated_at' => format_datetime($row['updated_at']),
        'is_stale' => strtotime((string) $row['updated_at']) < time() - LOCATION_STALE_SECONDS,
    ];
}

function api_error_message(Throwable $error): string
{
    error_log('[family-location] ' . $error::class . ': ' . $error->getMessage());
    return '服务器错误，请稍后重试。';
}

function public_user_payload(array $user): array
{
    $groups = user_groups_for_user((int) $user['id']);
    $membership = $groups[0] ?? null;

    return [
        'id' => (int) $user['id'],
        'username' => $user['username'],
        'display_name' => $user['display_name'],
        'group_name' => $membership['group_name'] ?? '',
        'role' => $membership ? normalize_role((string) $membership['role']) : '',
        'role_label' => $membership ? role_label((string) $membership['role']) : '',
        'terms_accepted' => user_terms_accepted($user),
        'cross_border_transfer_accepted' => user_cross_border_transfer_accepted($user),
        'groups' => array_map('group_payload', $groups),
        'report_interval_seconds' => user_report_interval_seconds($user),
    ];
}

function public_user_payload_for_group(array $user, array $membership): array
{
    $payload = public_user_payload($user);
    $payload['group_name'] = $membership['group_name'];
    $payload['role'] = normalize_role((string) $membership['role']);
    $payload['role_label'] = role_label((string) $membership['role']);

    return $payload;
}

function normalize_report_interval_seconds(int $seconds): int
{
    return max(MIN_REPORT_INTERVAL_SECONDS, min(MAX_REPORT_INTERVAL_SECONDS, $seconds));
}

function user_report_interval_seconds(array $user): int
{
    return normalize_report_interval_seconds((int) ($user['report_interval_seconds'] ?? DEFAULT_REPORT_INTERVAL_SECONDS));
}

function user_groups_for_user(int $userId): array
{
    $stmt = db()->prepare('
        SELECT
            ug.id,
            ug.user_id,
            ug.group_name,
            ug.role,
            fg.group_code,
            fg.display_name AS group_display_name,
            fg.owner_user_id
        FROM user_groups ug
        LEFT JOIN family_groups fg ON fg.group_name = ug.group_name
        WHERE ug.user_id = ?
        ORDER BY ug.group_name ASC, ug.id ASC
    ');
    $stmt->execute([$userId]);

    return $stmt->fetchAll();
}

function selected_group_name_from_request(): string
{
    $data = request_data();
    $groupName = trim((string) ($data['group_name'] ?? ($_GET['group_name'] ?? '')));

    if (function_exists('mb_strlen') && mb_strlen($groupName, 'UTF-8') > 100) {
        return mb_substr($groupName, 0, 100, 'UTF-8');
    }

    if (!function_exists('mb_strlen') && strlen($groupName) > 400) {
        return substr($groupName, 0, 400);
    }

    return $groupName;
}

function user_membership_for_group(array $user, string $groupName = ''): ?array
{
    $groups = user_groups_for_user((int) $user['id']);

    if ($groupName === '') {
        return $groups[0] ?? null;
    }

    foreach ($groups as $group) {
        if (hash_equals((string) $group['group_name'], $groupName)) {
            return $group;
        }
    }

    return null;
}

function require_user_membership(array $user, string $groupName = ''): array
{
    $membership = user_membership_for_group($user, $groupName);

    if ($membership) {
        return $membership;
    }

    if ($groupName === '') {
        json_response(['ok' => false, 'message' => '账号还没有家庭组。'], 409);
    }

    json_response(['ok' => false, 'message' => '无权访问该家庭组。'], 403);
}

function is_login_locked(array $user): bool
{
    if (empty($user['login_locked_at'])) {
        return false;
    }

    return strtotime((string) $user['login_locked_at']) > time() - LOGIN_LOCK_SECONDS;
}

function unlock_if_expired(PDO $pdo, array &$user): void
{
    if (empty($user['login_locked_at'])) {
        return;
    }

    if (strtotime((string) $user['login_locked_at']) > time() - LOGIN_LOCK_SECONDS) {
        return;
    }

    clear_failed_login($pdo, (int) $user['id']);
    $user['failed_login_count'] = 0;
    $user['login_locked_at'] = null;
}

function record_failed_login(PDO $pdo, array $user): void
{
    $failedCount = (int) ($user['failed_login_count'] ?? 0) + 1;

    if ($failedCount >= MAX_LOGIN_FAILURES) {
        $stmt = $pdo->prepare('
            UPDATE users
            SET failed_login_count = ?,
                login_locked_at = COALESCE(login_locked_at, ?)
            WHERE id = ?
        ');
        $stmt->execute([$failedCount, date('Y-m-d H:i:s'), (int) $user['id']]);
        return;
    }

    $stmt = $pdo->prepare('UPDATE users SET failed_login_count = ? WHERE id = ?');
    $stmt->execute([$failedCount, (int) $user['id']]);
}

function clear_failed_login(PDO $pdo, int $userId): void
{
    $stmt = $pdo->prepare('UPDATE users SET failed_login_count = 0, login_locked_at = NULL WHERE id = ?');
    $stmt->execute([$userId]);
}

function latest_locations_for_group(string $groupName): array
{
    $cached = latest_locations_cache_get($groupName);
    if (is_array($cached)) {
        return $cached;
    }

    $stmt = db()->prepare("
        SELECT
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
            ll.updated_at,
            u.username,
            u.display_name
        FROM latest_group_locations ll
        INNER JOIN users u ON u.id = ll.user_id
        INNER JOIN user_groups ug ON ug.user_id = ll.user_id AND ug.group_name = ll.group_name
        WHERE ll.group_name = ? AND u.is_active = 1
        ORDER BY ug.role ASC, u.username ASC
    ");
    $stmt->execute([$groupName]);

    $locations = [];
    foreach ($stmt->fetchAll() as $row) {
        $locations[] = location_payload($row);
    }

    latest_locations_cache_set($groupName, $locations);

    return $locations;
}
