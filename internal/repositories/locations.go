package repositories

import (
	"context"
	"database/sql"
	"errors"

	"familylocation/location-v3/internal/models"
)

var ErrLocationHistorySnapshotTooLarge = errors.New("location history snapshot exceeds the total row limit")
var ErrLocationHistorySnapshotBytesTooLarge = errors.New("location history snapshot exceeds the source byte limit")

const (
	defaultLocationHistoryLimit = 5000
	locationHistoryPruneBatch   = 500
)

type LocationRepository struct {
	db *sql.DB
}

func NewLocationRepository(db *sql.DB) LocationRepository {
	return LocationRepository{db: db}
}

func (repo LocationRepository) PruneHistoryForUserTx(ctx context.Context, tx *sql.Tx, groupName string, userID int64, limit int) error {
	if limit <= 0 {
		limit = defaultLocationHistoryLimit
	}
	_, err := tx.ExecContext(ctx, `
DELETE FROM locations
WHERE group_name = ? AND user_id = ? AND id <= COALESCE(
	(
		SELECT cutoff_id FROM (
			SELECT id AS cutoff_id
			FROM locations
			WHERE group_name = ? AND user_id = ?
			ORDER BY id DESC
			LIMIT 1 OFFSET ?
		) retention_cutoff
	),
	0
)
ORDER BY id ASC
LIMIT ?`, groupName, userID, groupName, userID, limit, locationHistoryPruneBatch)
	return err
}

func (repo LocationRepository) LatestForGroup(ctx context.Context, groupName string) ([]models.Location, error) {
	const query = `
SELECT
	COALESCE(ll.latest_location_id, 0) AS id,
	ll.user_id,
	u.username,
	COALESCE(u.display_name, ''),
	ll.group_name,
	ug.role,
	ll.latitude,
	ll.longitude,
	ll.altitude,
	ll.accuracy,
	ll.heading,
	ll.speed,
	ll.location_meta,
	ll.address_diagnostics,
	ll.address_mismatch,
	COALESCE(ll.encryption_mode, '') AS encryption_mode,
	COALESCE(ll.encrypted_payload, '') AS encrypted_payload,
	COALESCE(ll.p2p_key_version, 0) AS p2p_key_version,
	ll.updated_at AS created_at,
	ll.updated_at
FROM latest_group_locations ll
INNER JOIN users u ON u.id = ll.user_id
INNER JOIN user_groups ug ON ug.user_id = ll.user_id AND ug.group_name = ll.group_name
WHERE ll.group_name = ? AND u.is_active = 1
ORDER BY ug.role ASC, u.username ASC`

	return repo.queryLocations(ctx, query, groupName)
}

func (repo LocationRepository) HistoryByID(ctx context.Context, groupName string, userID int64, locationID int64) (*models.Location, error) {
	query := historySelect() + `
WHERE l.group_name = ? AND l.user_id = ? AND l.id = ? AND u.is_active = 1
LIMIT 1`
	locations, err := repo.queryLocations(ctx, query, groupName, userID, locationID)
	if err != nil {
		return nil, err
	}
	if len(locations) == 0 {
		return nil, nil
	}
	return &locations[0], nil
}

func (repo LocationRepository) HistoryByIDForGroup(ctx context.Context, groupName string, locationID int64) (*models.Location, error) {
	query := historySelect() + `
WHERE l.group_name = ? AND l.id = ? AND u.is_active = 1
LIMIT 1`
	locations, err := repo.queryLocations(ctx, query, groupName, locationID)
	if err != nil {
		return nil, err
	}
	if len(locations) == 0 {
		return nil, nil
	}
	return &locations[0], nil
}

func (repo LocationRepository) CountHistory(ctx context.Context, groupName string, userID int64) (int, error) {
	query := `
SELECT COUNT(*)
FROM locations l
INNER JOIN users u ON u.id = l.user_id
INNER JOIN user_groups ug ON ug.user_id = l.user_id AND ug.group_name = l.group_name
WHERE l.group_name = ? AND u.is_active = 1`
	args := []any{groupName}
	if userID > 0 {
		query += " AND l.user_id = ?"
		args = append(args, userID)
	}

	var total int
	err := repo.db.QueryRowContext(ctx, query, args...).Scan(&total)
	return total, err
}

func (repo LocationRepository) HistoryPage(ctx context.Context, groupName string, userID int64, limit int, offset int) ([]models.Location, error) {
	query := historySelect() + `
WHERE l.group_name = ? AND u.is_active = 1`
	args := []any{groupName}
	if userID > 0 {
		query += " AND l.user_id = ?"
		args = append(args, userID)
	}
	query += `
ORDER BY l.created_at DESC, l.id DESC
LIMIT ? OFFSET ?`
	args = append(args, limit, offset)
	return repo.queryLocations(ctx, query, args...)
}

func (repo LocationRepository) HistoryForUser(ctx context.Context, groupName string, userID int64, limit int) ([]models.Location, error) {
	if limit <= 0 {
		limit = defaultLocationHistoryLimit
	}
	query := historySelect() + `
WHERE l.group_name = ? AND l.user_id = ? AND u.is_active = 1
ORDER BY l.created_at DESC, l.id DESC
LIMIT ?`
	return repo.queryLocations(ctx, query, groupName, userID, limit)
}

// RetainedHistoryForUsers reads at most the configured retention window for
// each requested member. This keeps the read used for in-memory stay merging
// finite without allowing a busy member to crowd out another member's rows.
func (repo LocationRepository) RetainedHistoryForUsers(ctx context.Context, groupName string, userIDs []int64, perUserLimit int) ([]models.Location, error) {
	return repo.RetainedHistoryForUsersBounded(ctx, groupName, userIDs, perUserLimit, 0, 0)
}

