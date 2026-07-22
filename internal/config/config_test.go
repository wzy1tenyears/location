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

func TestLoadFallsBackForNonPositiveLocationHistoryLimit(t *testing.T) {
	for _, value := range []string{"0", "-25"} {
		t.Run(value, func(t *testing.T) {
			t.Setenv("LOC_LOCATION_HISTORY_LIMIT", value)
			if got := Load().Location.HistoryLimit; got != 5000 {
				t.Fatalf("HistoryLimit = %d, want finite fallback 5000", got)
			}
		})
	}
}

func TestLoadPreservesPositiveLocationHistoryLimit(t *testing.T) {
	t.Setenv("LOC_LOCATION_HISTORY_LIMIT", "37")
	if got := Load().Location.HistoryLimit; got != 37 {
		t.Fatalf("HistoryLimit = %d, want 37", got)
	}
}

func TestLoadReadsWriteFreezeFile(t *testing.T) {
	t.Setenv("LOC_WRITE_FREEZE_FILE", "/srv/family-location/state/runtime/write-freeze")
	if got := Load().Server.WriteFreezeFile; got != "/srv/family-location/state/runtime/write-freeze" {
		t.Fatalf("WriteFreezeFile = %q", got)
	}
}

func TestLoadDisablesWriteFreezeWhenPathIsUnset(t *testing.T) {
	t.Setenv("LOC_WRITE_FREEZE_FILE", "")
	if got := Load().Server.WriteFreezeFile; got != "" {
		t.Fatalf("WriteFreezeFile = %q, want empty", got)
	}
}

func TestLoadEnablesGroupCodeBackfillByDefault(t *testing.T) {
	t.Setenv("LOC_GROUP_CODE_BACKFILL_ENABLED", "")
	if !Load().Database.GroupCodeBackfillEnabled {
		t.Fatal("group-code backfill is disabled by default")
	}
}

func TestLoadAllowsGroupCodeBackfillToBeDisabledForStagedDeployment(t *testing.T) {
	for _, value := range []string{"0", "false", "no", "off"} {
		t.Run(value, func(t *testing.T) {
			t.Setenv("LOC_GROUP_CODE_BACKFILL_ENABLED", value)
			if Load().Database.GroupCodeBackfillEnabled {
				t.Fatalf("group-code backfill remained enabled for %q", value)
			}
		})
	}
}

func TestLoadRejectsAmbiguousGroupCodeBackfillToggleByUsingSafeDefault(t *testing.T) {
	t.Setenv("LOC_GROUP_CODE_BACKFILL_ENABLED", "maybe")
	if !Load().Database.GroupCodeBackfillEnabled {
		t.Fatal("invalid group-code backfill toggle disabled the default migration")
	}
}

func validIPGeoProviderQuota() IPGeoProviderQuota {
	return IPGeoProviderQuota{
		MaxRequests:     1000,
		ReserveRequests: 200,
		UserMaxMisses:   50,
		Window:          24 * time.Hour,
	}
}

func TestLoadReadsProviderSpecificIPGeoQuota(t *testing.T) {
	t.Setenv("LOC_IPDATA_QUOTA_MAX_REQUESTS", "1200")
	t.Setenv("LOC_IPDATA_QUOTA_RESERVE_REQUESTS", "300")
	t.Setenv("LOC_IPDATA_QUOTA_USER_MAX_MISSES", "40")
	t.Setenv("LOC_IPDATA_QUOTA_WINDOW_SECONDS", "86400")

	quota, ok := Load().External.IPGeoQuota("ipdata")
	if !ok {
		t.Fatal("IPGeoQuota(ipdata) is missing")
	}
	if quota.MaxRequests != 1200 || quota.ReserveRequests != 300 || quota.UserMaxMisses != 40 || quota.Window != 24*time.Hour {
		t.Fatalf("IPGeoQuota(ipdata) = %#v", quota)
	}
	if quota.AvailableRequests() != 900 {
		t.Fatalf("AvailableRequests() = %d, want 900", quota.AvailableRequests())
	}
}

