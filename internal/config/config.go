package config

import (
	"fmt"
	"math"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const defaultLocationHistoryLimit = 5000

type Config struct {
	Server   ServerConfig
	App      AppConfig
	Admin    AdminConfig
	Auth     AuthConfig
	Files    FileConfig
	External ExternalConfig
	Location LocationConfig
	Database DatabaseConfig
}

type ServerConfig struct {
	Addr            string
	WriteFreezeFile string
}

type AppConfig struct {
	Name              string
	UserAgentToken    string
	VersionCode       int
	VersionName       string
	ForceUpdate       bool
	DeviceCookieName  string
	SessionCookieName string
	SigningSecret     string
	SessionLifetime   time.Duration
}

type AdminConfig struct {
	VersionCode int
	VersionName string
	ForceUpdate bool
}

type AuthConfig struct {
	AdminUsername     string
	AdminPassword     string
	AdminPasswordHash string
}

type FileConfig struct {
	PublicBaseDir    string
	UserAPKFilename  string
	AdminAPKFilename string
}

type ExternalConfig struct {
	IPInfoLiteToken     string
	IP2LocationKey      string
	IPDataKey           string
	IPRegistryKey       string
	IPGeoProviderQuotas map[string]IPGeoProviderQuota
	TurnstileSiteKey    string
	TurnstileSecretKey  string
	AMapJSAPIKey        string
	AMapServicePath     string
	AMapSharePath       string
}

type IPGeoProviderQuota struct {
	MaxRequests     int
	ReserveRequests int
	UserMaxMisses   int
	Window          time.Duration
}

var defaultIPGeoProviderQuotas = map[string]IPGeoProviderQuota{
	"ipinfo-lite": {MaxRequests: 1000, ReserveRequests: 500, UserMaxMisses: 100, Window: 24 * time.Hour},
	"ip2location": {MaxRequests: 1000, ReserveRequests: 500, UserMaxMisses: 50, Window: 30 * 24 * time.Hour},
	"ipdata":      {MaxRequests: 200, ReserveRequests: 100, UserMaxMisses: 25, Window: 24 * time.Hour},
	"ipregistry":  {MaxRequests: 1000, ReserveRequests: 500, UserMaxMisses: 50, Window: 30 * 24 * time.Hour},
	"ip-api":      {MaxRequests: 45, ReserveRequests: 10, UserMaxMisses: 5, Window: time.Minute},
	"uapis":       {MaxRequests: 200, ReserveRequests: 50, UserMaxMisses: 20, Window: 24 * time.Hour},
	"baidu":       {MaxRequests: 1000, ReserveRequests: 100, UserMaxMisses: 50, Window: 24 * time.Hour},
	"iping":       {MaxRequests: 1000, ReserveRequests: 100, UserMaxMisses: 50, Window: 24 * time.Hour},
	"xxapi":       {MaxRequests: 5000, ReserveRequests: 500, UserMaxMisses: 100, Window: 24 * time.Hour},
}

func (quota IPGeoProviderQuota) AvailableRequests() int {
	available := quota.MaxRequests - quota.ReserveRequests
	if available < 0 {
		return 0
	}
	return available
}

func (cfg ExternalConfig) IPGeoQuota(provider string) (IPGeoProviderQuota, bool) {
	quota, ok := cfg.IPGeoProviderQuotas[strings.ToLower(strings.TrimSpace(provider))]
	return quota, ok
}

type LocationConfig struct {
	HistoryLimit             int
	MinReportSeconds         int
	MaxAccuracyMeters        float64
	MaxSpeedMPS              float64
	MaxReasonableTravelMPS   float64
	MaxLocationAgeSeconds    int
	MaxLocationFutureSeconds int
	JumpAllowanceMeters      float64
	MaxStationaryJumpMeters  float64
	MaxStationaryJumpSeconds int
	MaxStationarySpeedMPS    float64
	DiagnosticsUpdateSeconds int
	MaxDiagnosticsBytes      int
}

type DatabaseConfig struct {
	Host                     string
	Port                     int
	Name                     string
	User                     string
	Pass                     string
	Charset                  string
	GroupCodeBackfillEnabled bool
}

func Load() Config {
	return Config{
		Server: ServerConfig{
			Addr:            env("LOC_GO_ADDR", "127.0.0.1:8088"),
			WriteFreezeFile: env("LOC_WRITE_FREEZE_FILE", ""),
		},
		App: AppConfig{
			Name:              env("LOC_APP_NAME", "位置"),
			UserAgentToken:    env("LOC_APP_USER_AGENT_TOKEN", "loc-app"),
			VersionCode:       envInt("LOC_ANDROID_VERSION_CODE", 154),
			VersionName:       env("LOC_ANDROID_VERSION_NAME", "2.3.10"),
			ForceUpdate:       envBool("LOC_ANDROID_FORCE_UPDATE", true),
			DeviceCookieName:  env("LOC_DEVICE_COOKIE_NAME", "loc_device"),
			SessionCookieName: env("LOC_SESSION_COOKIE_NAME", "family_location_session"),
			SigningSecret:     env("LOC_APP_SIGNING_SECRET", ""),
			SessionLifetime:   time.Duration(envInt("LOC_SESSION_LIFETIME_SECONDS", 2592000)) * time.Second,
		},
		Admin: AdminConfig{
			VersionCode: envInt("LOC_ANDROID_ADMIN_VERSION_CODE", 101),
			VersionName: env("LOC_ANDROID_ADMIN_VERSION_NAME", "2.3.6"),
			ForceUpdate: envBool("LOC_ANDROID_ADMIN_FORCE_UPDATE", true),
		},
		Auth: AuthConfig{
			AdminUsername:     env("LOC_ADMIN_USERNAME", "admin"),
			AdminPassword:     env("LOC_ADMIN_PASSWORD", ""),
			AdminPasswordHash: env("LOC_ADMIN_PASSWORD_HASH", ""),
		},
		Files: FileConfig{
			PublicBaseDir:    env("LOC_PUBLIC_BASE_DIR", "."),
			UserAPKFilename:  env("LOC_ANDROID_APK_FILENAME", "location-release.apk"),
			AdminAPKFilename: env("LOC_ANDROID_ADMIN_APK_FILENAME", "private/location-admin-release.apk"),
		},
		External: ExternalConfig{
			IPInfoLiteToken: env("LOC_IPINFO_LITE_TOKEN", ""),
			IP2LocationKey:  env("LOC_IP2LOCATION_IO_KEY", ""),
			IPDataKey:       env("LOC_IPDATA_API_KEY", ""),
			IPRegistryKey:   env("LOC_IPREGISTRY_API_KEY", ""),
			IPGeoProviderQuotas: map[string]IPGeoProviderQuota{
				"ipinfo-lite": loadIPGeoProviderQuota("LOC_IPINFO_LITE", defaultIPGeoProviderQuotas["ipinfo-lite"]),
				"ip2location": loadIPGeoProviderQuota("LOC_IP2LOCATION", defaultIPGeoProviderQuotas["ip2location"]),
				"ipdata":      loadIPGeoProviderQuota("LOC_IPDATA", defaultIPGeoProviderQuotas["ipdata"]),
				"ipregistry":  loadIPGeoProviderQuota("LOC_IPREGISTRY", defaultIPGeoProviderQuotas["ipregistry"]),
				"ip-api":      loadIPGeoProviderQuota("LOC_IP_API", defaultIPGeoProviderQuotas["ip-api"]),
				"uapis":       loadIPGeoProviderQuota("LOC_UAPIS", defaultIPGeoProviderQuotas["uapis"]),
				"baidu":       loadIPGeoProviderQuota("LOC_BAIDU_IP", defaultIPGeoProviderQuotas["baidu"]),
				"iping":       loadIPGeoProviderQuota("LOC_IPING", defaultIPGeoProviderQuotas["iping"]),
				"xxapi":       loadIPGeoProviderQuota("LOC_XXAPI", defaultIPGeoProviderQuotas["xxapi"]),
			},
			TurnstileSiteKey:   env("LOC_CF_TURNSTILE_SITE_KEY", ""),
			TurnstileSecretKey: env("LOC_CF_TURNSTILE_SECRET_KEY", ""),
			AMapJSAPIKey:       env("LOC_AMAP_JS_API_KEY", ""),
			AMapServicePath:    env("LOC_AMAP_SERVICE_PROXY_PATH", "/_AMapService"),
			AMapSharePath:      env("LOC_AMAP_SHARE_PROXY_PATH", "/_ShareMapService"),
		},
		Location: LocationConfig{
			HistoryLimit:             envPositiveInt("LOC_LOCATION_HISTORY_LIMIT", defaultLocationHistoryLimit),
			MinReportSeconds:         envInt("LOC_MIN_LOCATION_REPORT_SECONDS", 10),
			MaxAccuracyMeters:        envPositiveFloat("LOC_MAX_LOCATION_ACCURACY_METERS", 100),
			MaxSpeedMPS:              envFloat("LOC_MAX_LOCATION_SPEED_MPS", 120),
			MaxReasonableTravelMPS:   envFloat("LOC_MAX_REASONABLE_TRAVEL_MPS", 120),
			MaxLocationAgeSeconds:    envPositiveInt("LOC_MAX_LOCATION_AGE_SECONDS", 60),
			MaxLocationFutureSeconds: envPositiveInt("LOC_MAX_LOCATION_FUTURE_SECONDS", 15),
			JumpAllowanceMeters:      envPositiveFloat("LOC_LOCATION_JUMP_ALLOWANCE_METERS", 100),
			MaxStationaryJumpMeters:  envPositiveFloat("LOC_MAX_STATIONARY_JUMP_METERS", 200),
			MaxStationaryJumpSeconds: envPositiveInt("LOC_MAX_STATIONARY_JUMP_SECONDS", 120),
			MaxStationarySpeedMPS:    envPositiveFloat("LOC_MAX_STATIONARY_SPEED_MPS", 2),
			DiagnosticsUpdateSeconds: envInt("LOC_LOCATION_DIAGNOSTICS_UPDATE_SECONDS", 600),
			MaxDiagnosticsBytes:      envInt("LOC_MAX_ADDRESS_DIAGNOSTICS_BYTES", 12000),
		},
		Database: DatabaseConfig{
			Host:                     env("LOC_DB_HOST", "127.0.0.1"),
			Port:                     envInt("LOC_DB_PORT", 3306),
			Name:                     env("LOC_DB_NAME", "family_loc"),
			User:                     env("LOC_DB_USER", "family_loc"),
			Pass:                     env("LOC_DB_PASS", ""),
			Charset:                  env("LOC_DB_CHARSET", "utf8mb4"),
			GroupCodeBackfillEnabled: envBool("LOC_GROUP_CODE_BACKFILL_ENABLED", true),
		},
	}
}

func Validate(cfg Config) error {
	if strings.TrimSpace(cfg.App.UserAgentToken) == "" {
		return fmt.Errorf("LOC_APP_USER_AGENT_TOKEN is required")
	}
	if len(cfg.App.SigningSecret) < 32 {
		return fmt.Errorf("LOC_APP_SIGNING_SECRET must contain at least 32 characters")
	}
	if cfg.App.SessionLifetime <= 0 {
		return fmt.Errorf("LOC_SESSION_LIFETIME_SECONDS must be positive")
	}
	if strings.TrimSpace(cfg.Auth.AdminUsername) == "" {
		return fmt.Errorf("LOC_ADMIN_USERNAME is required")
	}
	if strings.TrimSpace(cfg.Auth.AdminPassword) == "" && strings.TrimSpace(cfg.Auth.AdminPasswordHash) == "" {
		return fmt.Errorf("LOC_ADMIN_PASSWORD or LOC_ADMIN_PASSWORD_HASH is required")
	}
	if strings.TrimSpace(cfg.Database.Name) == "" || strings.TrimSpace(cfg.Database.User) == "" || strings.TrimSpace(cfg.Database.Pass) == "" {
		return fmt.Errorf("LOC_DB_NAME, LOC_DB_USER and LOC_DB_PASS are required")
	}
	for _, provider := range []struct {
		name       string
		credential string
		alwaysOn   bool
	}{
		{name: "ipinfo-lite", credential: cfg.External.IPInfoLiteToken},
		{name: "ip2location", credential: cfg.External.IP2LocationKey},
		{name: "ipdata", credential: cfg.External.IPDataKey},
		{name: "ipregistry", credential: cfg.External.IPRegistryKey},
		{name: "ip-api", alwaysOn: true},
		{name: "uapis", alwaysOn: true},
		{name: "baidu", alwaysOn: true},
		{name: "iping", alwaysOn: true},
		{name: "xxapi", alwaysOn: true},
	} {
		quota, ok := cfg.External.IPGeoQuota(provider.name)
		if provider.alwaysOn {
			if !ok {
				continue
			}
		} else if strings.TrimSpace(provider.credential) == "" {
			continue
		}
		if !ok {
			return fmt.Errorf("%s provider quota is required when its credential is configured", provider.name)
		}
		if quota.MaxRequests <= 0 || quota.ReserveRequests <= 0 || quota.ReserveRequests >= quota.MaxRequests {
			return fmt.Errorf("%s provider quota requires a positive plan maximum and safety reserve below that maximum", provider.name)
		}
		if quota.Window <= 0 {
			return fmt.Errorf("%s provider quota reset window must be positive", provider.name)
		}
		if quota.UserMaxMisses <= 0 || quota.UserMaxMisses > quota.AvailableRequests() {
			return fmt.Errorf("%s per-user miss quota must be positive and no greater than the provider lane", provider.name)
		}
	}
	for name, value := range map[string]string{
		"LOC_ANDROID_APK_FILENAME":       cfg.Files.UserAPKFilename,
		"LOC_ANDROID_ADMIN_APK_FILENAME": cfg.Files.AdminAPKFilename,
	} {
		cleaned := filepath.Clean(value)
		if filepath.IsAbs(cleaned) || cleaned == "." || cleaned == ".." || strings.HasPrefix(cleaned, ".."+string(filepath.Separator)) {
			return fmt.Errorf("%s must stay inside LOC_PUBLIC_BASE_DIR", name)
		}
	}
	return nil
}

func env(key string, fallback string) string {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	return value
}

func envInt(key string, fallback int) int {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return fallback
	}
	value, err := strconv.Atoi(raw)
	if err != nil {
		return fallback
	}
	return value
}

