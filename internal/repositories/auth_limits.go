package repositories

import (
	"context"
	"database/sql"
	"time"
)

type AuthLimitRepository struct {
	db *sql.DB
}

func NewAuthLimitRepository(db *sql.DB) AuthLimitRepository {
	return AuthLimitRepository{db: db}
}

func (repo AuthLimitRepository) AdminLoginLocked(ctx context.Context, ip string, lockWindow time.Duration) (bool, error) {
	var lockedAt sql.NullTime
	err := repo.db.QueryRowContext(ctx, "SELECT locked_at FROM admin_login_failures WHERE ip = ? LIMIT 1", ip).Scan(&lockedAt)
	if err == sql.ErrNoRows {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return lockedAt.Valid && lockedAt.Time.After(time.Now().Add(-lockWindow)), nil
}

func (repo AuthLimitRepository) RecordFailedAdminLogin(ctx context.Context, ip string, limit int, lockWindow time.Duration) (bool, error) {
	var failedCount int
	var lastFailedAt sql.NullTime
	err := repo.db.QueryRowContext(ctx, "SELECT failed_count, last_failed_at FROM admin_login_failures WHERE ip = ? LIMIT 1", ip).Scan(&failedCount, &lastFailedAt)
	if err != nil && err != sql.ErrNoRows {
		return false, err
	}
	if err == sql.ErrNoRows || !lastFailedAt.Valid || lastFailedAt.Time.Before(time.Now().Add(-lockWindow)) {
		failedCount = 1
	} else {
		failedCount += 1
	}
	var lockedAt any
	if failedCount >= limit {
		lockedAt = time.Now()
	}
	_, execErr := repo.db.ExecContext(ctx, `
INSERT INTO admin_login_failures (ip, failed_count, locked_at, last_failed_at)
VALUES (?, ?, ?, NOW())
ON DUPLICATE KEY UPDATE
	failed_count = VALUES(failed_count),
	locked_at = VALUES(locked_at),
	last_failed_at = NOW()`, ip, failedCount, lockedAt)
	return lockedAt != nil, execErr
}

func (repo AuthLimitRepository) ClearFailedAdminLogin(ctx context.Context, ip string) error {
	_, err := repo.db.ExecContext(ctx, "DELETE FROM admin_login_failures WHERE ip = ?", ip)
	return err
}

func (repo AuthLimitRepository) GroupJoinLocked(ctx context.Context, userID int64, ip string, lockWindow time.Duration) (bool, error) {
	var lockedAt sql.NullTime
	err := repo.db.QueryRowContext(ctx, "SELECT locked_at FROM group_join_failures WHERE user_id = ? AND ip = ? LIMIT 1", userID, ip).Scan(&lockedAt)
	if err == sql.ErrNoRows {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return lockedAt.Valid && lockedAt.Time.After(time.Now().Add(-lockWindow)), nil
}

func (repo AuthLimitRepository) RecordFailedGroupJoin(ctx context.Context, userID int64, ip string, limit int, lockWindow time.Duration) (bool, error) {
	var failedCount int
	var lastFailedAt sql.NullTime
	err := repo.db.QueryRowContext(ctx, "SELECT failed_count, last_failed_at FROM group_join_failures WHERE user_id = ? AND ip = ? LIMIT 1", userID, ip).Scan(&failedCount, &lastFailedAt)
	if err != nil && err != sql.ErrNoRows {
		return false, err
	}
	if err == sql.ErrNoRows || !lastFailedAt.Valid || lastFailedAt.Time.Before(time.Now().Add(-lockWindow)) {
		failedCount = 1
	} else {
		failedCount += 1
	}
	var lockedAt any
	if failedCount >= limit {
		lockedAt = time.Now()
	}
	_, execErr := repo.db.ExecContext(ctx, `
INSERT INTO group_join_failures (user_id, ip, failed_count, locked_at, last_failed_at)
VALUES (?, ?, ?, ?, NOW())
ON DUPLICATE KEY UPDATE
	failed_count = VALUES(failed_count),
	locked_at = VALUES(locked_at),
	last_failed_at = NOW()`, userID, ip, failedCount, lockedAt)
	return lockedAt != nil, execErr
}

func (repo AuthLimitRepository) ClearFailedGroupJoin(ctx context.Context, userID int64, ip string) error {
	_, err := repo.db.ExecContext(ctx, "DELETE FROM group_join_failures WHERE user_id = ? AND ip = ?", userID, ip)
	return err
}
