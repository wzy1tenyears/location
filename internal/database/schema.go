package database

import (
	"context"
	"database/sql"
	"embed"
	"fmt"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

//go:embed schema_core.sql china_regions_seed.sql migrations/*.sql
var schemaFiles embed.FS

func EnsureSchema(ctx context.Context, db *sql.DB) error {
	if err := execEmbeddedSQL(ctx, db, "schema_core.sql"); err != nil {
		return fmt.Errorf("apply core schema: %w", err)
	}
	if err := applyMigrations(ctx, db); err != nil {
		return fmt.Errorf("apply migrations: %w", err)
	}
	if err := ensureChinaRegions(ctx, db); err != nil {
		return fmt.Errorf("apply china regions seed: %w", err)
	}
	return nil
}

func ensureChinaRegions(ctx context.Context, db *sql.DB) error {
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
	err = db.QueryRowContext(ctx, "SELECT COUNT(*) FROM china_regions").Scan(&count)
	if err == nil && count >= expected {
		return nil
	}
	if err != nil && !missingTableError(err) {
		return err
	}
	return execEmbeddedSQL(ctx, db, "china_regions_seed.sql")
}

func applyMigrations(ctx context.Context, db *sql.DB) error {
	if _, err := db.ExecContext(ctx, `
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
		if err := db.QueryRowContext(ctx, "SELECT COUNT(*) FROM schema_migrations WHERE version = ?", version).Scan(&applied); err != nil {
			return err
		}
		if applied > 0 {
			continue
		}
		if err := execEmbeddedSQL(ctx, db, "migrations/"+entry.Name()); err != nil {
			return fmt.Errorf("migration %s: %w", version, err)
		}
		if _, err := db.ExecContext(ctx, "INSERT INTO schema_migrations (version) VALUES (?)", version); err != nil {
			return fmt.Errorf("record migration %s: %w", version, err)
		}
	}
	return nil
}

func execEmbeddedSQL(ctx context.Context, db *sql.DB, name string) error {
	raw, err := schemaFiles.ReadFile(name)
	if err != nil {
		return err
	}
	for _, statement := range splitSQLStatements(string(raw)) {
		if strings.TrimSpace(statement) == "" {
			continue
		}
		if _, err := db.ExecContext(ctx, statement); err != nil {
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
	return EnsureSchema(ctx, db)
}
