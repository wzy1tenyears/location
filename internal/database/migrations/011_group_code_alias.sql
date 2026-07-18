SET @legacy_group_code_column_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE family_groups ADD COLUMN legacy_group_code VARCHAR(32) NULL AFTER group_code',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'family_groups'
      AND column_name = 'legacy_group_code'
);
PREPARE legacy_group_code_column_stmt FROM @legacy_group_code_column_sql;
EXECUTE legacy_group_code_column_stmt;
DEALLOCATE PREPARE legacy_group_code_column_stmt;

SET @legacy_group_code_column_contract_sql = (
    SELECT IF(
        COUNT(*) = 1
        AND SUM(CASE
            WHEN data_type = 'varchar'
             AND character_maximum_length = 32
             AND is_nullable = 'YES'
            THEN 1 ELSE 0
        END) = 1,
        'SELECT 1',
        'SELECT __family_location_invalid_legacy_group_code_column_contract__ FROM family_groups LIMIT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'family_groups'
      AND column_name = 'legacy_group_code'
);
PREPARE legacy_group_code_column_contract_stmt FROM @legacy_group_code_column_contract_sql;
EXECUTE legacy_group_code_column_contract_stmt;
DEALLOCATE PREPARE legacy_group_code_column_contract_stmt;

SET @legacy_group_code_index_sql = (
    SELECT CASE
        WHEN COUNT(*) = 0 THEN
            'ALTER TABLE family_groups ADD UNIQUE INDEX uniq_family_groups_legacy_group_code (legacy_group_code)'
        WHEN COUNT(*) = 1
          AND SUM(CASE
              WHEN non_unique = 0
               AND column_name = 'legacy_group_code'
               AND seq_in_index = 1
		       AND sub_part IS NULL
              THEN 1 ELSE 0
          END) = 1 THEN
            'SELECT 1'
        ELSE
            'ALTER TABLE family_groups DROP INDEX uniq_family_groups_legacy_group_code, ADD UNIQUE INDEX uniq_family_groups_legacy_group_code (legacy_group_code)'
    END
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'family_groups'
      AND index_name = 'uniq_family_groups_legacy_group_code'
);
PREPARE legacy_group_code_index_stmt FROM @legacy_group_code_index_sql;
EXECUTE legacy_group_code_index_stmt;
DEALLOCATE PREPARE legacy_group_code_index_stmt;
