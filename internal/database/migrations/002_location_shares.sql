CREATE TABLE IF NOT EXISTS location_shares (
    token_hash CHAR(64) NOT NULL PRIMARY KEY,
    owner_user_id INT UNSIGNED NOT NULL,
    group_name VARCHAR(100) NOT NULL,
    location_ids_json LONGTEXT NOT NULL,
    access_code_hash VARCHAR(255) NOT NULL,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_location_shares_owner_created (owner_user_id, created_at),
    INDEX idx_location_shares_owner_expires (owner_user_id, expires_at),
    INDEX idx_location_shares_group_expires (group_name, expires_at),
    INDEX idx_location_shares_expires (expires_at),
    CONSTRAINT fk_location_shares_owner FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