// RetainedHistoryForUsersBounded applies both the per-member retention window
// and an optional total scan budget. It returns an explicit error instead of a
// partial snapshot when the total budget would be exceeded.
func (repo LocationRepository) RetainedHistoryForUsersBounded(ctx context.Context, groupName string, userIDs []int64, perUserLimit int, totalLimit int, totalSourceBytesLimit int) ([]models.Location, error) {
	if perUserLimit <= 0 {
		perUserLimit = defaultLocationHistoryLimit
	}
	seen := make(map[int64]struct{}, len(userIDs))
	locations := make([]models.Location, 0)
	loadedSourceBytes := 0
	for _, userID := range userIDs {
		if userID <= 0 {
			continue
		}
		if _, ok := seen[userID]; ok {
			continue
		}
		seen[userID] = struct{}{}
		fetchLimit := retainedHistoryFetchLimit(perUserLimit, totalLimit, len(locations))
		remainingSourceBytes := -1
		if totalSourceBytesLimit > 0 {
			remainingSourceBytes = totalSourceBytesLimit - loadedSourceBytes
		}
		rows, sourceBytes, err := repo.historyForUserBounded(ctx, groupName, userID, fetchLimit, remainingSourceBytes)
		if err != nil {
			return nil, err
		}
		if retainedHistoryExceedsTotalLimit(totalLimit, len(locations), len(rows)) {
			return nil, ErrLocationHistorySnapshotTooLarge
		}
		locations = append(locations, rows...)
		loadedSourceBytes += sourceBytes
	}
	return locations, nil
}

func (repo LocationRepository) historyForUserBounded(ctx context.Context, groupName string, userID int64, limit int, sourceBytesLimit int) ([]models.Location, int, error) {
	if limit <= 0 {
		limit = defaultLocationHistoryLimit
	}
	query := historySelect() + `
WHERE l.group_name = ? AND l.user_id = ? AND u.is_active = 1
ORDER BY l.created_at DESC, l.id DESC
LIMIT ?`
	return repo.queryLocationsBounded(ctx, sourceBytesLimit, query, groupName, userID, limit)
}

func retainedHistoryFetchLimit(perUserLimit int, totalLimit int, loaded int) int {
	if totalLimit <= 0 {
		return perUserLimit
	}
	remaining := totalLimit - loaded
	if remaining >= perUserLimit {
		return perUserLimit
	}
	fetchLimit := remaining + 1
	if fetchLimit < 1 {
		return 1
	}
	return fetchLimit
}

func retainedHistoryExceedsTotalLimit(totalLimit int, loaded int, fetched int) bool {
	return totalLimit > 0 && fetched > totalLimit-loaded
}

func historySelect() string {
	return `
SELECT
	l.id,
	l.user_id,
	u.username,
	COALESCE(u.display_name, ''),
	l.group_name,
	l.role,
	l.latitude,
	l.longitude,
	l.altitude,
	l.accuracy,
	l.heading,
	l.speed,
	l.location_meta,
	l.address_diagnostics,
	l.address_mismatch,
	COALESCE(l.encryption_mode, '') AS encryption_mode,
	COALESCE(l.encrypted_payload, '') AS encrypted_payload,
	COALESCE(l.p2p_key_version, 0) AS p2p_key_version,
	l.created_at,
	l.created_at AS updated_at
FROM locations l
INNER JOIN users u ON u.id = l.user_id
INNER JOIN user_groups ug ON ug.user_id = l.user_id AND ug.group_name = l.group_name
`
}

func (repo LocationRepository) queryLocations(ctx context.Context, query string, args ...any) ([]models.Location, error) {
	locations, _, err := repo.queryLocationsBounded(ctx, -1, query, args...)
	return locations, err
}

func (repo LocationRepository) queryLocationsBounded(ctx context.Context, sourceBytesLimit int, query string, args ...any) ([]models.Location, int, error) {
	rows, err := repo.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	var locations []models.Location
	loadedSourceBytes := 0
	for rows.Next() {
		var location models.Location
		if err := rows.Scan(
			&location.ID,
			&location.UserID,
			&location.Username,
			&location.DisplayName,
			&location.GroupName,
			&location.Role,
			&location.Latitude,
			&location.Longitude,
			&location.Altitude,
			&location.Accuracy,
			&location.Heading,
			&location.Speed,
			&location.LocationMeta,
			&location.AddressDiagnostics,
			&location.AddressMismatch,
			&location.EncryptionMode,
			&location.EncryptedPayload,
			&location.P2PKeyVersion,
			&location.CreatedAt,
			&location.UpdatedAt,
		); err != nil {
			return nil, 0, err
		}
		rowSourceBytes := locationHistorySourceBytes(location)
		if sourceBytesLimit >= 0 && rowSourceBytes > sourceBytesLimit-loadedSourceBytes {
			return nil, 0, ErrLocationHistorySnapshotBytesTooLarge
		}
		loadedSourceBytes += rowSourceBytes
		locations = append(locations, location)
	}
	if err := rows.Err(); err != nil {
		return nil, 0, err
	}
	return locations, loadedSourceBytes, nil
}

func locationHistorySourceBytes(location models.Location) int {
	const fixedModelBytes = 512
	return fixedModelBytes +
		len(location.Username) +
		len(location.DisplayName) +
		len(location.GroupName) +
		len(location.Role) +
		len(location.LocationMeta.String) +
		len(location.AddressDiagnostics.String) +
		len(location.EncryptionMode) +
		len(location.EncryptedPayload)
}
