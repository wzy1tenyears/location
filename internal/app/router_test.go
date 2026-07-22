package app

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"familylocation/location-v3/internal/config"
)

func TestRouterAppliesWriteFreezeBeforeEveryAPIRoute(t *testing.T) {
	const deploymentID = "20260719-010203-a1b2c3d4"
	const token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	path := filepath.Join(t.TempDir(), "write-freeze")
	content := "VERSION=1\nDEPLOYMENT_ID=" + deploymentID + "\nTOKEN=" + token + "\n"
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}

	cfg := config.Config{
		Server: config.ServerConfig{WriteFreezeFile: path},
		App: config.AppConfig{
			UserAgentToken:    "loc-app",
			SessionCookieName: "session",
		},
	}
	router := NewRouter(cfg, nil)

	blocked := httptest.NewRequest(http.MethodPost, "/api/not-a-real-route", strings.NewReader("{}"))
	blockedRecorder := httptest.NewRecorder()
	router.ServeHTTP(blockedRecorder, blocked)
	if blockedRecorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("blocked status = %d, want 503", blockedRecorder.Code)
	}

	verified := httptest.NewRequest(http.MethodPost, "/api/__deployment-write-freeze-bypass-probe__", strings.NewReader("{}"))
	verified.Header.Set("X-Location-Deployment-Verify", deploymentID+":"+token)
	verifiedRecorder := httptest.NewRecorder()
	router.ServeHTTP(verifiedRecorder, verified)
	if verifiedRecorder.Code != http.StatusNotFound {
		t.Fatalf("verified status = %d, want downstream 404", verifiedRecorder.Code)
	}
}
