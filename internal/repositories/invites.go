package repositories

import (
	"context"
	"database/sql"

	"familylocation/location-v3/internal/models"
)

type InviteRepository struct {
	db *sql.DB
}

func NewInviteRepository(db *sql.DB) InviteRepository {
	return InviteRepository{db: db}
}

func (repo InviteRepository) FindUsableByCode(ctx context.Context, code string) (*models.InviteCode, error) {
	const query = `
SELECT id, code, invite_type, COALESCE(assigned_group_name, ''), used_count, max_uses, is_active
FROM invite_codes
WHERE code = ? AND is_active = 1
LIMIT 1`

	var invite models.InviteCode
	err := repo.db.QueryRowContext(ctx, query, code).Scan(
		&invite.ID,
		&invite.Code,
		&invite.InviteType,
		&invite.AssignedGroupName,
		&invite.UsedCount,
		&invite.MaxUses,
		&invite.IsActive,
	)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if invite.UsedCount >= invite.MaxUses {
		return nil, nil
	}
	return &invite, nil
}

