package repositories

import (
	"context"
	"database/sql"

	"familylocation/location-v3/internal/models"
)

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
	query := historySelect() + `
WHERE l.group_name = ? AND l.user_id = ? AND u.is_active = 1
ORDER BY l.created_at DESC, l.id DESC
LIMIT ?`
	return repo.queryLocations(ctx, query, groupName, userID, limit)
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
	rows, err := repo.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var locations []models.Location
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
			return nil, err
		}
		locations = append(locations, location)
	}
	return locations, rows.Err()
}
