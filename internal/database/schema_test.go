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

func TestLocationShareQuotaIndexMigrationsAreOrderedAndIdempotent(t *testing.T) {
	entries, err := schemaFiles.ReadDir("migrations")
	if err != nil {
		t.Fatal(err)
	}
	names := make([]string, 0, len(entries))
	for _, entry := range entries {
		if !entry.IsDir() && strings.HasSuffix(entry.Name(), ".sql") {
			names = append(names, entry.Name())
		}
	}
	wantOrder := []string{
		"001_app_sessions.sql",
		"002_location_shares.sql",
		"003_location_share_plaintext.sql",
		"004_location_share_quota_indexes.sql",
		"005_support_ticket_quota_indexes.sql",
		"006_group_code_entropy.sql",
		"007_heartbeat_log_indexes.sql",
		"008_app_sessions_user_id_index.sql",
		"009_location_retention_index.sql",
		"010_environment_report_retention_index.sql",
		"011_group_code_alias.sql",
	}
	if len(names) < len(wantOrder) {
		t.Fatalf("migration files = %#v, want at least %#v", names, wantOrder)
	}
	for index, want := range wantOrder {
		if names[index] != want {
			t.Fatalf("migration %d = %q, want %q; all=%#v", index, names[index], want, names)
		}
	}

	createRaw, err := schemaFiles.ReadFile("migrations/002_location_shares.sql")
	if err != nil {
		t.Fatal(err)
	}
	upgradeRaw, err := schemaFiles.ReadFile("migrations/004_location_share_quota_indexes.sql")
	if err != nil {
		t.Fatal(err)
	}
	for _, indexName := range []string{"idx_location_shares_owner_expires", "idx_location_shares_group_expires"} {
		if !strings.Contains(string(createRaw), indexName) {
			t.Fatalf("fresh-install migration is missing %s", indexName)
		}
		if !strings.Contains(string(upgradeRaw), "information_schema.statistics") ||
			!strings.Contains(string(upgradeRaw), "index_name = '"+indexName+"'") {
			t.Fatalf("upgrade migration does not guard %s for replay", indexName)
		}
	}
}

func TestSupportTicketQuotaIndexesCoverFreshAndUpgradeSchemas(t *testing.T) {
	coreRaw, err := schemaFiles.ReadFile("schema_core.sql")
	if err != nil {
		t.Fatal(err)
	}
	upgradeRaw, err := schemaFiles.ReadFile("migrations/005_support_ticket_quota_indexes.sql")
	if err != nil {
		t.Fatal(err)
	}
	core := string(coreRaw)
	upgrade := string(upgradeRaw)
	for _, indexName := range []string{
		"idx_support_tickets_user_created",
		"idx_support_tickets_group_created",
		"idx_support_tickets_group_status_updated",
		"idx_ticket_messages_ticket_sender_created",
		"idx_ticket_messages_ticket_id",
	} {
		if !strings.Contains(core, indexName) {
			t.Fatalf("fresh schema is missing %s", indexName)
		}
		if !strings.Contains(upgrade, "information_schema.statistics") ||
			!strings.Contains(upgrade, "index_name = '"+indexName+"'") {
			t.Fatalf("upgrade migration does not guard %s for replay", indexName)
		}
	}
}

func TestHeartbeatRetentionIndexesCoverFreshAndUpgradeSchemas(t *testing.T) {
	coreRaw, err := schemaFiles.ReadFile("schema_core.sql")
	if err != nil {
		t.Fatal(err)
	}
	upgradeRaw, err := schemaFiles.ReadFile("migrations/007_heartbeat_log_indexes.sql")
	if err != nil {
		t.Fatal(err)
	}
	core := string(coreRaw)
	upgrade := string(upgradeRaw)
	for _, indexName := range []string{"idx_user_logs_user_type_id", "idx_user_logs_group_type_id"} {
		if !strings.Contains(core, indexName) {
			t.Fatalf("fresh schema is missing %s", indexName)
		}
		if !strings.Contains(upgrade, "information_schema.statistics") ||
			!strings.Contains(upgrade, "index_name = '"+indexName+"'") {
			t.Fatalf("upgrade migration does not guard %s for replay", indexName)
		}
	}
}

func TestEnvironmentReportRetentionIndexCoversFreshAndUpgradeSchemas(t *testing.T) {
	coreRaw, err := schemaFiles.ReadFile("schema_core.sql")
	if err != nil {
		t.Fatal(err)
	}
	upgradeRaw, err := schemaFiles.ReadFile("migrations/010_environment_report_retention_index.sql")
	if err != nil {
		t.Fatal(err)
	}
	const indexName = "idx_environment_reports_user_id"
	if !strings.Contains(string(coreRaw), indexName) {
		t.Fatalf("fresh schema is missing %s", indexName)
	}
	if !strings.Contains(string(upgradeRaw), "information_schema.statistics") ||
		!strings.Contains(string(upgradeRaw), "index_name = '"+indexName+"'") {
		t.Fatalf("upgrade migration does not guard %s for replay", indexName)
	}
}
