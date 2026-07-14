package repositories

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

var (
	ErrLocationShareAuthorization = errors.New("location share authorization no longer valid")
	ErrLocationShareUserQuota     = errors.New("location share user quota exceeded")
	ErrLocationShareGroupQuota    = errors.New("location share group quota exceeded")
)

type LocationShare struct {
	TokenHash           string
	TokenPlaintext      string
	OwnerUserID         int64
	GroupName           string
	LocationIDsJSON     string
	SnapshotJSON        string
	AccessCodeHash      string
	AccessCodePlaintext string
	ExpiresAt           time.Time
	CreatedAt           time.Time
}

type LocationShareRepository struct {
	db *sql.DB
}

func NewLocationShareRepository(db *sql.DB) LocationShareRepository {
	return LocationShareRepository{db: db}
}

func (repo LocationShareRepository) CreateWithinQuota(ctx context.Context, share LocationShare, now time.Time, userLimit int, groupLimit int) error {
	if userLimit < 1 || groupLimit < 1 {
		return errors.New("location share quota must be positive")
	}
	tx, err := repo.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()

	// All share creators take these stable locks in the same order. This makes
	// the quota checks and insert one serialized operation for both scopes.
	var lockedGroup string
	if err := tx.QueryRowContext(ctx, `
SELECT group_name
FROM family_groups
WHERE group_name = ?
LIMIT 1
FOR UPDATE`, share.GroupName).Scan(&lockedGroup); err != nil {
		if err == sql.ErrNoRows {
			return ErrLocationShareAuthorization
		}
		return err
	}
	var lockedUserID int64
	if err := tx.QueryRowContext(ctx, `
SELECT id
FROM users
WHERE id = ? AND is_active = 1
LIMIT 1
FOR UPDATE`, share.OwnerUserID).Scan(&lockedUserID); err != nil {
		if err == sql.ErrNoRows {
			return ErrLocationShareAuthorization
		}
		return err
	}
	var membershipID int64
	if err := tx.QueryRowContext(ctx, `
SELECT id
FROM user_groups
WHERE user_id = ? AND group_name = ?
LIMIT 1
FOR UPDATE`, share.OwnerUserID, share.GroupName).Scan(&membershipID); err != nil {
		if err == sql.ErrNoRows {
			return ErrLocationShareAuthorization
		}
		return err
	}

	var activeOwned int
	if err := tx.QueryRowContext(ctx, `
SELECT COUNT(*)
FROM location_shares
WHERE owner_user_id = ? AND expires_at > ?`, share.OwnerUserID, now).Scan(&activeOwned); err != nil {
		return err
	}
	if activeOwned >= userLimit {
		return ErrLocationShareUserQuota
	}

	var activeGroup int
	if err := tx.QueryRowContext(ctx, `
SELECT COUNT(*)
FROM location_shares
WHERE group_name = ? AND expires_at > ?`, share.GroupName, now).Scan(&activeGroup); err != nil {
		return err
	}
	if activeGroup >= groupLimit {
		return ErrLocationShareGroupQuota
	}

	_, err = tx.ExecContext(ctx, `
INSERT INTO location_shares (
	token_hash, token_plaintext, owner_user_id, group_name, location_ids_json, snapshot_json,
	access_code_hash, access_code_plaintext, expires_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		share.TokenHash,
		share.TokenPlaintext,
		share.OwnerUserID,
		share.GroupName,
		share.LocationIDsJSON,
		nullableShareSnapshot(share.SnapshotJSON),
		share.AccessCodeHash,
		share.AccessCodePlaintext,
		share.ExpiresAt,
	)
	if err != nil {
		return err
	}
	return tx.Commit()
}

func (repo LocationShareRepository) FindActive(ctx context.Context, tokenHash string, now time.Time) (*LocationShare, error) {
	var share LocationShare
	err := repo.db.QueryRowContext(ctx, `
SELECT token_hash, token_plaintext, owner_user_id, group_name, location_ids_json,
	COALESCE(snapshot_json, ''), access_code_hash, access_code_plaintext, expires_at, created_at
FROM location_shares
WHERE token_hash = ? AND expires_at > ?
LIMIT 1`, tokenHash, now).Scan(
		&share.TokenHash,
		&share.TokenPlaintext,
		&share.OwnerUserID,
		&share.GroupName,
		&share.LocationIDsJSON,
		&share.SnapshotJSON,
		&share.AccessCodeHash,
		&share.AccessCodePlaintext,
		&share.ExpiresAt,
		&share.CreatedAt,
	)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &share, nil
}

func (repo LocationShareRepository) ListOwned(ctx context.Context, ownerUserID int64, groupName string, limit int, offset int) ([]LocationShare, error) {
	rows, err := repo.db.QueryContext(ctx, `
SELECT token_hash, token_plaintext, owner_user_id, group_name, location_ids_json,
	COALESCE(snapshot_json, ''), access_code_hash, access_code_plaintext, expires_at, created_at
FROM location_shares
WHERE owner_user_id = ? AND group_name = ?
ORDER BY created_at DESC, token_hash DESC
LIMIT ? OFFSET ?`, ownerUserID, groupName, limit, offset)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	shares := make([]LocationShare, 0)
	for rows.Next() {
		var share LocationShare
		if err := rows.Scan(
			&share.TokenHash,
			&share.TokenPlaintext,
			&share.OwnerUserID,
			&share.GroupName,
			&share.LocationIDsJSON,
			&share.SnapshotJSON,
			&share.AccessCodeHash,
			&share.AccessCodePlaintext,
			&share.ExpiresAt,
			&share.CreatedAt,
		); err != nil {
			return nil, err
		}
		shares = append(shares, share)
	}
	return shares, rows.Err()
}

func (repo LocationShareRepository) DeleteExpired(ctx context.Context, now time.Time) error {
	_, err := repo.db.ExecContext(ctx, "DELETE FROM location_shares WHERE expires_at <= ?", now)
	return err
}

func (repo LocationShareRepository) DeleteExpiredBatch(ctx context.Context, now time.Time, limit int) (int64, error) {
	if limit < 1 {
		return 0, errors.New("location share cleanup limit must be positive")
	}
	result, err := repo.db.ExecContext(ctx, `
DELETE FROM location_shares
WHERE expires_at <= ?
ORDER BY expires_at
LIMIT ?`, now, limit)
	if err != nil {
		return 0, err
	}
	return result.RowsAffected()
}

func (repo LocationShareRepository) DeleteExpiredBatches(ctx context.Context, now time.Time, batchSize int, maxBatches int) (int64, error) {
	if batchSize < 1 || maxBatches < 1 {
		return 0, errors.New("location share cleanup bounds must be positive")
	}
	var total int64
	for range maxBatches {
		deleted, err := repo.DeleteExpiredBatch(ctx, now, batchSize)
		total += deleted
		if err != nil {
			return total, err
		}
		if deleted < int64(batchSize) {
			return total, nil
		}
	}
	return total, nil
}

func nullableShareSnapshot(value string) any {
	if value == "" {
		return nil
	}
	return value
}
