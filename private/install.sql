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
    debug_mode TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_users_group_role (group_name, role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS support_ticket_messages (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    ticket_id BIGINT UNSIGNED NOT NULL,
    sender_type ENUM('user', 'admin') NOT NULL,
    message TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ticket_messages_ticket_created (ticket_id, created_at),
    CONSTRAINT fk_ticket_messages_ticket FOREIGN KEY (ticket_id) REFERENCES support_tickets(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS p2p_user_keys (
    user_id INT UNSIGNED NOT NULL PRIMARY KEY,
    public_key_jwk LONGTEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_p2p_user_keys_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS announcements (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(120) NOT NULL DEFAULT '',
    body TEXT NOT NULL,
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    version INT UNSIGNED NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_announcements_active_updated (is_active, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_presence (
    user_id INT UNSIGNED NOT NULL PRIMARY KEY,
    last_seen_at DATETIME NOT NULL,
    last_group_name VARCHAR(100) NOT NULL DEFAULT '',
    last_user_agent VARCHAR(255) NOT NULL DEFAULT '',
    last_ip VARCHAR(45) NOT NULL DEFAULT '',
    CONSTRAINT fk_user_presence_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_user_presence_last_seen (last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
