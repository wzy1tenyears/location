package repositories

import (
	"context"
	"database/sql"

	"familylocation/location-v3/internal/models"
)

type AnnouncementRepository struct {
	db *sql.DB
}

func NewAnnouncementRepository(db *sql.DB) AnnouncementRepository {
	return AnnouncementRepository{db: db}
}

func (repo AnnouncementRepository) LatestActive(ctx context.Context) (*models.Announcement, error) {
	const query = `
SELECT id, title, body, version, updated_at
FROM announcements
WHERE is_active = 1 AND body <> ''
ORDER BY updated_at DESC, id DESC
LIMIT 1`

	var announcement models.Announcement
	err := repo.db.QueryRowContext(ctx, query).Scan(
		&announcement.ID,
		&announcement.Title,
		&announcement.Body,
		&announcement.Version,
		&announcement.UpdatedAt,
	)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &announcement, nil
}
