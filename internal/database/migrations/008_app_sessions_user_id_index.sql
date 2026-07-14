SET @app_sessions_user_id_index_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE app_sessions ADD INDEX idx_app_sessions_user_id (user_id)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'app_sessions'
      AND index_name = 'idx_app_sessions_user_id'
);
PREPARE app_sessions_user_id_index_stmt FROM @app_sessions_user_id_index_sql;
EXECUTE app_sessions_user_id_index_stmt;
DEALLOCATE PREPARE app_sessions_user_id_index_stmt;
