package config

import (
	"fmt"
	"os"
	"path/filepath"
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
			VersionCode:       envInt("LOC_ANDROID_VERSION_CODE", 135),
			VersionName:       env("LOC_ANDROID_VERSION_NAME", "2.1.0"),
			ForceUpdate:       envBool("LOC_ANDROID_FORCE_UPDATE", true),
			DeviceCookieName:  env("LOC_DEVICE_COOKIE_NAME", "loc_device"),
			SessionCookieName: env("LOC_SESSION_COOKIE_NAME", "family_location_session"),
			SigningSecret:     env("LOC_APP_SIGNING_SECRET", ""),
			SessionLifetime:   time.Duration(envInt("LOC_SESSION_LIFETIME_SECONDS", 2592000)) * time.Second,
		},
		Admin: AdminConfig{
			VersionCode: envInt("LOC_ANDROID_ADMIN_VERSION_CODE", 92),
			VersionName: env("LOC_ANDROID_ADMIN_VERSION_NAME", "2.1.0"),
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
