ALTER TABLE family_groups
    MODIFY COLUMN group_code VARCHAR(32) NULL;

UPDATE family_groups
SET group_code = LOWER(HEX(RANDOM_BYTES(16)))
WHERE group_code IS NOT NULL
  AND CHAR_LENGTH(group_code) = 6;
