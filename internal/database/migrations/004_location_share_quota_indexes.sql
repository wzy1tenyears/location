SET @location_share_owner_index_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE location_shares ADD INDEX idx_location_shares_owner_expires (owner_user_id, expires_at)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'location_shares'
      AND index_name = 'idx_location_shares_owner_expires'
);
PREPARE location_share_owner_index_stmt FROM @location_share_owner_index_sql;
EXECUTE location_share_owner_index_stmt;
DEALLOCATE PREPARE location_share_owner_index_stmt;

SET @location_share_group_index_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE location_shares ADD INDEX idx_location_shares_group_expires (group_name, expires_at)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'location_shares'
      AND index_name = 'idx_location_shares_group_expires'
);
PREPARE location_share_group_index_stmt FROM @location_share_group_index_sql;
EXECUTE location_share_group_index_stmt;
DEALLOCATE PREPARE location_share_group_index_stmt;
