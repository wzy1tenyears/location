package database

import (
	"strings"
	"testing"
)

func TestGroupCodeSchemaAndMigrationUse128BitCodes(t *testing.T) {
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
		t.Fatal("fresh schema does not support 32-character group codes")
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
}
