package repositories

import (
	"context"
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

func (repo *SessionRepository) Upsert(ctx context.Context, sessionID string, userID *int64, adminLoggedIn bool, expiresAt time.Time) error {
	var nullableUserID any
	if userID != nil && *userID > 0 {
		nullableUserID = *userID
	}
	_, err := repo.db.ExecContext(ctx, `
INSERT INTO app_sessions (session_id, user_id, admin_logged_in, expires_at)
VALUES (?, ?, ?, ?)
ON DUPLICATE KEY UPDATE
	user_id = VALUES(user_id),
	admin_logged_in = VALUES(admin_logged_in),
	expires_at = VALUES(expires_at),
	updated_at = CURRENT_TIMESTAMP`,
		sessionID, nullableUserID, adminLoggedIn, expiresAt)
	return err
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
