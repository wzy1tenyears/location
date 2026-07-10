package repositories

import (
	"context"
	"database/sql"
)

type EnvironmentReportRepository struct {
	db *sql.DB
}

func NewEnvironmentReportRepository(db *sql.DB) EnvironmentReportRepository {
	return EnvironmentReportRepository{db: db}
}

func (repo EnvironmentReportRepository) HasReportTodayLike(ctx context.Context, userID int64, pattern string) (bool, error) {
	var id int64
	err := repo.db.QueryRowContext(ctx, `
SELECT id
FROM environment_reports
WHERE user_id = ? AND created_at >= CURDATE() AND report_json LIKE ?
LIMIT 1`, userID, pattern).Scan(&id)
	if err == sql.ErrNoRows {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return true, nil
}

func (repo EnvironmentReportRepository) Insert(ctx context.Context, userID int64, reportJSON string) error {
	_, err := repo.db.ExecContext(ctx, "INSERT INTO environment_reports (user_id, report_json) VALUES (?, ?)", userID, reportJSON)
	return err
}
