package repositories

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"sync"
	"time"
)

type RateLimitRepository struct {
	db      *sql.DB
	cleanup *rateLimitCleanupState
}

type rateLimitCleanupState struct {
	mu               sync.Mutex
	nextCleanup      time.Time
	hitsSinceCleanup int
}

const (
	rateLimitCleanupInterval = time.Hour
	rateLimitCleanupAge      = 25 * time.Hour
	rateLimitCleanupEvery    = 256
	rateLimitCleanupBatch    = 1024
)

var rateLimitCleanupStates sync.Map

func NewRateLimitRepository(db *sql.DB) RateLimitRepository {
	state, _ := rateLimitCleanupStates.LoadOrStore(db, &rateLimitCleanupState{nextCleanup: time.Now().Add(rateLimitCleanupInterval)})
	return RateLimitRepository{db: db, cleanup: state.(*rateLimitCleanupState)}
}

func (repo RateLimitRepository) Hit(ctx context.Context, bucket string, identity string, maxHits int, window time.Duration) (bool, error) {
	identityHash := rateLimitIdentityHash(bucket, identity)

	tx, err := repo.db.BeginTx(ctx, nil)
	if err != nil {
		return false, err
	}
	defer func() { _ = tx.Rollback() }()

	_, err = tx.ExecContext(ctx, `
INSERT INTO api_rate_limits (bucket, identity_hash, window_started_at, hit_count)
VALUES (?, ?, NOW(), 0)
ON DUPLICATE KEY UPDATE bucket = VALUES(bucket)`, bucket, identityHash)
	if err != nil {
		return false, err
	}

	var startedAt time.Time
	var hitCount int
	err = tx.QueryRowContext(ctx, `
SELECT window_started_at, hit_count
FROM api_rate_limits
WHERE bucket = ? AND identity_hash = ?
LIMIT 1 FOR UPDATE`, bucket, identityHash).Scan(&startedAt, &hitCount)
	if err != nil {
		return false, err
	}

	if startedAt.Before(time.Now().Add(-window)) {
		hitCount = 1
		_, err = tx.ExecContext(ctx, `
UPDATE api_rate_limits
SET window_started_at = NOW(), hit_count = ?, updated_at = NOW()
WHERE bucket = ? AND identity_hash = ?`, hitCount, bucket, identityHash)
	} else {
		hitCount++
		_, err = tx.ExecContext(ctx, `
UPDATE api_rate_limits
SET hit_count = ?, updated_at = NOW()
WHERE bucket = ? AND identity_hash = ?`, hitCount, bucket, identityHash)
	}
	if err != nil {
		return false, err
	}
	if err := tx.Commit(); err != nil {
		return false, err
	}
	repo.deleteStaleIfDue(ctx, time.Now())
	return hitCount <= maxHits, nil
}

func (repo RateLimitRepository) Clear(ctx context.Context, bucket string, identity string) error {
	_, err := repo.db.ExecContext(ctx, "DELETE FROM api_rate_limits WHERE bucket = ? AND identity_hash = ?", bucket, rateLimitIdentityHash(bucket, identity))
	return err
}

func (repo RateLimitRepository) DeleteStale(ctx context.Context, before time.Time, limit int) (int64, error) {
	if limit <= 0 {
		return 0, nil
	}
	result, err := repo.db.ExecContext(ctx, "DELETE FROM api_rate_limits WHERE updated_at < ? ORDER BY updated_at ASC LIMIT ?", before, limit)
	if err != nil {
		return 0, err
	}
	return result.RowsAffected()
}

func (repo RateLimitRepository) deleteStaleIfDue(ctx context.Context, now time.Time) {
	if repo.cleanup == nil {
		return
	}
	repo.cleanup.mu.Lock()
	repo.cleanup.hitsSinceCleanup++
	if now.Before(repo.cleanup.nextCleanup) && repo.cleanup.hitsSinceCleanup < rateLimitCleanupEvery {
		repo.cleanup.mu.Unlock()
		return
	}
	repo.cleanup.nextCleanup = now.Add(rateLimitCleanupInterval)
	repo.cleanup.hitsSinceCleanup = 0
	repo.cleanup.mu.Unlock()
	_, _ = repo.DeleteStale(ctx, now.Add(-rateLimitCleanupAge), rateLimitCleanupBatch)
}

func rateLimitIdentityHash(bucket string, identity string) string {
	sum := sha256.Sum256([]byte(bucket + "|" + identity))
	return hex.EncodeToString(sum[:])
}
