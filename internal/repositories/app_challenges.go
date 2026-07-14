package repositories

import (
	"context"
	"database/sql"
	"errors"
	"sync"
	"time"

	"familylocation/location-v3/internal/models"
)

type AppChallengeRepository struct {
	db      *sql.DB
	cleanup *appChallengeCleanupState
}

const expiredChallengeCleanupBatch = 500

type appChallengeCleanupState struct {
	mu          sync.Mutex
	running     bool
	nextCleanup time.Time
}

func NewAppChallengeRepository(db *sql.DB) AppChallengeRepository {
	return AppChallengeRepository{db: db, cleanup: &appChallengeCleanupState{}}
}

func (repo AppChallengeRepository) Insert(ctx context.Context, challenge models.AppChallenge) error {
	tx, err := repo.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	if _, err := deleteExpiredChallengesTx(ctx, tx, time.Now(), expiredChallengeCleanupBatch); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `
INSERT INTO app_challenges (id, secret_hash, device_fingerprint, purpose, expires_at)
VALUES (?, ?, ?, ?, ?)`,
		challenge.ID, challenge.SecretHash, challenge.DeviceFingerprint, challenge.Purpose, challenge.ExpiresAt); err != nil {
		return err
	}
	return tx.Commit()
}

func (repo AppChallengeRepository) DeleteExpiredBatch(ctx context.Context, now time.Time, limit int) (int64, error) {
	if limit <= 0 {
		return 0, errors.New("app challenge cleanup limit must be positive")
	}
	result, err := repo.db.ExecContext(ctx, "DELETE FROM app_challenges WHERE expires_at <= ? ORDER BY expires_at ASC LIMIT ?", now, limit)
	if err != nil {
		return 0, err
	}
	return result.RowsAffected()
}

func (repo AppChallengeRepository) DeleteExpiredBatches(ctx context.Context, now time.Time, batchSize int, maxBatches int) (int64, error) {
	if batchSize <= 0 || maxBatches <= 0 {
		return 0, errors.New("app challenge cleanup bounds must be positive")
	}
	var total int64
	for batch := 0; batch < maxBatches; batch++ {
		deleted, err := repo.DeleteExpiredBatch(ctx, now, batchSize)
		if err != nil {
			return total, err
		}
		total += deleted
		if deleted < int64(batchSize) {
			break
		}
	}
	return total, nil
}

func (repo AppChallengeRepository) DeleteExpiredIfDue(ctx context.Context, now time.Time, interval time.Duration, batchSize int) error {
	if repo.cleanup == nil {
		_, err := repo.DeleteExpiredBatch(ctx, now, batchSize)
		return err
	}
	repo.cleanup.mu.Lock()
	if repo.cleanup.running || now.Before(repo.cleanup.nextCleanup) {
		repo.cleanup.mu.Unlock()
		return nil
	}
	repo.cleanup.running = true
	repo.cleanup.mu.Unlock()

	deleted, err := repo.DeleteExpiredBatch(ctx, now, batchSize)
	repo.cleanup.mu.Lock()
	repo.cleanup.running = false
	if err != nil || deleted == int64(batchSize) {
		repo.cleanup.nextCleanup = now
	} else {
		repo.cleanup.nextCleanup = now.Add(interval)
	}
	repo.cleanup.mu.Unlock()
	return err
}

func deleteExpiredChallengesTx(ctx context.Context, tx *sql.Tx, now time.Time, limit int) (int64, error) {
	result, err := tx.ExecContext(ctx, "DELETE FROM app_challenges WHERE expires_at <= ? ORDER BY expires_at ASC LIMIT ?", now, limit)
	if err != nil {
		return 0, err
	}
	return result.RowsAffected()
}

func (repo AppChallengeRepository) FindByIDAndDevice(ctx context.Context, id string, deviceFingerprint string) (*models.AppChallenge, error) {
	var challenge models.AppChallenge
	err := repo.db.QueryRowContext(ctx, `
SELECT id, secret_hash, device_fingerprint, purpose, verified_at, consumed_at, expires_at
FROM app_challenges
WHERE id = ? AND device_fingerprint = ?
LIMIT 1`, id, deviceFingerprint).Scan(
		&challenge.ID,
		&challenge.SecretHash,
		&challenge.DeviceFingerprint,
		&challenge.Purpose,
		&challenge.VerifiedAt,
		&challenge.ConsumedAt,
		&challenge.ExpiresAt,
	)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &challenge, nil
}

func (repo AppChallengeRepository) FindVerifiedConsumable(ctx context.Context, id string, purpose string, deviceFingerprint string) (*models.AppChallenge, error) {
	var challenge models.AppChallenge
	err := repo.db.QueryRowContext(ctx, `
SELECT id, secret_hash, device_fingerprint, purpose, verified_at, consumed_at, expires_at
FROM app_challenges
WHERE id = ? AND purpose = ? AND device_fingerprint = ? AND verified_at IS NOT NULL AND consumed_at IS NULL AND expires_at > NOW()
LIMIT 1`, id, purpose, deviceFingerprint).Scan(
		&challenge.ID,
		&challenge.SecretHash,
		&challenge.DeviceFingerprint,
		&challenge.Purpose,
		&challenge.VerifiedAt,
		&challenge.ConsumedAt,
		&challenge.ExpiresAt,
	)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &challenge, nil
}

func (repo AppChallengeRepository) MarkVerified(ctx context.Context, id string, deviceFingerprint string) error {
	_, err := repo.db.ExecContext(ctx, "UPDATE app_challenges SET verified_at = NOW() WHERE id = ? AND device_fingerprint = ? AND verified_at IS NULL", id, deviceFingerprint)
	return err
}

func (repo AppChallengeRepository) Consume(ctx context.Context, id string) (bool, error) {
	result, err := repo.db.ExecContext(ctx, "UPDATE app_challenges SET consumed_at = NOW() WHERE id = ? AND consumed_at IS NULL", id)
	if err != nil {
		return false, err
	}
	rows, err := result.RowsAffected()
	return rows == 1, err
}

func ChallengeExpired(challenge *models.AppChallenge, now time.Time) bool {
	return challenge == nil || !challenge.ExpiresAt.After(now)
}
