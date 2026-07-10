package repositories

import (
	"context"
	"database/sql"
	"time"

	"familylocation/location-v3/internal/models"
)

type AppChallengeRepository struct {
	db *sql.DB
}

func NewAppChallengeRepository(db *sql.DB) AppChallengeRepository {
	return AppChallengeRepository{db: db}
}

func (repo AppChallengeRepository) Insert(ctx context.Context, challenge models.AppChallenge) error {
	_, err := repo.db.ExecContext(ctx, `
INSERT INTO app_challenges (id, secret_hash, device_fingerprint, purpose, expires_at)
VALUES (?, ?, ?, ?, ?)`,
		challenge.ID, challenge.SecretHash, challenge.DeviceFingerprint, challenge.Purpose, challenge.ExpiresAt)
	return err
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
