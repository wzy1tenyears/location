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
            disabled_reason VARCHAR(255) NOT NULL DEFAULT '',
            failed_login_count TINYINT UNSIGNED NOT NULL DEFAULT 0,
            login_locked_at DATETIME NULL,
            terms_accepted_at DATETIME NULL,
            user_agreement_accepted_at DATETIME NULL,
            privacy_policy_accepted_at DATETIME NULL,
            cross_border_transfer_accepted_at DATETIME NULL,
            environment_data_consent_at DATETIME NULL,
            debug_mode TINYINT(1) NOT NULL DEFAULT 0,
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
            p2p_enabled_at DATETIME NULL,
            p2p_enabled_by INT UNSIGNED NULL,
            p2p_key_version INT UNSIGNED NOT NULL DEFAULT 0,
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
            encryption_mode VARCHAR(20) NOT NULL DEFAULT '',
            encrypted_payload LONGTEXT NULL,
            p2p_key_version INT UNSIGNED NOT NULL DEFAULT 0,
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
            encryption_mode VARCHAR(20) NOT NULL DEFAULT '',
            encrypted_payload LONGTEXT NULL,
            p2p_key_version INT UNSIGNED NOT NULL DEFAULT 0,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (user_id, group_name),
            CONSTRAINT fk_latest_group_locations_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            INDEX idx_latest_location_id (latest_location_id),
            INDEX idx_latest_group_role (group_name, role),
            INDEX idx_latest_updated (updated_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    ");

    add_column_if_missing($pdo, 'users', 'failed_login_count', 'TINYINT UNSIGNED NOT NULL DEFAULT 0');
    add_column_if_missing($pdo, 'users', 'disabled_reason', "VARCHAR(255) NOT NULL DEFAULT ''");
    add_column_if_missing($pdo, 'users', 'login_locked_at', 'DATETIME NULL');
    add_column_if_missing($pdo, 'users', 'terms_accepted_at', 'DATETIME NULL');
    add_column_if_missing($pdo, 'users', 'user_agreement_accepted_at', 'DATETIME NULL');
    add_column_if_missing($pdo, 'users', 'privacy_policy_accepted_at', 'DATETIME NULL');
    add_column_if_missing($pdo, 'users', 'cross_border_transfer_accepted_at', 'DATETIME NULL');
    add_column_if_missing($pdo, 'users', 'environment_data_consent_at', 'DATETIME NULL');
    add_column_if_missing($pdo, 'users', 'debug_mode', 'TINYINT(1) NOT NULL DEFAULT 0');
    add_column_if_missing($pdo, 'users', 'report_interval_seconds', 'INT UNSIGNED NOT NULL DEFAULT ' . DEFAULT_REPORT_INTERVAL_SECONDS);
    add_column_if_missing($pdo, 'family_groups', 'display_name', "VARCHAR(100) NOT NULL DEFAULT ''");
    add_column_if_missing($pdo, 'family_groups', 'group_code', 'VARCHAR(6) NULL UNIQUE');
    add_column_if_missing($pdo, 'family_groups', 'owner_user_id', 'INT UNSIGNED NULL');
    add_column_if_missing($pdo, 'family_groups', 'p2p_enabled_at', 'DATETIME NULL');
    add_column_if_missing($pdo, 'family_groups', 'p2p_enabled_by', 'INT UNSIGNED NULL');
    add_column_if_missing($pdo, 'family_groups', 'p2p_key_version', 'INT UNSIGNED NOT NULL DEFAULT 0');
    $pdo->exec("UPDATE family_groups SET display_name = group_name WHERE display_name = ''");
    if (table_exists($pdo, 'invite_codes') && column_exists($pdo, 'invite_codes', 'code') && strtolower(column_type($pdo, 'invite_codes', 'code')) !== 'varchar(255)') {
        $pdo->exec('ALTER TABLE invite_codes MODIFY code VARCHAR(255) NOT NULL');
    }
    add_column_if_missing($pdo, 'locations', 'altitude', 'FLOAT NULL');
    add_column_if_missing($pdo, 'locations', 'location_meta', 'LONGTEXT NULL');
    add_column_if_missing($pdo, 'locations', 'address_diagnostics', 'LONGTEXT NULL');
    add_column_if_missing($pdo, 'locations', 'address_mismatch', 'TINYINT(1) NOT NULL DEFAULT 0');
    add_column_if_missing($pdo, 'locations', 'encryption_mode', "VARCHAR(20) NOT NULL DEFAULT ''");
    add_column_if_missing($pdo, 'locations', 'encrypted_payload', 'LONGTEXT NULL');
    add_column_if_missing($pdo, 'locations', 'p2p_key_version', 'INT UNSIGNED NOT NULL DEFAULT 0');
    add_column_if_missing($pdo, 'latest_group_locations', 'latest_location_id', 'BIGINT UNSIGNED NULL');
    add_column_if_missing($pdo, 'latest_group_locations', 'altitude', 'FLOAT NULL');
    add_column_if_missing($pdo, 'latest_group_locations', 'location_meta', 'LONGTEXT NULL');
    add_column_if_missing($pdo, 'latest_group_locations', 'address_diagnostics', 'LONGTEXT NULL');
    add_column_if_missing($pdo, 'latest_group_locations', 'address_mismatch', 'TINYINT(1) NOT NULL DEFAULT 0');
    add_column_if_missing($pdo, 'latest_group_locations', 'encryption_mode', "VARCHAR(20) NOT NULL DEFAULT ''");
    add_column_if_missing($pdo, 'latest_group_locations', 'encrypted_payload', 'LONGTEXT NULL');
    add_column_if_missing($pdo, 'latest_group_locations', 'p2p_key_version', 'INT UNSIGNED NOT NULL DEFAULT 0');
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
        CREATE TABLE IF NOT EXISTS environment_reports (
            id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            user_id INT UNSIGNED NOT NULL,
            report_json LONGTEXT NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            INDEX idx_environment_reports_user_created (user_id, created_at),
            CONSTRAINT fk_environment_reports_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    ");

    $pdo->exec("
        CREATE TABLE IF NOT EXISTS p2p_user_keys (
            user_id INT UNSIGNED NOT NULL PRIMARY KEY,
            public_key_jwk LONGTEXT NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            CONSTRAINT fk_p2p_user_keys_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    ");

    $pdo->exec("
        CREATE TABLE IF NOT EXISTS p2p_group_members (
            group_name VARCHAR(100) NOT NULL,
            user_id INT UNSIGNED NOT NULL,
            consent_at DATETIME NULL,
            wrapped_group_key LONGTEXT NULL,
            key_version INT UNSIGNED NOT NULL DEFAULT 0,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            PRIMARY KEY (group_name, user_id),
            INDEX idx_p2p_group_members_user (user_id),
            CONSTRAINT fk_p2p_group_members_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    ");

    $pdo->exec("
        CREATE TABLE IF NOT EXISTS user_devices (
            id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            user_id INT UNSIGNED NOT NULL,
            device_fingerprint CHAR(64) NOT NULL,
            browser_fingerprint VARCHAR(128) NOT NULL DEFAULT '',
            user_agent VARCHAR(255) NOT NULL DEFAULT '',
            first_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            last_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            UNIQUE KEY uniq_device_fingerprint (device_fingerprint),
            INDEX idx_user_devices_user_seen (user_id, last_seen_at),
            CONSTRAINT fk_user_devices_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    ");

    $pdo->exec("
        CREATE TABLE IF NOT EXISTS support_tickets (
            id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            user_id INT UNSIGNED NOT NULL,
            group_name VARCHAR(100) NOT NULL DEFAULT '',
            subject VARCHAR(120) NOT NULL DEFAULT '',
            status ENUM('open', 'closed') NOT NULL DEFAULT 'open',
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            INDEX idx_support_tickets_user_updated (user_id, updated_at),
            INDEX idx_support_tickets_status_updated (status, updated_at),
            CONSTRAINT fk_support_tickets_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    ");

    $pdo->exec("
        CREATE TABLE IF NOT EXISTS support_ticket_messages (
            id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            ticket_id BIGINT UNSIGNED NOT NULL,
            sender_type ENUM('user', 'admin') NOT NULL,
            message TEXT NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            INDEX idx_ticket_messages_ticket_created (ticket_id, created_at),
            CONSTRAINT fk_ticket_messages_ticket FOREIGN KEY (ticket_id) REFERENCES support_tickets(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    ");

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
            allow_group_owner TINYINT(1) NOT NULL DEFAULT 0,
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
    add_column_if_missing($pdo, 'invite_codes', 'allow_group_owner', 'TINYINT(1) NOT NULL DEFAULT 0');

    $pdo->exec("
        CREATE TABLE IF NOT EXISTS user_presence (
            user_id INT UNSIGNED NOT NULL PRIMARY KEY,
            last_seen_at DATETIME NOT NULL,
            last_group_name VARCHAR(100) NOT NULL DEFAULT '',
            last_user_agent VARCHAR(255) NOT NULL DEFAULT '',
            last_ip VARCHAR(45) NOT NULL DEFAULT '',
            CONSTRAINT fk_user_presence_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            INDEX idx_user_presence_last_seen (last_seen_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    ");

    $pdo->exec("
        CREATE TABLE IF NOT EXISTS user_logs (
            id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            user_id INT UNSIGNED NULL,
            group_name VARCHAR(100) NOT NULL DEFAULT '',
            event_type VARCHAR(40) NOT NULL,
            message VARCHAR(255) NOT NULL DEFAULT '',
            meta_json LONGTEXT NULL,
            ip VARCHAR(45) NOT NULL DEFAULT '',
            user_agent VARCHAR(255) NOT NULL DEFAULT '',
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            INDEX idx_user_logs_created (created_at),
            INDEX idx_user_logs_user_created (user_id, created_at),
            INDEX idx_user_logs_group_created (group_name, created_at),
            INDEX idx_user_logs_type_created (event_type, created_at),
            CONSTRAINT fk_user_logs_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    ");

    $pdo->exec("
        CREATE TABLE IF NOT EXISTS app_settings (
            setting_key VARCHAR(80) NOT NULL PRIMARY KEY,
            setting_value TEXT NOT NULL,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    ");

    $pdo->exec("
        CREATE TABLE IF NOT EXISTS admin_login_failures (
            ip VARCHAR(45) NOT NULL PRIMARY KEY,
            failed_count INT UNSIGNED NOT NULL DEFAULT 0,
            locked_at DATETIME NULL,
            last_failed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    ");

    $pdo->exec("
        CREATE TABLE IF NOT EXISTS group_join_failures (
            user_id INT UNSIGNED NOT NULL,
            ip VARCHAR(45) NOT NULL,
            failed_count INT UNSIGNED NOT NULL DEFAULT 0,
            locked_at DATETIME NULL,
            last_failed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            PRIMARY KEY (user_id, ip),
            INDEX idx_group_join_failures_ip (ip, last_failed_at),
            CONSTRAINT fk_group_join_failures_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    ");

    $pdo->exec("
        CREATE TABLE IF NOT EXISTS api_rate_limits (
            bucket VARCHAR(80) NOT NULL,
            identity_hash CHAR(64) NOT NULL,
            window_started_at DATETIME NOT NULL,
            hit_count INT UNSIGNED NOT NULL DEFAULT 0,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            PRIMARY KEY (bucket, identity_hash),
            INDEX idx_api_rate_limits_updated (updated_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    ");

    $pdo->exec("
        CREATE TABLE IF NOT EXISTS app_challenges (
            id CHAR(32) NOT NULL PRIMARY KEY,
            secret_hash CHAR(64) NOT NULL,
            device_fingerprint CHAR(64) NOT NULL,
            purpose VARCHAR(20) NOT NULL,
            verified_at DATETIME NULL,
            consumed_at DATETIME NULL,
            expires_at DATETIME NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            INDEX idx_app_challenges_device (device_fingerprint, purpose, expires_at),
            INDEX idx_app_challenges_expires (expires_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    ");

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

function app_setting_keys(): array
{
    return [
        'ban_root_users',
        'ban_adb_users',
        'ban_fake_location_users',
        'ban_accessibility_users',
        'ban_packet_capture_users',
    ];
}

function app_setting(string $key, string $default = ''): string
{
    if (!in_array($key, app_setting_keys(), true)) {
        return $default;
    }

    $stmt = db()->prepare('SELECT setting_value FROM app_settings WHERE setting_key = ? LIMIT 1');
    $stmt->execute([$key]);
    $value = $stmt->fetchColumn();
    return is_string($value) ? $value : $default;
}

function app_setting_bool(string $key, bool $default = false): bool
{
    $value = app_setting($key, $default ? '1' : '0');
    return in_array(strtolower(trim($value)), ['1', 'true', 'yes', 'on'], true);
}

function set_app_setting(string $key, string $value): void
{
    if (!in_array($key, app_setting_keys(), true)) {
        throw new RuntimeException('设置项不存在。');
    }

    $stmt = db()->prepare('
        INSERT INTO app_settings (setting_key, setting_value)
        VALUES (?, ?)
        ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)
    ');
    $stmt->execute([$key, $value]);
}

function security_policy_settings(): array
{
    $settings = [];
    foreach (app_setting_keys() as $key) {
        $settings[$key] = app_setting_bool($key);
    }

    return $settings;
}

function security_policy_enabled(): bool
{
    foreach (app_setting_keys() as $key) {
        if (app_setting_bool($key)) {
            return true;
        }
    }

    return false;
}

function device_report_policy_violations(array $report): array
{
    $violations = [];

    if (app_setting_bool('ban_root_users') && !empty($report['root_detected'])) {
        $violations[] = 'Root';
    }
    if (app_setting_bool('ban_adb_users') && !empty($report['adb_enabled'])) {
        $violations[] = 'ADB';
    }
    if (
        app_setting_bool('ban_fake_location_users')
        && (!empty($report['mock_location_risk']) || !empty($report['fake_location_detected']))
    ) {
        $violations[] = '模拟定位';
    }
    if (
        app_setting_bool('ban_accessibility_users')
        && (!empty($report['accessibility_risk']) || !empty($report['accessibility_services']))
    ) {
        $violations[] = '无障碍风险服务';
    }
    if (app_setting_bool('ban_packet_capture_users') && device_report_has_packet_capture_risk($report)) {
        $violations[] = '抓包工具';
    }

    return $violations;
}

function device_report_has_packet_capture_risk(array $report): bool
{
    if (!empty($report['reqable_detected'])) {
        return true;
    }

    $packages = $report['suspicious_packages'] ?? [];
    if (!is_array($packages)) {
        return false;
    }

    foreach ($packages as $package) {
        $name = strtolower((string) $package);
        if (str_contains($name, 'reqable') || str_contains($name, 'httpcanary') || str_contains($name, 'charles')) {
            return true;
        }
    }

    return false;
}

function enforce_device_report_policy(array $user, ?array $report): void
{
    if (!security_policy_enabled() || !empty($user['debug_mode'])) {
        return;
    }

    if (!$report) {
        json_response(['ok' => false, 'message' => '缺少设备环境数据，已拒绝请求。'], 403);
    }

    $violations = device_report_policy_violations($report);
    if (!$violations) {
        return;
    }

    json_response([
        'ok' => false,
        'message' => '当前设备环境不符合后台安全策略：' . implode('、', $violations) . '。',
    ], 403);
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

function user_environment_data_consent(array $user): bool
{
    return !empty($user['environment_data_consent_at']);
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

function request_device_fingerprint(): string
{
    $cookieName = defined('APP_DEVICE_COOKIE_NAME') ? APP_DEVICE_COOKIE_NAME : 'loc_device';
    $deviceCookie = (string) ($_COOKIE[$cookieName] ?? '');
    if (!preg_match('/^[a-f0-9]{64}$/i', $deviceCookie)) {
        json_response(['ok' => false, 'message' => '请使用新版 App 登录。'], 403);
    }

    return strtolower($deviceCookie);
}

function input_browser_fingerprint(): string
{
    $value = input_string('browser_fingerprint', 128);
    return preg_match('/^[a-zA-Z0-9:_-]{1,128}$/', $value) ? $value : '';
}

function bind_user_device(PDO $pdo, array $user, string $deviceFingerprint, string $browserFingerprint = ''): void
{
    if ($deviceFingerprint === '') {
        return;
    }

    $userId = (int) $user['id'];
    $debugMode = !empty($user['debug_mode']);
    $userAgent = substr((string) ($_SERVER['HTTP_USER_AGENT'] ?? ''), 0, 255);

    $stmt = $pdo->prepare('SELECT * FROM user_devices WHERE device_fingerprint = ? LIMIT 1');
    $stmt->execute([$deviceFingerprint]);
    $existing = $stmt->fetch();

    if ($existing && (int) $existing['user_id'] !== $userId) {
        if ($debugMode) {
            record_user_log($userId, '', 'device_bind_skipped_debug', '调试模式跳过设备指纹冲突', [
                'device_fingerprint' => $deviceFingerprint,
                'bound_user_id' => (int) $existing['user_id'],
            ]);
            return;
        }

        json_response(['ok' => false, 'message' => '该设备已绑定其他账号。'], 403);
    }

    if (!$existing) {
        $stmt = $pdo->prepare('SELECT COUNT(*) FROM user_devices WHERE user_id = ?');
        $stmt->execute([$userId]);
        if (!$debugMode && (int) $stmt->fetchColumn() >= 3) {
            json_response(['ok' => false, 'message' => '该账号绑定设备已达 3 台，请联系后台删除旧设备。'], 403);
        }

        $stmt = $pdo->prepare('
            INSERT INTO user_devices (user_id, device_fingerprint, browser_fingerprint, user_agent, last_seen_at)
            VALUES (?, ?, ?, ?, NOW())
        ');
        $stmt->execute([$userId, $deviceFingerprint, $browserFingerprint, $userAgent]);
        record_user_log($userId, '', 'device_bind', '绑定新设备指纹');
        return;
    }

    $stmt = $pdo->prepare('
        UPDATE user_devices
        SET browser_fingerprint = ?,
            user_agent = ?,
            last_seen_at = NOW()
        WHERE id = ?
    ');
    $stmt->execute([$browserFingerprint, $userAgent, (int) $existing['id']]);
}

function json_response(array $data, int $status = 200): never
{
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function consume_app_challenge_token(string $token, string $purpose): bool
{
    if (!str_starts_with($token, 'app:')) {
        return false;
    }

    $parts = explode(':', $token, 3);
    if (count($parts) !== 3) {
        return false;
    }

    $challengeId = $parts[1];
    $secret = $parts[2];
    if (!preg_match('/^[a-f0-9]{32}$/i', $challengeId) || !preg_match('/^[a-f0-9]{64}$/i', $secret)) {
        return false;
    }

    $deviceFingerprint = request_device_fingerprint();
    $pdo = db();
    $stmt = $pdo->prepare('
        SELECT *
        FROM app_challenges
        WHERE id = ?
            AND purpose = ?
            AND device_fingerprint = ?
            AND verified_at IS NOT NULL
            AND consumed_at IS NULL
            AND expires_at > NOW()
        LIMIT 1
    ');
    $stmt->execute([$challengeId, $purpose, $deviceFingerprint]);
    $challenge = $stmt->fetch();
    if (!$challenge || !hash_equals((string) $challenge['secret_hash'], hash('sha256', $secret))) {
        return false;
    }

    $stmt = $pdo->prepare('UPDATE app_challenges SET consumed_at = NOW() WHERE id = ? AND consumed_at IS NULL');
    $stmt->execute([$challengeId]);

    return $stmt->rowCount() === 1;
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
        'p2p_enabled' => !empty($group['p2p_enabled_at']),
        'p2p_key_version' => (int) ($group['p2p_key_version'] ?? 0),
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
        'encryption_mode' => (string) ($row['encryption_mode'] ?? ''),
        'encrypted_payload' => (string) ($row['encrypted_payload'] ?? ''),
        'p2p_key_version' => (int) ($row['p2p_key_version'] ?? 0),
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
    $payloadGroups = [];
    foreach ($groups as $group) {
        $groupPayload = group_payload($group);
        if ((int) ($group['owner_user_id'] ?? 0) === (int) $user['id']) {
            $groupPayload['members'] = group_members_payload((string) $group['group_name']);
        }
        $payloadGroups[] = $groupPayload;
    }

    return [
        'id' => (int) $user['id'],
        'username' => $user['username'],
        'display_name' => $user['display_name'],
        'group_name' => $membership['group_name'] ?? '',
        'role' => $membership ? normalize_role((string) $membership['role']) : '',
        'role_label' => $membership ? role_label((string) $membership['role']) : '',
        'terms_accepted' => user_terms_accepted($user),
        'cross_border_transfer_accepted' => user_cross_border_transfer_accepted($user),
        'environment_data_consent' => user_environment_data_consent($user),
        'groups' => $payloadGroups,
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
            fg.owner_user_id,
            fg.p2p_enabled_at,
            fg.p2p_key_version
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

function group_members_payload(string $groupName): array
{
    $stmt = db()->prepare('
        SELECT
            u.id AS user_id,
            u.username,
            u.display_name,
            ug.role
        FROM user_groups ug
        INNER JOIN users u ON u.id = ug.user_id
        WHERE ug.group_name = ?
        ORDER BY ug.role ASC, u.username ASC
    ');
    $stmt->execute([$groupName]);

    return array_map(static function (array $member): array {
        return [
            'user_id' => (int) $member['user_id'],
            'username' => (string) $member['username'],
            'display_name' => (string) $member['display_name'],
            'role' => normalize_role((string) $member['role']),
            'role_label' => role_label((string) $member['role']),
        ];
    }, $stmt->fetchAll());
}

function require_group_owner(array $user, string $groupName): array
{
    $membership = require_user_membership($user, $groupName);
    $stmt = db()->prepare('SELECT * FROM family_groups WHERE group_name = ? LIMIT 1');
    $stmt->execute([(string) $membership['group_name']]);
    $group = $stmt->fetch();

    if (!$group || (int) ($group['owner_user_id'] ?? 0) !== (int) $user['id']) {
        json_response(['ok' => false, 'message' => '只有家庭组管理员可以操作。'], 403);
    }

    return $group;
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

function admin_login_failure_limit(): int
{
    return defined('ADMIN_MAX_LOGIN_FAILURES') ? max(1, (int) ADMIN_MAX_LOGIN_FAILURES) : 5;
}

function admin_login_locked(PDO $pdo, string $ip): bool
{
    $stmt = $pdo->prepare('SELECT locked_at FROM admin_login_failures WHERE ip = ? LIMIT 1');
    $stmt->execute([$ip]);
    $row = $stmt->fetch();

    return $row && !empty($row['locked_at']) && strtotime((string) $row['locked_at']) > time() - LOGIN_LOCK_SECONDS;
}

function record_failed_admin_login(PDO $pdo, string $ip): bool
{
    $stmt = $pdo->prepare('SELECT * FROM admin_login_failures WHERE ip = ? LIMIT 1');
    $stmt->execute([$ip]);
    $row = $stmt->fetch();

    $failedCount = 1;
    if ($row && strtotime((string) $row['last_failed_at']) > time() - LOGIN_LOCK_SECONDS) {
        $failedCount = (int) $row['failed_count'] + 1;
    }
    $lockedAt = $failedCount >= admin_login_failure_limit() ? date('Y-m-d H:i:s') : null;

    $stmt = $pdo->prepare('
        INSERT INTO admin_login_failures (ip, failed_count, locked_at, last_failed_at)
        VALUES (?, ?, ?, NOW())
        ON DUPLICATE KEY UPDATE
            failed_count = VALUES(failed_count),
            locked_at = VALUES(locked_at),
            last_failed_at = NOW()
    ');
    $stmt->execute([$ip, $failedCount, $lockedAt]);

    return $lockedAt !== null;
}

function clear_failed_admin_login(PDO $pdo, string $ip): void
{
    $stmt = $pdo->prepare('DELETE FROM admin_login_failures WHERE ip = ?');
    $stmt->execute([$ip]);
}

function group_join_failure_limit(): int
{
    return defined('GROUP_JOIN_MAX_FAILURES') ? max(1, (int) GROUP_JOIN_MAX_FAILURES) : 10;
}

function group_join_locked(PDO $pdo, int $userId, string $ip): bool
{
    $stmt = $pdo->prepare('SELECT locked_at FROM group_join_failures WHERE user_id = ? AND ip = ? LIMIT 1');
    $stmt->execute([$userId, $ip]);
    $row = $stmt->fetch();

    return $row && !empty($row['locked_at']) && strtotime((string) $row['locked_at']) > time() - LOGIN_LOCK_SECONDS;
}

function record_failed_group_join(PDO $pdo, int $userId, string $ip): bool
{
    $stmt = $pdo->prepare('SELECT * FROM group_join_failures WHERE user_id = ? AND ip = ? LIMIT 1');
    $stmt->execute([$userId, $ip]);
    $row = $stmt->fetch();

    $failedCount = 1;
    if ($row && strtotime((string) $row['last_failed_at']) > time() - LOGIN_LOCK_SECONDS) {
        $failedCount = (int) $row['failed_count'] + 1;
    }
    $lockedAt = $failedCount >= group_join_failure_limit() ? date('Y-m-d H:i:s') : null;

    $stmt = $pdo->prepare('
        INSERT INTO group_join_failures (user_id, ip, failed_count, locked_at, last_failed_at)
        VALUES (?, ?, ?, ?, NOW())
        ON DUPLICATE KEY UPDATE
            failed_count = VALUES(failed_count),
            locked_at = VALUES(locked_at),
            last_failed_at = NOW()
    ');
    $stmt->execute([$userId, $ip, $failedCount, $lockedAt]);

    return $lockedAt !== null;
}

function clear_failed_group_join(PDO $pdo, int $userId, string $ip): void
{
    $stmt = $pdo->prepare('DELETE FROM group_join_failures WHERE user_id = ? AND ip = ?');
    $stmt->execute([$userId, $ip]);
}

function rate_limit_or_fail(string $bucket, int $maxHits, int $windowSeconds, string $identity = ''): void
{
    $bucket = substr(preg_replace('/[^a-zA-Z0-9:_-]/', '', $bucket), 0, 80);
    if ($bucket === '') {
        $bucket = 'default';
    }

    $maxHits = max(1, $maxHits);
    $windowSeconds = max(1, $windowSeconds);
    $identitySource = $identity !== '' ? $identity : client_ip_address();
    $identityHash = hash('sha256', $bucket . '|' . $identitySource);
    $pdo = db();

    $stmt = $pdo->prepare('SELECT window_started_at, hit_count FROM api_rate_limits WHERE bucket = ? AND identity_hash = ? LIMIT 1');
    $stmt->execute([$bucket, $identityHash]);
    $row = $stmt->fetch();

    if (!$row || strtotime((string) $row['window_started_at']) <= time() - $windowSeconds) {
        $stmt = $pdo->prepare('
            INSERT INTO api_rate_limits (bucket, identity_hash, window_started_at, hit_count)
            VALUES (?, ?, NOW(), 1)
            ON DUPLICATE KEY UPDATE
                window_started_at = VALUES(window_started_at),
                hit_count = 1,
                updated_at = NOW()
        ');
        $stmt->execute([$bucket, $identityHash]);
        return;
    }

    $nextHitCount = (int) $row['hit_count'] + 1;
    $stmt = $pdo->prepare('UPDATE api_rate_limits SET hit_count = ?, updated_at = NOW() WHERE bucket = ? AND identity_hash = ?');
    $stmt->execute([$nextHitCount, $bucket, $identityHash]);

    if ($nextHitCount > $maxHits) {
        json_response(['ok' => false, 'message' => '请求过于频繁，请稍后再试。'], 429);
    }
}

function client_ip_address(): string
{
    $candidates = [
        $_SERVER['HTTP_CF_CONNECTING_IP'] ?? '',
        $_SERVER['HTTP_X_FORWARDED_FOR'] ?? '',
        $_SERVER['REMOTE_ADDR'] ?? '',
    ];

    foreach ($candidates as $candidate) {
        $ip = trim(explode(',', (string) $candidate)[0]);
        if ($ip !== '' && filter_var($ip, FILTER_VALIDATE_IP)) {
            return substr($ip, 0, 45);
        }
    }

    return '';
}

function text_limit(string $value, int $maxLength): string
{
    if (function_exists('mb_substr')) {
        return mb_substr($value, 0, $maxLength, 'UTF-8');
    }

    return strlen($value) > $maxLength * 4 ? substr($value, 0, $maxLength * 4) : $value;
}

function record_user_log(?int $userId, string $groupName, string $eventType, string $message = '', array $meta = []): void
{
    try {
        $metaJson = $meta
            ? json_encode($meta, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES)
            : null;
        $stmt = db()->prepare('
            INSERT INTO user_logs (user_id, group_name, event_type, message, meta_json, ip, user_agent)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        ');
        $stmt->execute([
            $userId,
            text_limit($groupName, 100),
            substr($eventType, 0, 40),
            text_limit($message, 255),
            $metaJson,
            client_ip_address(),
            substr((string) ($_SERVER['HTTP_USER_AGENT'] ?? ''), 0, 255),
        ]);
    } catch (Throwable $error) {
        error_log('[family-location] user log failed: ' . $error->getMessage());
    }
}

function touch_user_presence(int $userId, string $groupName = ''): void
{
    $stmt = db()->prepare('
        INSERT INTO user_presence (user_id, last_seen_at, last_group_name, last_user_agent, last_ip)
        VALUES (?, NOW(), ?, ?, ?)
        ON DUPLICATE KEY UPDATE
            last_seen_at = NOW(),
            last_group_name = VALUES(last_group_name),
            last_user_agent = VALUES(last_user_agent),
            last_ip = VALUES(last_ip)
    ');
    $stmt->execute([
        $userId,
        text_limit($groupName, 100),
        substr((string) ($_SERVER['HTTP_USER_AGENT'] ?? ''), 0, 255),
        client_ip_address(),
    ]);
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
            ll.encryption_mode,
            ll.encrypted_payload,
            ll.p2p_key_version,
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
