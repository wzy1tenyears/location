package database

import (
	"strings"
	"testing"
)

func TestLocationRetentionIndexCoversFreshAndUpgradeSchemas(t *testing.T) {
	coreRaw, err := schemaFiles.ReadFile("schema_core.sql")
	if err != nil {
		t.Fatal(err)
	}
	migrationRaw, err := schemaFiles.ReadFile("migrations/009_location_retention_index.sql")
	if err != nil {
		t.Fatal(err)
	}
	const indexName = "idx_locations_group_user_id"
	core := string(coreRaw)
	migration := string(migrationRaw)
	if !strings.Contains(core, "INDEX "+indexName+" (group_name, user_id, id)") {
		t.Fatalf("fresh schema is missing %s with the retention key order", indexName)
	}
	if !strings.Contains(migration, "information_schema.statistics") ||
		!strings.Contains(migration, "index_name = '"+indexName+"'") ||
		!strings.Contains(migration, "ADD INDEX "+indexName+" (group_name, user_id, id)") {
		t.Fatalf("upgrade migration does not idempotently add %s", indexName)
	}
}

func TestLocationRetentionMigrationFollowsReservedSessionMigration(t *testing.T) {
	entries, err := schemaFiles.ReadDir("migrations")
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, entry := range entries {
		if !entry.IsDir() && strings.HasSuffix(entry.Name(), ".sql") {
			names = append(names, entry.Name())
		}
	}
	joined := strings.Join(names, "\n")
	reserved := strings.Index(joined, "008_app_sessions_user_id_index.sql")
	retention := strings.Index(joined, "009_location_retention_index.sql")
	if reserved < 0 || retention <= reserved {
		t.Fatalf("retention migration must follow reserved session migration: %#v", names)
	}
}

func TestLocationHistoryTimeIndexCoversFreshAndUpgradeSchemas(t *testing.T) {
	coreRaw, err := schemaFiles.ReadFile("schema_core.sql")
	if err != nil {
		t.Fatal(err)
	}
	migrationRaw, err := schemaFiles.ReadFile("migrations/012_location_history_time_index.sql")
	if err != nil {
		t.Fatal(err)
	}
	const definition = "idx_locations_group_user_created_id (group_name, user_id, created_at, id)"
	if !strings.Contains(string(coreRaw), "INDEX "+definition) {
		t.Fatalf("fresh schema is missing %s", definition)
	}
	if !strings.Contains(string(migrationRaw), "ADD INDEX "+definition) ||
		!strings.Contains(string(migrationRaw), "information_schema.statistics") {
		t.Fatalf("upgrade migration does not idempotently add %s", definition)
	}
}
