SET @environment_reports_user_id_index_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE environment_reports ADD INDEX idx_environment_reports_user_id (user_id, id)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'environment_reports'
      AND index_name = 'idx_environment_reports_user_id'
);
PREPARE environment_reports_user_id_index_stmt FROM @environment_reports_user_id_index_sql;
EXECUTE environment_reports_user_id_index_stmt;
DEALLOCATE PREPARE environment_reports_user_id_index_stmt;
