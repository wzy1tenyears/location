package database

import (
	"context"
	"crypto/rand"
	"database/sql"
	"errors"
	"fmt"
	"regexp"
	"strings"

	"familylocation/location-v3/internal/services"

	"github.com/go-sql-driver/mysql"
)

const groupCodeBackfillMaxAttempts = 32

var currentGroupCodePattern = regexp.MustCompile(`^[0-9a-z]{8}$`)
var legacyGroupCodePattern = regexp.MustCompile(`^[0-9a-f]{32}$`)
var mysqlDuplicateKeyPattern = regexp.MustCompile("(?i)for key ['\"\x60](?:[^.'\"\x60]+\\.)?([^'\"\x60]+)['\"\x60]")

type groupCodeGenerator func() (string, error)

type pendingGroupCodeBackfill struct {
	id         int64
	current    sql.NullString
	legacyCode sql.NullString
}

func backfillLegacyGroupCodes(ctx context.Context, conn *sql.Conn) error {
	return backfillLegacyGroupCodesWithGenerator(ctx, conn, func() (string, error) {
		return services.RandomLowerAlphaNumeric(rand.Reader, 8)
	})
}

func backfillLegacyGroupCodesWithGenerator(ctx context.Context, conn *sql.Conn, generate groupCodeGenerator) error {
	if generate == nil {
		return fmt.Errorf("group-code generator is nil")
	}
	tx, err := conn.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin group-code backfill: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	rows, err := tx.QueryContext(ctx, `
SELECT id, group_code, legacy_group_code
FROM family_groups
WHERE group_code IS NULL
   OR CHAR_LENGTH(group_code) <> 8
   OR BINARY group_code NOT REGEXP '^[0-9a-z]{8}$'
   OR (legacy_group_code IS NOT NULL AND (
       CHAR_LENGTH(legacy_group_code) <> 32
       OR BINARY legacy_group_code NOT REGEXP '^[0-9a-f]{32}$'
   ))
ORDER BY id ASC
FOR UPDATE`)
	if err != nil {
		return fmt.Errorf("select group codes for backfill: %w", err)
	}
	pending := make([]pendingGroupCodeBackfill, 0)
	for rows.Next() {
		var item pendingGroupCodeBackfill
		if err := rows.Scan(&item.id, &item.current, &item.legacyCode); err != nil {
			_ = rows.Close()
			return fmt.Errorf("scan group code for backfill: %w", err)
		}
		pending = append(pending, item)
	}
	if err := rows.Err(); err != nil {
		_ = rows.Close()
		return fmt.Errorf("iterate group codes for backfill: %w", err)
	}
	if err := rows.Close(); err != nil {
		return fmt.Errorf("close group-code backfill rows: %w", err)
	}

	for _, item := range pending {
		legacyValue := any(nil)
		currentValue := item.current.String
		legacyValueString := item.legacyCode.String
		if currentGroupCodePattern.MatchString(currentValue) {
			return fmt.Errorf("group %d has invalid legacy group-code alias %q", item.id, item.legacyCode.String)
		}
		if currentValue != "" && !legacyGroupCodePattern.MatchString(currentValue) {
			return fmt.Errorf("group %d has unsupported historical group code %q", item.id, item.current.String)
		}
		if legacyValueString != "" && !legacyGroupCodePattern.MatchString(legacyValueString) {
			return fmt.Errorf("group %d has unsupported legacy group-code alias %q", item.id, item.legacyCode.String)
		}
		if legacyValueString != "" {
			if currentValue != "" && currentValue != legacyValueString {
				return fmt.Errorf("group %d has conflicting current and legacy group codes", item.id)
			}
			legacyValue = item.legacyCode.String
		} else if currentValue != "" {
			legacyValue = item.current.String
		}

		currentExpected := ""
		if item.current.Valid {
			currentExpected = item.current.String
		}
		legacyExpected := ""
		if item.legacyCode.Valid {
			legacyExpected = item.legacyCode.String
		}

		updated := false
		for attempt := 0; attempt < groupCodeBackfillMaxAttempts; attempt++ {
			newCode, generateErr := generate()
			if generateErr != nil {
				return fmt.Errorf("generate replacement group code for group %d: %w", item.id, generateErr)
			}
			if !currentGroupCodePattern.MatchString(newCode) {
				return fmt.Errorf("group-code generator returned invalid code %q", newCode)
			}
			result, updateErr := tx.ExecContext(ctx, `
UPDATE family_groups
SET legacy_group_code = ?, group_code = ?
WHERE id = ?
  AND COALESCE(group_code, '') = ?
  AND COALESCE(legacy_group_code, '') = ?`, legacyValue, newCode, item.id, currentExpected, legacyExpected)
			if updateErr != nil {
				if isMySQLDuplicateKeyFor(updateErr, "group_code") {
					continue
				}
				return fmt.Errorf("update group code %d: %w", item.id, updateErr)
			}
			rows, rowsErr := result.RowsAffected()
			if rowsErr != nil {
				return fmt.Errorf("inspect group-code update %d: %w", item.id, rowsErr)
			}
			if rows != 1 {
				return fmt.Errorf("group-code update %d affected %d rows", item.id, rows)
			}
			updated = true
			break
		}
		if !updated {
			return fmt.Errorf("failed to allocate a unique group code for group %d after %d attempts", item.id, groupCodeBackfillMaxAttempts)
		}
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit group-code backfill: %w", err)
	}
	return nil
}

func isMySQLDuplicateKeyFor(err error, keyName string) bool {
	var mysqlErr *mysql.MySQLError
	if !errors.As(err, &mysqlErr) || mysqlErr.Number != 1062 {
		return false
	}
	match := mysqlDuplicateKeyPattern.FindStringSubmatch(mysqlErr.Message)
	return len(match) == 2 && strings.EqualFold(match[1], keyName)
}
