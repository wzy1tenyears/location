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

func (repo AuthLimitRepository) ReserveAdminLoginAttempt(ctx context.Context, ip string, limit int, lockWindow time.Duration) (bool, bool, error) {
	now := time.Now()
	tx, err := repo.db.BeginTx(ctx, nil)
	if err != nil {
		return false, false, err
	}
	defer func() { _ = tx.Rollback() }()

	_, err = tx.ExecContext(ctx, `
INSERT INTO admin_login_failures (ip, failed_count, locked_at, last_failed_at)
VALUES (?, 0, NULL, ?)
	ON DUPLICATE KEY UPDATE ip = VALUES(ip)`, ip, now)
	if err != nil {
		return false, false, err
	}

	var failedCount int
	var lockedAt sql.NullTime
	var lastFailedAt sql.NullTime
	err = tx.QueryRowContext(ctx, `
SELECT failed_count, locked_at, last_failed_at
FROM admin_login_failures
WHERE ip = ?
LIMIT 1 FOR UPDATE`, ip).Scan(&failedCount, &lockedAt, &lastFailedAt)
	if err != nil {
		return false, false, err
	}
	now = time.Now()
	if lockedAt.Valid && lockedAt.Time.After(now.Add(-lockWindow)) {
		if err := tx.Commit(); err != nil {
			return false, false, err
		}
		return false, true, nil
	}
	if !lastFailedAt.Valid || lastFailedAt.Time.Before(now.Add(-lockWindow)) {
		failedCount = 1
	} else {
		failedCount += 1
	}
	var nextLockedAt any
	if failedCount >= limit {
		nextLockedAt = now
	}
	_, err = tx.ExecContext(ctx, `
UPDATE admin_login_failures
SET failed_count = ?, locked_at = ?, last_failed_at = ?
WHERE ip = ?`, failedCount, nextLockedAt, now, ip)
	if err != nil {
		return false, false, err
	}
	if err := tx.Commit(); err != nil {
		return false, false, err
	}
	return true, nextLockedAt != nil, nil
}

func (repo AuthLimitRepository) ClearFailedAdminLogin(ctx context.Context, ip string) error {
	_, err := repo.db.ExecContext(ctx, "DELETE FROM admin_login_failures WHERE ip = ?", ip)
	return err
}

func (repo AuthLimitRepository) ReserveGroupJoinAttempt(ctx context.Context, userID int64, ip string, limit int, lockWindow time.Duration) (bool, bool, error) {
	now := time.Now()
	tx, err := repo.db.BeginTx(ctx, nil)
	if err != nil {
		return false, false, err
	}
	defer func() { _ = tx.Rollback() }()

	_, err = tx.ExecContext(ctx, `
INSERT INTO group_join_failures (user_id, ip, failed_count, locked_at, last_failed_at)
VALUES (?, ?, 0, NULL, ?)
	ON DUPLICATE KEY UPDATE user_id = VALUES(user_id)`, userID, ip, now)
	if err != nil {
		return false, false, err
	}

	var failedCount int
	var lockedAt sql.NullTime
	var lastFailedAt sql.NullTime
	err = tx.QueryRowContext(ctx, `
SELECT failed_count, locked_at, last_failed_at
FROM group_join_failures
WHERE user_id = ? AND ip = ?
LIMIT 1 FOR UPDATE`, userID, ip).Scan(&failedCount, &lockedAt, &lastFailedAt)
	if err != nil {
		return false, false, err
	}
	now = time.Now()
	if lockedAt.Valid && lockedAt.Time.After(now.Add(-lockWindow)) {
		if err := tx.Commit(); err != nil {
			return false, false, err
		}
		return false, true, nil
	}
	if !lastFailedAt.Valid || lastFailedAt.Time.Before(now.Add(-lockWindow)) {
		failedCount = 1
	} else {
		failedCount += 1
	}
	var nextLockedAt any
	if failedCount >= limit {
		nextLockedAt = now
	}
	_, err = tx.ExecContext(ctx, `
UPDATE group_join_failures
SET failed_count = ?, locked_at = ?, last_failed_at = ?
WHERE user_id = ? AND ip = ?`, failedCount, nextLockedAt, now, userID, ip)
	if err != nil {
		return false, false, err
	}
	if err := tx.Commit(); err != nil {
		return false, false, err
	}
	return true, nextLockedAt != nil, nil
}

func (repo AuthLimitRepository) ClearFailedGroupJoin(ctx context.Context, userID int64, ip string) error {
	_, err := repo.db.ExecContext(ctx, "DELETE FROM group_join_failures WHERE user_id = ? AND ip = ?", userID, ip)
	return err
}
