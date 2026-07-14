package repositories

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

type SupportTicketMaintenanceRepository struct {
	db *sql.DB
}

func NewSupportTicketMaintenanceRepository(db *sql.DB) SupportTicketMaintenanceRepository {
	return SupportTicketMaintenanceRepository{db: db}
}

func (repo SupportTicketMaintenanceRepository) DeleteClosedBatch(ctx context.Context, cutoff time.Time, limit int) (int64, error) {
	if limit <= 0 {
		return 0, errors.New("support ticket cleanup limit must be positive")
	}
	result, err := repo.db.ExecContext(ctx, `
DELETE FROM support_tickets
WHERE status = 'closed' AND updated_at <= ?
ORDER BY updated_at ASC, id ASC
LIMIT ?`, cutoff, limit)
	if err != nil {
		return 0, err
	}
	return result.RowsAffected()
}

func (repo SupportTicketMaintenanceRepository) DeleteClosedBatches(ctx context.Context, cutoff time.Time, batchSize int, maxBatches int) (int64, error) {
	if batchSize <= 0 || maxBatches <= 0 {
		return 0, errors.New("support ticket cleanup bounds must be positive")
	}
	var total int64
	for batch := 0; batch < maxBatches; batch++ {
		deleted, err := repo.DeleteClosedBatch(ctx, cutoff, batchSize)
		if err != nil {
			return total, err
		}
		total += deleted
		if deleted < int64(batchSize) {
			break
		}
	}
	return total, nil
}
