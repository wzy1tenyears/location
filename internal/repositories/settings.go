package repositories

import (
	"context"
	"database/sql"
)

type SettingRepository struct {
	db *sql.DB
}

func NewSettingRepository(db *sql.DB) SettingRepository {
	return SettingRepository{db: db}
}

func (repo SettingRepository) Bool(ctx context.Context, key string) (bool, error) {
	var value string
	err := repo.db.QueryRowContext(ctx, "SELECT setting_value FROM app_settings WHERE setting_key = ? LIMIT 1", key).Scan(&value)
	if err == sql.ErrNoRows {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return value == "1" || value == "true" || value == "yes" || value == "on", nil
}

func (repo SettingRepository) SecurityPolicy(ctx context.Context) (map[string]bool, error) {
	keys := []string{
		"ban_root_users",
		"ban_adb_users",
		"ban_fake_location_users",
		"ban_accessibility_users",
		"ban_packet_capture_users",
		"ban_suspicious_packages_users",
	}
	settings := make(map[string]bool, len(keys))
	for _, key := range keys {
		value, err := repo.Bool(ctx, key)
		if err != nil {
			return nil, err
		}
		settings[key] = value
	}
	return settings, nil
}

func (repo SettingRepository) Set(ctx context.Context, key string, value string) error {
	_, err := repo.db.ExecContext(ctx, `
INSERT INTO app_settings (setting_key, setting_value)
VALUES (?, ?)
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)`, key, value)
	return err
}

func SecuritySettingKeys() []string {
	return []string{
		"ban_root_users",
		"ban_adb_users",
		"ban_fake_location_users",
		"ban_accessibility_users",
		"ban_packet_capture_users",
		"ban_suspicious_packages_users",
	}
}
