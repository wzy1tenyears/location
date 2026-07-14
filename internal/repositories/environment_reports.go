package repositories

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
)

const (
	EnvironmentReportKindEnvironment     = "environment"
	EnvironmentReportKindDeviceIntegrity = "device_integrity"
	EnvironmentReportPayloadLimit        = 200000
	EnvironmentReportDailyRowLimit       = 8
	EnvironmentReportRetentionRows       = 90
	EnvironmentReportCleanupBatch        = 500
)

var (
	ErrInvalidEnvironmentReportKind = errors.New("invalid environment report kind")
	ErrEnvironmentReportTooLarge    = errors.New("environment report is too large")
)

type EnvironmentReportStoreResult int

const (
	EnvironmentReportUnchanged EnvironmentReportStoreResult = iota
	EnvironmentReportInserted
	EnvironmentReportUpdated
	EnvironmentReportQuotaExceeded
)

type EnvironmentReportRepository struct {
	db *sql.DB
}

func NewEnvironmentReportRepository(db *sql.DB) EnvironmentReportRepository {
	return EnvironmentReportRepository{db: db}
}

func (repo EnvironmentReportRepository) StoreDaily(ctx context.Context, userID int64, reportKind string, reportJSON string) (EnvironmentReportStoreResult, error) {
	tx, err := repo.db.BeginTx(ctx, nil)
	if err != nil {
		return EnvironmentReportUnchanged, err
	}
	defer func() { _ = tx.Rollback() }()

	result, err := repo.StoreDailyTx(ctx, tx, userID, reportKind, reportJSON)
	if err != nil {
		return EnvironmentReportUnchanged, err
	}
	if err := tx.Commit(); err != nil {
		return EnvironmentReportUnchanged, err
	}
	return result, nil
}

// StoreDailyTx serializes on the user row, so concurrent callers can create at
// most one row for a server-selected report kind and day.
func (repo EnvironmentReportRepository) StoreDailyTx(ctx context.Context, tx *sql.Tx, userID int64, reportKind string, reportJSON string) (EnvironmentReportStoreResult, error) {
	canonicalJSON, err := canonicalEnvironmentReportJSON(reportKind, reportJSON)
	if err != nil {
		return EnvironmentReportUnchanged, err
	}

	var lockedUserID int64
	if err := tx.QueryRowContext(ctx, "SELECT id FROM users WHERE id = ? FOR UPDATE", userID).Scan(&lockedUserID); err != nil {
		return EnvironmentReportUnchanged, err
	}
	if err := pruneEnvironmentReportsTx(ctx, tx, userID, EnvironmentReportRetentionRows); err != nil {
		return EnvironmentReportUnchanged, err
	}

	var existingID int64
	var existingJSON string
	err = tx.QueryRowContext(ctx, `
SELECT id, report_json
FROM environment_reports
WHERE user_id = ?
	AND created_at >= CURDATE()
	AND JSON_UNQUOTE(JSON_EXTRACT(
		CASE WHEN JSON_VALID(report_json) THEN report_json ELSE '{}' END,
		'$.report_kind'
	)) = ?
ORDER BY created_at DESC, id DESC
LIMIT 1
FOR UPDATE`, userID, reportKind).Scan(&existingID, &existingJSON)
	if err != nil && err != sql.ErrNoRows {
		return EnvironmentReportUnchanged, err
	}
	if err == nil {
		if existingJSON == canonicalJSON {
			return EnvironmentReportUnchanged, nil
		}
		if _, err := tx.ExecContext(ctx, "UPDATE environment_reports SET report_json = ?, created_at = NOW() WHERE id = ?", canonicalJSON, existingID); err != nil {
			return EnvironmentReportUnchanged, err
		}
		return EnvironmentReportUpdated, nil
	}

	var dailyRows int
	if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM environment_reports WHERE user_id = ? AND created_at >= CURDATE()", userID).Scan(&dailyRows); err != nil {
		return EnvironmentReportUnchanged, err
	}
	if dailyRows >= EnvironmentReportDailyRowLimit {
		return EnvironmentReportQuotaExceeded, nil
	}
	if _, err := tx.ExecContext(ctx, "INSERT INTO environment_reports (user_id, report_json) VALUES (?, ?)", userID, canonicalJSON); err != nil {
		return EnvironmentReportUnchanged, err
	}
	if err := pruneEnvironmentReportsTx(ctx, tx, userID, EnvironmentReportRetentionRows); err != nil {
		return EnvironmentReportUnchanged, err
	}
	return EnvironmentReportInserted, nil
}

func pruneEnvironmentReportsTx(ctx context.Context, tx *sql.Tx, userID int64, limit int) error {
	var boundaryID int64
	err := tx.QueryRowContext(ctx, `
SELECT id
FROM environment_reports
WHERE user_id = ?
ORDER BY id DESC
LIMIT 1 OFFSET ?`, userID, limit-1).Scan(&boundaryID)
	if err == sql.ErrNoRows {
		return nil
	}
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, "DELETE FROM environment_reports WHERE user_id = ? AND id < ? ORDER BY id ASC LIMIT ?", userID, boundaryID, EnvironmentReportCleanupBatch)
	return err
}

func canonicalEnvironmentReportJSON(reportKind string, reportJSON string) (string, error) {
	if reportKind != EnvironmentReportKindEnvironment && reportKind != EnvironmentReportKindDeviceIntegrity {
		return "", ErrInvalidEnvironmentReportKind
	}
	var report map[string]any
	if err := json.Unmarshal([]byte(reportJSON), &report); err != nil {
		return "", err
	}
	if report == nil {
		report = map[string]any{}
	}
	report["report_kind"] = reportKind
	payload, err := json.Marshal(report)
	if err != nil {
		return "", err
	}
	if len(payload) > EnvironmentReportPayloadLimit {
		return "", ErrEnvironmentReportTooLarge
	}
	return string(payload), nil
}
