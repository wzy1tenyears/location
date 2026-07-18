package database

import (
	"context"
	"database/sql"
	"embed"
	"errors"
	"fmt"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

//go:embed schema_core.sql china_regions_seed.sql migrations/*.sql
var schemaFiles embed.FS

func EnsureSchema(ctx context.Context, db *sql.DB) error {
	return prepareSchema(ctx, db, false)
}

func prepareSchema(ctx context.Context, db *sql.DB, backfillGroupCodes bool) error {
	return withMySQLNamedLock(ctx, db, schemaPreparationLockName, schemaPreparationLockWait, func(conn *sql.Conn) error {
		if err := ensureSchemaOnConn(ctx, conn); err != nil {
			return err
		}
		if backfillGroupCodes {
			if err := backfillLegacyGroupCodes(ctx, conn); err != nil {
				return fmt.Errorf("backfill legacy group codes: %w", err)
			}
		}
		return nil
	})
}

func ensureSchemaOnConn(ctx context.Context, conn *sql.Conn) error {
	if err := execEmbeddedSQL(ctx, conn, "schema_core.sql"); err != nil {
		return fmt.Errorf("apply core schema: %w", err)
	}
	if err := applyMigrations(ctx, conn); err != nil {
		return fmt.Errorf("apply migrations: %w", err)
	}
	if err := ensureChinaRegions(ctx, conn); err != nil {
		return fmt.Errorf("apply china regions seed: %w", err)
	}
	return nil
}

type schemaExecutor interface {
	ExecContext(context.Context, string, ...any) (sql.Result, error)
	QueryRowContext(context.Context, string, ...any) *sql.Row
}

func ensureChinaRegions(ctx context.Context, executor schemaExecutor) error {
	raw, err := schemaFiles.ReadFile("china_regions_seed.sql")
	if err != nil {
		return err
	}
	expected := 0
	for _, statement := range splitSQLStatements(string(raw)) {
		if strings.HasPrefix(strings.ToUpper(strings.TrimSpace(statement)), "INSERT IGNORE INTO CHINA_REGIONS") {
			expected++
		}
	}
	if expected == 0 {
		return fmt.Errorf("china regions seed contains no rows")
	}

	var count int
	err = executor.QueryRowContext(ctx, "SELECT COUNT(*) FROM china_regions").Scan(&count)
	if err == nil && count >= expected {
		return nil
	}
	if err != nil && !missingTableError(err) {
		return err
	}
	return execEmbeddedSQL(ctx, executor, "china_regions_seed.sql")
}

func applyMigrations(ctx context.Context, executor schemaExecutor) error {
	if _, err := executor.ExecContext(ctx, `
CREATE TABLE IF NOT EXISTS schema_migrations (
	version VARCHAR(120) NOT NULL PRIMARY KEY,
	applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`); err != nil {
		return err
	}

	entries, err := schemaFiles.ReadDir("migrations")
	if err != nil {
		return err
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].Name() < entries[j].Name() })
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".sql") {
			continue
		}
		version := strings.TrimSuffix(entry.Name(), filepath.Ext(entry.Name()))
		var applied int
		if err := executor.QueryRowContext(ctx, "SELECT COUNT(*) FROM schema_migrations WHERE version = ?", version).Scan(&applied); err != nil {
			return err
		}
		if applied > 0 {
			continue
		}
		if err := execEmbeddedSQL(ctx, executor, "migrations/"+entry.Name()); err != nil {
			return fmt.Errorf("migration %s: %w", version, err)
		}
		if _, err := executor.ExecContext(ctx, "INSERT INTO schema_migrations (version) VALUES (?)", version); err != nil {
			return fmt.Errorf("record migration %s: %w", version, err)
		}
	}
	return nil
}

type sqlExecutor interface {
	ExecContext(context.Context, string, ...any) (sql.Result, error)
}

func execEmbeddedSQL(ctx context.Context, executor sqlExecutor, name string) error {
	raw, err := schemaFiles.ReadFile(name)
	if err != nil {
		return err
	}
	for _, statement := range splitSQLStatements(string(raw)) {
		if strings.TrimSpace(statement) == "" {
			continue
		}
		if _, err := executor.ExecContext(ctx, statement); err != nil {
			return fmt.Errorf("%s: %w", truncateStatement(statement), err)
		}
	}
	return nil
}

func splitSQLStatements(raw string) []string {
	statements := make([]string, 0)
	var builder strings.Builder
	for _, line := range strings.Split(raw, "\n") {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || strings.HasPrefix(trimmed, "--") {
			continue
		}
		builder.WriteString(line)
		builder.WriteByte('\n')
		if strings.HasSuffix(trimmed, ";") {
			statement := strings.TrimSpace(builder.String())
			if statement != "" {
				statements = append(statements, strings.TrimSuffix(statement, ";"))
			}
			builder.Reset()
		}
	}
	if tail := strings.TrimSpace(builder.String()); tail != "" {
		statements = append(statements, tail)
	}
	return statements
}

func truncateStatement(statement string) string {
	statement = strings.Join(strings.Fields(statement), " ")
	if len(statement) <= 120 {
		return statement
	}
	return statement[:120] + "..."
}

func missingTableError(err error) bool {
	return strings.Contains(strings.ToLower(err.Error()), "doesn't exist") ||
		strings.Contains(strings.ToLower(err.Error()), "does not exist")
}

func EnsureSchemaWithTimeout(db *sql.DB) error {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	err := EnsureSchema(ctx, db)
	if err != nil && errors.Is(err, context.DeadlineExceeded) {
		return fmt.Errorf("prepare schema timed out: %w", err)
	}
	return err
}