func envPositiveInt(key string, fallback int) int {
	value := envInt(key, fallback)
	if value <= 0 {
		return fallback
	}
	return value
}

func envBool(key string, fallback bool) bool {
	raw := strings.ToLower(strings.TrimSpace(os.Getenv(key)))
	if raw == "" {
		return fallback
	}
	switch raw {
	case "1", "true", "yes", "on":
		return true
	case "0", "false", "no", "off":
		return false
	default:
		return fallback
	}
}

func envFloat(key string, fallback float64) float64 {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return fallback
	}
	value, err := strconv.ParseFloat(raw, 64)
	if err != nil {
		return fallback
	}
	return value
}

func envPositiveFloat(key string, fallback float64) float64 {
	value := envFloat(key, fallback)
	if value <= 0 || math.IsNaN(value) || math.IsInf(value, 0) {
		return fallback
	}
	return value
}

func loadIPGeoProviderQuota(prefix string, fallback IPGeoProviderQuota) IPGeoProviderQuota {
	return IPGeoProviderQuota{
		MaxRequests:     envInt(prefix+"_QUOTA_MAX_REQUESTS", fallback.MaxRequests),
		ReserveRequests: envInt(prefix+"_QUOTA_RESERVE_REQUESTS", fallback.ReserveRequests),
		UserMaxMisses:   envInt(prefix+"_QUOTA_USER_MAX_MISSES", fallback.UserMaxMisses),
		Window:          time.Duration(envInt(prefix+"_QUOTA_WINDOW_SECONDS", int(fallback.Window/time.Second))) * time.Second,
	}
}
