package database

import (
	"strings"
	"testing"
)

func TestSplitSQLStatements(t *testing.T) {
	raw := "-- comment\nCREATE TABLE first_table (id INT);\n\nINSERT INTO first_table VALUES (1);\n"
	statements := splitSQLStatements(raw)
	if len(statements) != 2 {
		t.Fatalf("splitSQLStatements() returned %d statements, want 2", len(statements))
	}
	if !strings.HasPrefix(statements[0], "CREATE TABLE") || !strings.HasPrefix(statements[1], "INSERT INTO") {
		t.Fatalf("unexpected statements: %#v", statements)
	}
}

func TestChinaRegionsSeedIsComplete(t *testing.T) {
	raw, err := schemaFiles.ReadFile("china_regions_seed.sql")
	if err != nil {
		t.Fatal(err)
	}
	rows := 0
	for _, statement := range splitSQLStatements(string(raw)) {
		if strings.HasPrefix(strings.ToUpper(strings.TrimSpace(statement)), "INSERT IGNORE INTO CHINA_REGIONS") {
			rows++
		}
	}
	if rows != 3644 {
		t.Fatalf("china regions seed contains %d rows, want 3644", rows)
	}
}
