UPDATE invite_codes
SET is_active = 0
WHERE is_active = 1
  AND (assigned_group_name IS NULL OR TRIM(assigned_group_name) = '');
