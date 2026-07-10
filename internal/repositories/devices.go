package repositories

import (
	"context"
	"database/sql"
)

type UserDevice struct {
	ID                 int64
	UserID             int64
	DeviceFingerprint  string
	BrowserFingerprint string
	UserAgent          string
}

type DeviceRepository struct {
	db *sql.DB
}

func NewDeviceRepository(db *sql.DB) DeviceRepository {
	return DeviceRepository{db: db}
}

func (repo DeviceRepository) FindByFingerprint(ctx context.Context, fingerprint string) (*UserDevice, error) {
	var device UserDevice
	err := repo.db.QueryRowContext(ctx, `
SELECT id, user_id, device_fingerprint, browser_fingerprint, user_agent
FROM user_devices
WHERE device_fingerprint = ?
LIMIT 1`, fingerprint).Scan(&device.ID, &device.UserID, &device.DeviceFingerprint, &device.BrowserFingerprint, &device.UserAgent)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &device, nil
}

func (repo DeviceRepository) CountByUser(ctx context.Context, userID int64) (int, error) {
	var count int
	err := repo.db.QueryRowContext(ctx, "SELECT COUNT(*) FROM user_devices WHERE user_id = ?", userID).Scan(&count)
	return count, err
}

func (repo DeviceRepository) Insert(ctx context.Context, userID int64, fingerprint string, browserFingerprint string, userAgent string) error {
	_, err := repo.db.ExecContext(ctx, `
INSERT INTO user_devices (user_id, device_fingerprint, browser_fingerprint, user_agent, last_seen_at)
VALUES (?, ?, ?, ?, NOW())`, userID, fingerprint, browserFingerprint, userAgent)
	return err
}

func (repo DeviceRepository) UpdateSeen(ctx context.Context, id int64, browserFingerprint string, userAgent string) error {
	_, err := repo.db.ExecContext(ctx, `
UPDATE user_devices
SET browser_fingerprint = ?, user_agent = ?, last_seen_at = NOW()
WHERE id = ?`, browserFingerprint, userAgent, id)
	return err
}
