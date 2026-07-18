package repositories

import (
	"context"
	"database/sql"

	"familylocation/location-v3/internal/models"
)

type GroupRepository struct {
	db *sql.DB
}

func NewGroupRepository(db *sql.DB) GroupRepository {
	return GroupRepository{db: db}
}

func (repo GroupRepository) ForUser(ctx context.Context, userID int64) ([]models.Membership, error) {
	const query = `
SELECT
	ug.id,
	ug.user_id,
	ug.group_name,
	ug.role,
	COALESCE(fg.group_code, ''),
	COALESCE(fg.display_name, ug.group_name),
	COALESCE(fg.owner_user_id, 0),
	fg.p2p_enabled_at,
	COALESCE(fg.p2p_key_version, 0)
FROM user_groups ug
LEFT JOIN family_groups fg ON fg.group_name = ug.group_name
WHERE ug.user_id = ?
ORDER BY ug.group_name ASC, ug.id ASC`

	rows, err := repo.db.QueryContext(ctx, query, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var groups []models.Membership
	for rows.Next() {
		var group models.Membership
		if err := rows.Scan(
			&group.ID,
			&group.UserID,
			&group.GroupName,
			&group.Role,
			&group.GroupCode,
			&group.GroupDisplayName,
			&group.OwnerUserID,
			&group.P2PEnabledAt,
			&group.P2PKeyVersion,
		); err != nil {
			return nil, err
		}
		groups = append(groups, group)
	}
	return groups, rows.Err()
}

func (repo GroupRepository) MembershipForUser(ctx context.Context, userID int64, groupName string) (*models.Membership, error) {
	groups, err := repo.ForUser(ctx, userID)
	if err != nil {
		return nil, err
	}
	if groupName == "" {
		if len(groups) == 0 {
			return nil, nil
		}
		return &groups[0], nil
	}
	for _, group := range groups {
		if group.GroupName == groupName {
			selected := group
			return &selected, nil
		}
	}
	return nil, nil
}

func (repo GroupRepository) Members(ctx context.Context, groupName string) ([]models.GroupMember, error) {
	return repo.membersQuery(ctx, `
SELECT u.id AS user_id, u.username, u.display_name, ug.role
FROM user_groups ug
INNER JOIN users u ON u.id = ug.user_id
WHERE ug.group_name = ? AND u.is_active = 1
		ORDER BY ug.role ASC, u.username ASC`, groupName)
}

// MembersBounded reads one extra row when callers need an exact limit/overflow
// decision without materializing an unbounded group membership list.
func (repo GroupRepository) MembersBounded(ctx context.Context, groupName string, limit int) ([]models.GroupMember, error) {
	if limit < 1 {
		limit = 1
	}
	return repo.membersQuery(ctx, `
SELECT u.id AS user_id, u.username, u.display_name, ug.role
FROM user_groups ug
INNER JOIN users u ON u.id = ug.user_id
WHERE ug.group_name = ? AND u.is_active = 1
ORDER BY ug.role ASC, u.username ASC
LIMIT ?`, groupName, limit)
}

func (repo GroupRepository) MembersPage(ctx context.Context, groupName string, limit int, offset int) ([]models.GroupMember, error) {
	if limit < 1 {
		limit = 1
	}
	if offset < 0 {
		offset = 0
	}
	return repo.membersQuery(ctx, `
SELECT u.id AS user_id, u.username, u.display_name, ug.role
FROM user_groups ug
INNER JOIN users u ON u.id = ug.user_id
WHERE ug.group_name = ? AND u.is_active = 1
ORDER BY ug.role ASC, u.username ASC
LIMIT ? OFFSET ?`, groupName, limit, offset)
}

func (repo GroupRepository) CountMembers(ctx context.Context, groupName string) (int, error) {
	var count int
	err := repo.db.QueryRowContext(ctx, `
SELECT COUNT(*)
FROM user_groups ug
INNER JOIN users u ON u.id = ug.user_id
WHERE ug.group_name = ? AND u.is_active = 1`, groupName).Scan(&count)
	return count, err
}

func (repo GroupRepository) Member(ctx context.Context, groupName string, userID int64) (*models.GroupMember, error) {
	rows, err := repo.membersQuery(ctx, `
SELECT u.id AS user_id, u.username, u.display_name, ug.role
FROM user_groups ug
INNER JOIN users u ON u.id = ug.user_id
WHERE ug.group_name = ? AND ug.user_id = ? AND u.is_active = 1
LIMIT 1`, groupName, userID)
	if err != nil {
		return nil, err
	}
	if len(rows) == 0 {
		return nil, nil
	}
	return &rows[0], nil
}

func (repo GroupRepository) membersQuery(ctx context.Context, query string, args ...any) ([]models.GroupMember, error) {
	rows, err := repo.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var members []models.GroupMember
	for rows.Next() {
		var member models.GroupMember
		if err := rows.Scan(&member.UserID, &member.Username, &member.DisplayName, &member.Role); err != nil {
			return nil, err
		}
		members = append(members, member)
	}
	return members, rows.Err()
}
