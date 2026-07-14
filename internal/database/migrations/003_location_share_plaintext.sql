ALTER TABLE location_shares
    ADD COLUMN token_plaintext CHAR(64) NOT NULL DEFAULT '' AFTER token_hash,
    ADD COLUMN access_code_plaintext VARCHAR(64) NOT NULL DEFAULT '' AFTER access_code_hash,
    ADD COLUMN snapshot_json LONGTEXT NULL AFTER location_ids_json;
