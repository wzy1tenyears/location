package database

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"testing"

	"github.com/DATA-DOG/go-sqlmock"
	"github.com/go-sql-driver/mysql"
)

func TestGroupCodeSchemaPreservesLegacyAliasWithoutShrinkingCurrentColumn(t *testing.T) {
	coreRaw, err := schemaFiles.ReadFile("schema_core.sql")
	if err != nil {
		t.Fatal(err)
	}
	migrationRaw, err := schemaFiles.ReadFile("migrations/006_group_code_entropy.sql")
	if err != nil {
		t.Fatal(err)
	}
	core := strings.ToUpper(string(coreRaw))
	migration := strings.ToUpper(string(migrationRaw))
	if !strings.Contains(core, "GROUP_CODE VARCHAR(32)") {
		t.Fatal("fresh schema shrank the current group-code column")
	}
	if !strings.Contains(core, "LEGACY_GROUP_CODE VARCHAR(32) NULL") ||
		!strings.Contains(core, "UNIQUE KEY UNIQ_FAMILY_GROUPS_LEGACY_GROUP_CODE (LEGACY_GROUP_CODE)") {
		t.Fatal("fresh schema does not preserve a unique legacy group-code alias")
	}
	for _, required := range []string{
		"MODIFY COLUMN GROUP_CODE VARCHAR(32)",
		"RANDOM_BYTES(16)",
		"CHAR_LENGTH(GROUP_CODE) = 6",
	} {
		if !strings.Contains(migration, required) {
			t.Fatalf("group-code migration is missing %q", required)
		}
	}

	aliasRaw, err := schemaFiles.ReadFile("migrations/011_group_code_alias.sql")
	if err != nil {
		t.Fatal(err)
	}
	alias := strings.ToUpper(string(aliasRaw))
	for _, required := range []string{
		"INFORMATION_SCHEMA.COLUMNS",
		"COLUMN_NAME = 'LEGACY_GROUP_CODE'",
		"ADD COLUMN LEGACY_GROUP_CODE VARCHAR(32) NULL",
		"DATA_TYPE = 'VARCHAR'",
		"CHARACTER_MAXIMUM_LENGTH = 32",
		"IS_NULLABLE = 'YES'",
		"__FAMILY_LOCATION_INVALID_LEGACY_GROUP_CODE_COLUMN_CONTRACT__",
		"INFORMATION_SCHEMA.STATISTICS",
		"INDEX_NAME = 'UNIQ_FAMILY_GROUPS_LEGACY_GROUP_CODE'",
		"COUNT(*) = 1",
		"NON_UNIQUE = 0",
		"COLUMN_NAME = 'LEGACY_GROUP_CODE'",
		"SEQ_IN_INDEX = 1",
		"SUB_PART IS NULL",
		"DROP INDEX UNIQ_FAMILY_GROUPS_LEGACY_GROUP_CODE",
		"ADD UNIQUE INDEX UNIQ_FAMILY_GROUPS_LEGACY_GROUP_CODE (LEGACY_GROUP_CODE)",
	} {
		if !strings.Contains(alias, required) {
			t.Fatalf("group-code alias migration is missing %q", required)
		}
	}
}

