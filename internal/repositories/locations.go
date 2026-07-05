package repositories

import (
	"context"
	"database/sql"

	"familylocation/location-v3/internal/models"
)

type LocationRepository struct {
	db *sql.DB
}

func NewLocationRepository(db *sql.DB) LocationRepository {
	return LocationRepository{db: db}
}

func (repo LocationRepository) LatestForGroup(ctx context.Context, groupName string) ([]models.Location, error) {
	const query = `
SELECT
	0 AS id,
	ll.user_id,
	u.username,
	u.display_name,
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
	ll.encryption_mode,
	ll.encrypted_payload,
	ll.p2p_key_version,
	ll.updated_at AS created_at,
	ll.updated_at
FROM latest_group_locations ll
INNER JOIN users u ON u.id = ll.user_id
INNER JOIN user_groups ug ON ug.user_id = ll.user_id AND ug.group_name = ll.group_name
WHERE ll.group_name = ? AND u.is_active = 1
ORDER BY ug.role ASC, u.username ASC`

	return repo.queryLocations(ctx, query, groupName)
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
	u.display_name,
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
	l.encryption_mode,
	l.encrypted_payload,
	l.p2p_key_version,
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

