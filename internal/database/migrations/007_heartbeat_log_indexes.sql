SET @user_logs_user_type_index_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE user_logs ADD INDEX idx_user_logs_user_type_id (user_id, event_type, id)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'user_logs'
      AND index_name = 'idx_user_logs_user_type_id'
);
PREPARE user_logs_user_type_index_stmt FROM @user_logs_user_type_index_sql;
EXECUTE user_logs_user_type_index_stmt;
DEALLOCATE PREPARE user_logs_user_type_index_stmt;

SET @user_logs_group_type_index_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE user_logs ADD INDEX idx_user_logs_group_type_id (group_name, event_type, id)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'user_logs'
      AND index_name = 'idx_user_logs_group_type_id'
);
PREPARE user_logs_group_type_index_stmt FROM @user_logs_group_type_index_sql;
EXECUTE user_logs_group_type_index_stmt;
DEALLOCATE PREPARE user_logs_group_type_index_stmt;