func TestLoadUsesConservativeProviderQuotaDefaults(t *testing.T) {
	for _, key := range []string{
		"LOC_IPINFO_LITE_QUOTA_MAX_REQUESTS", "LOC_IPINFO_LITE_QUOTA_RESERVE_REQUESTS", "LOC_IPINFO_LITE_QUOTA_USER_MAX_MISSES", "LOC_IPINFO_LITE_QUOTA_WINDOW_SECONDS",
		"LOC_IP2LOCATION_QUOTA_MAX_REQUESTS", "LOC_IP2LOCATION_QUOTA_RESERVE_REQUESTS", "LOC_IP2LOCATION_QUOTA_USER_MAX_MISSES", "LOC_IP2LOCATION_QUOTA_WINDOW_SECONDS",
		"LOC_IPDATA_QUOTA_MAX_REQUESTS", "LOC_IPDATA_QUOTA_RESERVE_REQUESTS", "LOC_IPDATA_QUOTA_USER_MAX_MISSES", "LOC_IPDATA_QUOTA_WINDOW_SECONDS",
		"LOC_IPREGISTRY_QUOTA_MAX_REQUESTS", "LOC_IPREGISTRY_QUOTA_RESERVE_REQUESTS", "LOC_IPREGISTRY_QUOTA_USER_MAX_MISSES", "LOC_IPREGISTRY_QUOTA_WINDOW_SECONDS",
	} {
		t.Setenv(key, "")
	}
	loaded := Load().External
	for provider, want := range defaultIPGeoProviderQuotas {
		got, ok := loaded.IPGeoQuota(provider)
		if !ok || got != want {
			t.Fatalf("default quota %s = %#v, want %#v", provider, got, want)
		}
	}
}

func TestValidateAcceptsExistingCredentialWithDefaultProviderQuota(t *testing.T) {
	cfg := validConfig()
	cfg.External = Load().External
	cfg.External.IPDataKey = "provider-key"
	if err := Validate(cfg); err != nil {
		t.Fatalf("Validate() rejected existing credential with default quota: %v", err)
	}
}

func TestValidateRejectsProgrammaticProviderWithoutQuota(t *testing.T) {
	cfg := validConfig()
	cfg.External.IPDataKey = "provider-key"
	if err := Validate(cfg); err == nil {
		t.Fatal("Validate() accepted provider credentials without a quota policy")
	}
}

func TestValidateRejectsExplicitInvalidProviderQuotaOverride(t *testing.T) {
	t.Setenv("LOC_IPDATA_QUOTA_MAX_REQUESTS", "0")
	cfg := validConfig()
	cfg.External = Load().External
	cfg.External.IPDataKey = "provider-key"
	if err := Validate(cfg); err == nil {
		t.Fatal("Validate() accepted an explicit zero provider quota")
	}
}

func TestValidateAcceptsConfiguredIPGeoProviderQuota(t *testing.T) {
	cfg := validConfig()
	cfg.External.IPDataKey = "provider-key"
	cfg.External.IPGeoProviderQuotas = map[string]IPGeoProviderQuota{"ipdata": validIPGeoProviderQuota()}
	if err := Validate(cfg); err != nil {
		t.Fatalf("Validate() error = %v", err)
	}
}

func TestValidateRejectsUnsafeIPGeoProviderQuota(t *testing.T) {
	for name, mutate := range map[string]func(*IPGeoProviderQuota){
		"missing plan maximum":    func(quota *IPGeoProviderQuota) { quota.MaxRequests = 0 },
		"missing safety reserve":  func(quota *IPGeoProviderQuota) { quota.ReserveRequests = 0 },
		"reserve consumes plan":   func(quota *IPGeoProviderQuota) { quota.ReserveRequests = quota.MaxRequests },
		"missing reset window":    func(quota *IPGeoProviderQuota) { quota.Window = 0 },
		"missing user miss quota": func(quota *IPGeoProviderQuota) { quota.UserMaxMisses = 0 },
		"user quota exceeds lane": func(quota *IPGeoProviderQuota) { quota.UserMaxMisses = quota.AvailableRequests() + 1 },
	} {
		t.Run(name, func(t *testing.T) {
			cfg := validConfig()
			cfg.External.IPDataKey = "provider-key"
			quota := validIPGeoProviderQuota()
			mutate(&quota)
			cfg.External.IPGeoProviderQuotas = map[string]IPGeoProviderQuota{"ipdata": quota}
			if err := Validate(cfg); err == nil {
				t.Fatalf("Validate() accepted unsafe quota %#v", quota)
			}
		})
	}
}
