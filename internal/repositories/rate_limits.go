package repositories

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"time"
)

type RateLimitRepository struct {
	db *sql.DB
}

func NewRateLimitRepository(db *sql.DB) RateLimitRepository {
	return RateLimitRepository{db: db}
}

func (repo RateLimitRepository) Hit(ctx context.Context, bucket string, identity string, maxHits int, window time.Duration) (bool, error) {
	sum := sha256.Sum256([]byte(bucket + "|" + identity))
	identityHash := hex.EncodeToString(sum[:])

	var startedAt time.Time
	var hitCount int
	err := repo.db.QueryRowContext(ctx, "SELECT window_started_at, hit_count FROM api_rate_limits WHERE bucket = ? AND identity_hash = ? LIMIT 1", bucket, identityHash).Scan(&startedAt, &hitCount)
	if err == sql.ErrNoRows || startedAt.Before(time.Now().Add(-window)) {
		_, execErr := repo.db.ExecContext(ctx, `
INSERT INTO api_rate_limits (bucket, identity_hash, window_started_at, hit_count)
VALUES (?, ?, NOW(), 1)
ON DUPLICATE KEY UPDATE window_started_at = VALUES(window_started_at), hit_count = 1, updated_at = NOW()`, bucket, identityHash)
		return true, execErr
	}
	if err != nil {
		return false, err
	}

	nextHitCount := hitCount + 1
	_, execErr := repo.db.ExecContext(ctx, "UPDATE api_rate_limits SET hit_count = ?, updated_at = NOW() WHERE bucket = ? AND identity_hash = ?", nextHitCount, bucket, identityHash)
	if execErr != nil {
		return false, execErr
	}
	return nextHitCount <= maxHits, nil
}