func TestBackfillLegacyGroupCodesIsAtomicAndRetriesCurrentCodeCollision(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	conn, err := db.Conn(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()

	legacy := strings.Repeat("a", 32)
	mock.ExpectBegin()
	mock.ExpectQuery(`(?s)SELECT id, group_code, legacy_group_code.*FOR UPDATE`).
		WillReturnRows(sqlmock.NewRows([]string{"id", "group_code", "legacy_group_code"}).
			AddRow(1, legacy, nil).
			AddRow(2, nil, nil))
	mock.ExpectExec(`(?s)UPDATE family_groups.*SET legacy_group_code = \?, group_code = \?`).
		WithArgs(legacy, "00000000", int64(1), legacy, "").
		WillReturnError(&mysql.MySQLError{Number: 1062, Message: "Duplicate entry '00000000' for key 'family_groups.group_code'"})
	mock.ExpectExec(`(?s)UPDATE family_groups.*SET legacy_group_code = \?, group_code = \?`).
		WithArgs(legacy, "11111111", int64(1), legacy, "").
		WillReturnResult(sqlmock.NewResult(0, 1))
	mock.ExpectExec(`(?s)UPDATE family_groups.*SET legacy_group_code = \?, group_code = \?`).
		WithArgs(nil, "22222222", int64(2), "", "").
		WillReturnResult(sqlmock.NewResult(0, 1))
	mock.ExpectCommit()

	codes := []string{"00000000", "11111111", "22222222"}
	index := 0
	err = backfillLegacyGroupCodesWithGenerator(context.Background(), conn, func() (string, error) {
		code := codes[index]
		index++
		return code, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if index != len(codes) {
		t.Fatalf("generator called %d times, want %d", index, len(codes))
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestBackfillLegacyGroupCodesRollsBackAllRowsOnLaterEntropyFailure(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	conn, err := db.Conn(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()

	firstLegacy := strings.Repeat("a", 32)
	secondLegacy := strings.Repeat("b", 32)
	mock.ExpectBegin()
	mock.ExpectQuery(`(?s)SELECT id, group_code, legacy_group_code.*FOR UPDATE`).
		WillReturnRows(sqlmock.NewRows([]string{"id", "group_code", "legacy_group_code"}).
			AddRow(1, firstLegacy, nil).
			AddRow(2, secondLegacy, nil))
	mock.ExpectExec(`(?s)UPDATE family_groups.*SET legacy_group_code = \?, group_code = \?`).
		WithArgs(firstLegacy, "00000000", int64(1), firstLegacy, "").
		WillReturnResult(sqlmock.NewResult(0, 1))
	mock.ExpectRollback()

	entropyErr := errors.New("entropy unavailable")
	index := 0
	err = backfillLegacyGroupCodesWithGenerator(context.Background(), conn, func() (string, error) {
		index++
		if index == 1 {
			return "00000000", nil
		}
		return "", entropyErr
	})
	if !errors.Is(err, entropyErr) {
		t.Fatalf("backfill error = %v, want entropy error", err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestBackfillLegacyGroupCodesRejectsNon32CharacterAlias(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	conn, err := db.Conn(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()

	mock.ExpectBegin()
	mock.ExpectQuery(`(?s)SELECT id, group_code, legacy_group_code.*FOR UPDATE`).
		WillReturnRows(sqlmock.NewRows([]string{"id", "group_code", "legacy_group_code"}).
			AddRow(1, "abcd1234", "ABCDEF0123456789ABCDEF0123456789"))
	mock.ExpectRollback()
	err = backfillLegacyGroupCodesWithGenerator(context.Background(), conn, func() (string, error) {
		t.Fatal("generator was called for an invalid alias")
		return "", nil
	})
	if err == nil || !strings.Contains(err.Error(), "invalid legacy") {
		t.Fatalf("backfill error = %v, want invalid-legacy failure", err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestBackfillLegacyGroupCodesRejectsWhitespaceWithoutWritingTrimmedAlias(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	conn, err := db.Conn(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()

	dirtyLegacy := " " + strings.Repeat("a", 32)
	mock.ExpectBegin()
	mock.ExpectQuery(`(?s)SELECT id, group_code, legacy_group_code.*FOR UPDATE`).
		WillReturnRows(sqlmock.NewRows([]string{"id", "group_code", "legacy_group_code"}).
			AddRow(1, dirtyLegacy, nil))
	mock.ExpectRollback()
	err = backfillLegacyGroupCodesWithGenerator(context.Background(), conn, func() (string, error) {
		t.Fatal("generator was called for a non-canonical historical group code")
		return "", nil
	})
	if err == nil || !strings.Contains(err.Error(), "unsupported historical") {
		t.Fatalf("backfill error = %v, want exact-value validation failure", err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestBackfillLegacyGroupCodesIsReplaySafeWhenNoRowsNeedMigration(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	conn, err := db.Conn(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()

	mock.ExpectBegin()
	mock.ExpectQuery(`(?s)SELECT id, group_code, legacy_group_code.*FOR UPDATE`).
		WillReturnRows(sqlmock.NewRows([]string{"id", "group_code", "legacy_group_code"}))
	mock.ExpectCommit()
	err = backfillLegacyGroupCodesWithGenerator(context.Background(), conn, func() (string, error) {
		t.Fatal("generator was called during replay-safe no-op")
		return "", nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestDatabaseDuplicateKeyMatchingIsPrecise(t *testing.T) {
	wrappedCurrent := fmt.Errorf("wrapped: %w", &mysql.MySQLError{Number: 1062, Message: "Duplicate entry 'abcdefgh' for key 'family_groups.group_code'"})
	if !isMySQLDuplicateKeyFor(wrappedCurrent, "group_code") {
		t.Fatal("wrapped current group-code collision was not recognized")
	}
	for _, err := range []error{
		&mysql.MySQLError{Number: 1062, Message: "Duplicate entry 'legacy' for key 'family_groups.uniq_family_groups_legacy_group_code'"},
		&mysql.MySQLError{Number: 1062, Message: "Duplicate entry 'family' for key 'family_groups.group_name'"},
		&mysql.MySQLError{Number: 1048, Message: "Column cannot be null"},
		errors.New("Duplicate entry for key group_code"),
	} {
		if isMySQLDuplicateKeyFor(err, "group_code") {
			t.Fatalf("unrelated error was treated as group-code collision: %v", err)
		}
	}
}
