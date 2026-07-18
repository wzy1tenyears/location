package database

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"
)

const (
	schemaPreparationLockName = "family-location-v3-schema-v1"
	schemaPreparationLockWait = 60
	schemaPreparationTimeout  = 2 * time.Minute
	schemaLockReleaseTimeout  = 5 * time.Second
)

func PrepareWithTimeout(db *sql.DB, backfillGroupCodes bool) error {
	ctx, cancel := context.WithTimeout(context.Background(), schemaPreparationTimeout)
	defer cancel()
	return prepareSchema(ctx, db, backfillGroupCodes)
}

func withMySQLNamedLock(ctx context.Context, db *sql.DB, name string, waitSeconds int, callback func(*sql.Conn) error) (resultErr error) {
	conn, err := db.Conn(ctx)
	if err != nil {
		return fmt.Errorf("open schema lock connection: %w", err)
	}
	acquired := false
	defer func() {
		if acquired {
			releaseCtx, cancel := context.WithTimeout(context.Background(), schemaLockReleaseTimeout)
			defer cancel()
			var released sql.NullInt64
			if releaseErr := conn.QueryRowContext(releaseCtx, "SELECT RELEASE_LOCK(?)", name).Scan(&released); releaseErr != nil {
				resultErr = errors.Join(resultErr, fmt.Errorf("release schema lock: %w", releaseErr))
			} else if !released.Valid || released.Int64 != 1 {
				resultErr = errors.Join(resultErr, fmt.Errorf("release schema lock returned no ownership"))
			}
		}
		if closeErr := conn.Close(); closeErr != nil {
			resultErr = errors.Join(resultErr, fmt.Errorf("close schema lock connection: %w", closeErr))
		}
	}()

	var lockResult sql.NullInt64
	if err := conn.QueryRowContext(ctx, "SELECT GET_LOCK(?, ?)", name, waitSeconds).Scan(&lockResult); err != nil {
		return fmt.Errorf("acquire schema lock: %w", err)
	}
	if !lockResult.Valid || lockResult.Int64 != 1 {
		return fmt.Errorf("acquire schema lock timed out")
	}
	acquired = true
	return callback(conn)
}
