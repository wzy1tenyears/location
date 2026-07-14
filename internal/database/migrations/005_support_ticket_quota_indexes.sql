SET @support_tickets_user_created_index_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE support_tickets ADD INDEX idx_support_tickets_user_created (user_id, created_at)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'support_tickets'
      AND index_name = 'idx_support_tickets_user_created'
);
PREPARE support_tickets_user_created_index_stmt FROM @support_tickets_user_created_index_sql;
EXECUTE support_tickets_user_created_index_stmt;
DEALLOCATE PREPARE support_tickets_user_created_index_stmt;

SET @support_tickets_group_created_index_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE support_tickets ADD INDEX idx_support_tickets_group_created (group_name, created_at)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'support_tickets'
      AND index_name = 'idx_support_tickets_group_created'
);
PREPARE support_tickets_group_created_index_stmt FROM @support_tickets_group_created_index_sql;
EXECUTE support_tickets_group_created_index_stmt;
DEALLOCATE PREPARE support_tickets_group_created_index_stmt;

SET @support_tickets_group_status_index_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE support_tickets ADD INDEX idx_support_tickets_group_status_updated (group_name, status, updated_at)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'support_tickets'
      AND index_name = 'idx_support_tickets_group_status_updated'
);
PREPARE support_tickets_group_status_index_stmt FROM @support_tickets_group_status_index_sql;
EXECUTE support_tickets_group_status_index_stmt;
DEALLOCATE PREPARE support_tickets_group_status_index_stmt;

SET @ticket_messages_sender_created_index_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE support_ticket_messages ADD INDEX idx_ticket_messages_ticket_sender_created (ticket_id, sender_type, created_at)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'support_ticket_messages'
      AND index_name = 'idx_ticket_messages_ticket_sender_created'
);
PREPARE ticket_messages_sender_created_index_stmt FROM @ticket_messages_sender_created_index_sql;
EXECUTE ticket_messages_sender_created_index_stmt;
DEALLOCATE PREPARE ticket_messages_sender_created_index_stmt;

SET @ticket_messages_ticket_id_index_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE support_ticket_messages ADD INDEX idx_ticket_messages_ticket_id (ticket_id, id)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'support_ticket_messages'
      AND index_name = 'idx_ticket_messages_ticket_id'
);
PREPARE ticket_messages_ticket_id_index_stmt FROM @ticket_messages_ticket_id_index_sql;
EXECUTE ticket_messages_ticket_id_index_stmt;
DEALLOCATE PREPARE ticket_messages_ticket_id_index_stmt;
