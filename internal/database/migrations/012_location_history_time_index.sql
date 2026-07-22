SET @locations_group_user_created_id_index_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE locations ADD INDEX idx_locations_group_user_created_id (group_name, user_id, created_at, id)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'locations'
      AND index_name = 'idx_locations_group_user_created_id'
);
PREPARE locations_group_user_created_id_index_stmt FROM @locations_group_user_created_id_index_sql;
EXECUTE locations_group_user_created_id_index_stmt;
DEALLOCATE PREPARE locations_group_user_created_id_index_stmt;
