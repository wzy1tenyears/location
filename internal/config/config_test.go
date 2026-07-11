package config

import (
	"strings"
	"testing"
	"time"
)

func validConfig() Config {
	return Config{
		App: AppConfig{
			UserAgentToken:  "loc-app",
			SigningSecret:   strings.Repeat("s", 32),
			SessionLifetime: time.Hour,
		},
		Auth: AuthConfig{AdminUsername: "admin", AdminPasswordHash: "hash"},
		Files: FileConfig{
			UserAPKFilename:  "location-release.apk",
			AdminAPKFilename: "private/location-admin-release.apk",
		},
		Database: DatabaseConfig{Name: "location", User: "location", Pass: "secret"},
	}
}

func TestValidateAcceptsCompleteConfig(t *testing.T) {
	if err := Validate(validConfig()); err != nil {
		t.Fatalf("Validate() error = %v", err)
	}
}

func TestValidateRequiresIndependentSigningSecret(t *testing.T) {
	cfg := validConfig()
	cfg.App.SigningSecret = ""
	if err := Validate(cfg); err == nil {
		t.Fatal("Validate() accepted an empty signing secret")
	}
}

func TestValidateRejectsAPKPathTraversal(t *testing.T) {
	cfg := validConfig()
	cfg.Files.AdminAPKFilename = "../admin.apk"
	if err := Validate(cfg); err == nil {
		t.Fatal("Validate() accepted an APK path outside the public directory")
	}
}
