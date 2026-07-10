package config

import (
	"os"
	"strconv"
	"strings"
	"time"
)

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
	Addr string
}

type AppConfig struct {
	Name              string
	UserAgentToken    string
	VersionCode       int
	VersionName       string
	ForceUpdate       bool
	DeviceCookieName  string
	SessionCookieName string
	PHPSessionDir     string
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
	AdminPath         string
}

type FileConfig struct {
	PublicBaseDir    string
	UserAPKFilename  string
	AdminAPKFilename string
}

type ExternalConfig struct {
	IPInfoLiteToken    string
	IP2LocationKey     string
	IPDataKey          string
	IPRegistryKey      string
	TurnstileSiteKey   string
	TurnstileSecretKey string
	AMapJSAPIKey       string
	AMapServicePath    string
}

type LocationConfig struct {
	HistoryLimit             int
	MinReportSeconds         int
	MaxAccuracyMeters        float64
	MaxSpeedMPS              float64
	MaxReasonableTravelMPS   float64
	DiagnosticsUpdateSeconds int
	MaxDiagnosticsBytes      int
}

type DatabaseConfig struct {
	Host    string
	Port    int
	Name    string
	User    string
	Pass    string
	Charset string
}

func Load() Config {
	return Config{
		Server: ServerConfig{
			Addr: env("LOC_GO_ADDR", "127.0.0.1:8088"),
		},
		App: AppConfig{
			Name:              env("LOC_APP_NAME", "位置"),
			UserAgentToken:    env("LOC_APP_USER_AGENT_TOKEN", "loc-app"),
			VersionCode:       envInt("LOC_ANDROID_VERSION_CODE", 133),
			VersionName:       env("LOC_ANDROID_VERSION_NAME", "2.1.0"),
			ForceUpdate:       envBool("LOC_ANDROID_FORCE_UPDATE", true),
			DeviceCookieName:  env("LOC_DEVICE_COOKIE_NAME", "loc_device"),
			SessionCookieName: env("LOC_SESSION_COOKIE_NAME", "family_location_session"),
			PHPSessionDir:     env("LOC_PHP_SESSION_DIR", ""),
			SessionLifetime:   time.Duration(envInt("LOC_SESSION_LIFETIME_SECONDS", 2592000)) * time.Second,
		},
		Admin: AdminConfig{
			VersionCode: envInt("LOC_ANDROID_ADMIN_VERSION_CODE", 90),
			VersionName: env("LOC_ANDROID_ADMIN_VERSION_NAME", "2.1.0"),
			ForceUpdate: envBool("LOC_ANDROID_ADMIN_FORCE_UPDATE", true),
		},
		Auth: AuthConfig{
			AdminUsername:     env("LOC_ADMIN_USERNAME", "admin114514"),
			AdminPassword:     env("LOC_ADMIN_PASSWORD", ""),
			AdminPasswordHash: env("LOC_ADMIN_PASSWORD_HASH", ""),
			AdminPath:         env("LOC_ADMIN_PATH", "admin114514"),
		},
		Files: FileConfig{
			PublicBaseDir:    env("LOC_PUBLIC_BASE_DIR", "."),
			UserAPKFilename:  env("LOC_ANDROID_APK_FILENAME", "location-release.apk"),
			AdminAPKFilename: env("LOC_ANDROID_ADMIN_APK_FILENAME", "private/location-admin-release.apk"),
		},
		External: ExternalConfig{
			IPInfoLiteToken:    env("LOC_IPINFO_LITE_TOKEN", ""),
			IP2LocationKey:     env("LOC_IP2LOCATION_IO_KEY", ""),
			IPDataKey:          env("LOC_IPDATA_API_KEY", ""),
			IPRegistryKey:      env("LOC_IPREGISTRY_API_KEY", ""),
			TurnstileSiteKey:   env("LOC_CF_TURNSTILE_SITE_KEY", ""),
			TurnstileSecretKey: env("LOC_CF_TURNSTILE_SECRET_KEY", ""),
			AMapJSAPIKey:       env("LOC_AMAP_JS_API_KEY", ""),
			AMapServicePath:    env("LOC_AMAP_SERVICE_PROXY_PATH", "/_AMapService"),
		},
		Location: LocationConfig{
			HistoryLimit:             envInt("LOC_LOCATION_HISTORY_LIMIT", 5000),
			MinReportSeconds:         envInt("LOC_MIN_LOCATION_REPORT_SECONDS", 10),
			MaxAccuracyMeters:        envFloat("LOC_MAX_LOCATION_ACCURACY_METERS", 5000),
			MaxSpeedMPS:              envFloat("LOC_MAX_LOCATION_SPEED_MPS", 120),
			MaxReasonableTravelMPS:   envFloat("LOC_MAX_REASONABLE_TRAVEL_MPS", 120),
			DiagnosticsUpdateSeconds: envInt("LOC_LOCATION_DIAGNOSTICS_UPDATE_SECONDS", 600),
			MaxDiagnosticsBytes:      envInt("LOC_MAX_ADDRESS_DIAGNOSTICS_BYTES", 12000),
		},
		Database: DatabaseConfig{
			Host:    env("LOC_DB_HOST", "127.0.0.1"),
			Port:    envInt("LOC_DB_PORT", 3306),
			Name:    env("LOC_DB_NAME", "family_loc"),
			User:    env("LOC_DB_USER", "family_loc"),
			Pass:    env("LOC_DB_PASS", ""),
			Charset: env("LOC_DB_CHARSET", "utf8mb4"),
		},
	}
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
