package repositories

import (
	"context"
	"crypto/subtle"
	"database/sql"
	"sync"
	"time"
)

type SessionRecord struct {
	SessionID     string
	UserID        sql.NullInt64
	AdminLoggedIn bool
	ExpiresAt     time.Time
}

type SessionRepository struct {
	db          *sql.DB
	cleanupMu   sync.Mutex
	nextCleanup time.Time
}

func NewSessionRepository(db *sql.DB) *SessionRepository {
	return &SessionRepository{db: db}
}

func (repo *SessionRepository) UpsertAdmin(ctx context.Context, sessionID string, expiresAt time.Time) error {
	_, err := repo.db.ExecContext(ctx, `
INSERT INTO app_sessions (session_id, user_id, admin_logged_in, expires_at)
VALUES (?, NULL, 1, ?)
ON DUPLICATE KEY UPDATE
	user_id = NULL,
	admin_logged_in = 1,
	expires_at = VALUES(expires_at),
	updated_at = CURRENT_TIMESTAMP`,
		sessionID, expiresAt)
	return err
}

func (repo *SessionRepository) CreateUserSessionIfPasswordMatches(ctx context.Context, sessionID string, userID int64, expectedPasswordHash string, expiresAt time.Time) (bool, error) {
	tx, err := repo.db.BeginTx(ctx, nil)
	if err != nil {
		return false, err
	}
	defer func() { _ = tx.Rollback() }()

	var currentPasswordHash string
	err = tx.QueryRowContext(ctx, `
SELECT password_hash
FROM users
WHERE id = ? AND is_active = 1
LIMIT 1 FOR UPDATE`, userID).Scan(&currentPasswordHash)
	if err == sql.ErrNoRows {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if subtle.ConstantTimeCompare([]byte(currentPasswordHash), []byte(expectedPasswordHash)) != 1 {
		return false, nil
	}
	_, err = tx.ExecContext(ctx, `
INSERT INTO app_sessions (session_id, user_id, admin_logged_in, expires_at)
VALUES (?, ?, 0, ?)
ON DUPLICATE KEY UPDATE
	user_id = VALUES(user_id),
	admin_logged_in = 0,
	expires_at = VALUES(expires_at),
	updated_at = CURRENT_TIMESTAMP`, sessionID, userID, expiresAt)
	if err != nil {
		return false, err
	}
	if err := tx.Commit(); err != nil {
		return false, err
	}
	return true, nil
}

func (repo *SessionRepository) FindActive(ctx context.Context, sessionID string, now time.Time) (*SessionRecord, error) {
	var record SessionRecord
	err := repo.db.QueryRowContext(ctx, `
SELECT session_id, user_id, admin_logged_in, expires_at
FROM app_sessions
WHERE session_id = ? AND expires_at > ?
LIMIT 1`, sessionID, now).Scan(&record.SessionID, &record.UserID, &record.AdminLoggedIn, &record.ExpiresAt)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &record, nil
}

func (repo *SessionRepository) Delete(ctx context.Context, sessionID string) error {
	_, err := repo.db.ExecContext(ctx, "DELETE FROM app_sessions WHERE session_id = ?", sessionID)
	return err
}

func (repo *SessionRepository) UpdatePasswordAndRevokeUserSessions(ctx context.Context, userID int64, passwordHash string) error {
	tx, err := repo.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()

	if err := updatePasswordAndDeleteUserSessions(ctx, tx, userID, passwordHash); err != nil {
		return err
	}
	return tx.Commit()
}

func (repo *SessionRepository) ChangePasswordAndRevokeUserSessions(ctx context.Context, userID int64, expectedPasswordHash string, newPasswordHash string) (bool, error) {
	tx, err := repo.db.BeginTx(ctx, nil)
	if err != nil {
		return false, err
	}
	defer func() { _ = tx.Rollback() }()

	var currentPasswordHash string
	err = tx.QueryRowContext(ctx, `
SELECT password_hash
FROM users
WHERE id = ?
LIMIT 1 FOR UPDATE`, userID).Scan(&currentPasswordHash)
	if err == sql.ErrNoRows {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if subtle.ConstantTimeCompare([]byte(currentPasswordHash), []byte(expectedPasswordHash)) != 1 {
		return false, nil
	}
	if err := updatePasswordAndDeleteUserSessions(ctx, tx, userID, newPasswordHash); err != nil {
		return false, err
	}
	if err := tx.Commit(); err != nil {
		return false, err
	}
	return true, nil
}

func updatePasswordAndDeleteUserSessions(ctx context.Context, tx *sql.Tx, userID int64, passwordHash string) error {
	if _, err := tx.ExecContext(ctx, "UPDATE users SET password_hash = ? WHERE id = ?", passwordHash, userID); err != nil {
		return err
	}
	_, err := tx.ExecContext(ctx, "DELETE FROM app_sessions WHERE user_id = ?", userID)
	return err
}

func (repo *SessionRepository) DeleteExpired(ctx context.Context, now time.Time) error {
	_, err := repo.db.ExecContext(ctx, "DELETE FROM app_sessions WHERE expires_at <= ?", now)
	return err
}

func (repo *SessionRepository) DeleteExpiredIfDue(ctx context.Context, now time.Time, interval time.Duration) {
	repo.cleanupMu.Lock()
	if now.Before(repo.nextCleanup) {
		repo.cleanupMu.Unlock()
		return
	}
	repo.nextCleanup = now.Add(interval)
	repo.cleanupMu.Unlock()

	_ = repo.DeleteExpired(ctx, now)
}
