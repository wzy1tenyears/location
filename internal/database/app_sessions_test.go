package database

import (
	"strings"
	"testing"
)

func TestAppSessionUserIndexCoversFreshAndUpgradedDatabases(t *testing.T) {
	freshRaw, err := schemaFiles.ReadFile("migrations/001_app_sessions.sql")
	if err != nil {
		t.Fatal(err)
	}
	upgradeRaw, err := schemaFiles.ReadFile("migrations/008_app_sessions_user_id_index.sql")
	if err != nil {
		t.Fatal(err)
	}
	fresh := string(freshRaw)
	upgrade := string(upgradeRaw)
	if !strings.Contains(fresh, "INDEX idx_app_sessions_user_id (user_id)") {
		t.Fatal("fresh app_sessions schema is missing the user_id revocation index")
	}
	if !strings.Contains(upgrade, "information_schema.statistics") ||
		!strings.Contains(upgrade, "index_name = 'idx_app_sessions_user_id'") ||
		!strings.Contains(upgrade, "ALTER TABLE app_sessions ADD INDEX idx_app_sessions_user_id (user_id)") {
		t.Fatal("008 migration does not idempotently add the app_sessions user_id index")
	}
}
