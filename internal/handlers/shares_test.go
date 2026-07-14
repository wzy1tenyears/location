package handlers

import (
	"database/sql"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/models"
	"familylocation/location-v3/internal/repositories"
)

func TestNormalizeShareLocationIDs(t *testing.T) {
	values := normalizeShareLocationIDs([]int64{4, 0, 4, -1, 9})
	if len(values) != 2 || values[0] != 4 || values[1] != 9 {
		t.Fatalf("normalizeShareLocationIDs() = %#v", values)
	}
}

func TestShareLifetimeForHours(t *testing.T) {
	for _, hours := range []int{1, 24, 168, 720} {
		duration, ok := shareLifetimeForHours(hours)
		if !ok || duration != time.Duration(hours)*time.Hour {
			t.Fatalf("shareLifetimeForHours(%d) = %v, %v", hours, duration, ok)
		}
	}
	if _, ok := shareLifetimeForHours(48); ok {
		t.Fatal("shareLifetimeForHours() accepted an unsupported duration")
	}
}

func TestPublicShareTokenSupportsPathAndQuery(t *testing.T) {
	token := "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	pathRequest := httptest.NewRequest("GET", "/share/token="+token, nil)
	if got := publicShareToken(pathRequest); got != token {
		t.Fatalf("publicShareToken(path) = %q", got)
	}
	queryRequest := httptest.NewRequest("GET", "/share?token="+token, nil)
	if got := publicShareToken(queryRequest); got != token {
		t.Fatalf("publicShareToken(query) = %q", got)
	}
}

func TestPublicSharePathUsesCanonicalQueryURL(t *testing.T) {
	token := strings.Repeat("a", 64)
	if got := publicSharePath(token); got != "/share?token="+token {
		t.Fatalf("publicSharePath() = %q", got)
	}
}

func TestShareHistoryDetailsDoNotDependOnMap(t *testing.T) {
	expand := strings.Index(locationShareHTML, "if (toggleDetails) setExpandedRow(index,true);")
	mapGuard := strings.Index(locationShareHTML, "if (!map || !markers[index]) return;")
	if expand < 0 || mapGuard < 0 || expand > mapGuard {
		t.Fatal("share history details are still blocked by map initialization")
	}
}

func TestPublicShareLocationsDropsDiagnosticsAndUsername(t *testing.T) {
	rows := []models.Location{{
		ID:          7,
		Username:    "private-login",
		DisplayName: "成员甲",
		Role:        "guardian",
		Latitude:    23.1,
		Longitude:   113.2,
		CreatedAt:   time.Date(2026, 7, 11, 12, 0, 0, 0, time.Local),
		AddressDiagnostics: sql.NullString{Valid: true, String: `{
			"preferred_address":"测试地址",
			"preferred_city":"广州市",
			"sources":[{"type":"ip","ip":"203.0.113.1"}]
		}`},
	}}
	payload := publicShareLocations(rows)
	if len(payload) != 1 {
		t.Fatalf("publicShareLocations() length = %d", len(payload))
	}
	if _, exists := payload[0]["username"]; exists {
		t.Fatal("public payload exposed username")
	}
	if _, exists := payload[0]["_snapshot_provenance"]; exists {
		t.Fatal("public payload exposed internal snapshot provenance")
	}
	if payload[0]["address"] != "测试地址" || payload[0]["city"] != "广州市" {
		t.Fatalf("public detail fields are incomplete: %#v", payload[0])
	}
	if payload[0]["role_label"] != "监护端" {
		t.Fatalf("unexpected public role label: %#v", payload[0]["role_label"])
	}
	diagnostics, ok := payload[0]["address_diagnostics"].(map[string]any)
	if !ok || diagnostics["preferred_address"] != "测试地址" {
		t.Fatalf("unexpected diagnostics: %#v", payload[0]["address_diagnostics"])
	}
	if _, exists := diagnostics["sources"]; exists {
		t.Fatal("public payload exposed diagnostic sources")
	}
}

func TestOwnedSharesPagination(t *testing.T) {
	req := httptest.NewRequest("GET", "/api/share?limit=500&offset=-2", nil)
	limit, offset := ownedSharesPagination(req)
	if limit != maxOwnedSharesLimit || offset != 0 {
		t.Fatalf("ownedSharesPagination() = %d, %d", limit, offset)
	}

	req = httptest.NewRequest("GET", "/api/share?limit=0&offset=12", nil)
	limit, offset = ownedSharesPagination(req)
	if limit != defaultOwnedSharesLimit || offset != 12 {
		t.Fatalf("ownedSharesPagination() fallback = %d, %d", limit, offset)
	}
}

func TestBuildOwnedLocationShareItemAllowsLegacyRowsWithoutPlaintext(t *testing.T) {
	now := time.Date(2026, 7, 11, 20, 0, 0, 0, time.Local)
	item := buildOwnedLocationShareItem(
		httptest.NewRequest("GET", "https://location.example/api/share", nil),
		repositories.LocationShare{
			TokenHash:       strings.Repeat("a", 64),
			LocationIDsJSON: `[7,7,9,0]`,
			CreatedAt:       now.Add(-time.Hour),
			ExpiresAt:       now.Add(time.Hour),
		},
		now,
	)
	if item.ShareURL != "" || item.AccessCode != "" {
		t.Fatalf("legacy share exposed secrets: %#v", item)
	}
	if item.LocationCount != 2 || !item.Active {
		t.Fatalf("legacy share metadata is incorrect: %#v", item)
	}
}

func TestBuildOwnedLocationShareItemReadsPlaintext(t *testing.T) {
	tokenHash := strings.Repeat("c", 64)
	token := strings.Repeat("d", 64)
	now := time.Date(2026, 7, 11, 20, 0, 0, 0, time.Local)
	item := buildOwnedLocationShareItem(
		httptest.NewRequest("GET", "https://location.example/api/share", nil),
		repositories.LocationShare{
			TokenHash:           tokenHash,
			TokenPlaintext:      token,
			LocationIDsJSON:     `[4,8]`,
			AccessCodePlaintext: "2468",
			CreatedAt:           now.Add(-2 * time.Hour),
			ExpiresAt:           now.Add(-time.Minute),
		},
		now,
	)
	if item.ShareURL != "https://location.example/share?token="+token || item.AccessCode != "2468" {
		t.Fatalf("decrypted share secrets are incorrect: %#v", item)
	}
	if item.LocationCount != 2 || item.Active {
		t.Fatalf("decrypted share metadata is incorrect: %#v", item)
	}
}

func TestShareSnapshotCoordinateRejectsUnverifiableP2PPlaintext(t *testing.T) {
	_, err := shareSnapshotCoordinate(models.Location{ID: 9, UserID: 3, EncryptionMode: "p2p-v1"})
	var apiErr httpx.APIError
	if !errors.As(err, &apiErr) || apiErr.Status != http.StatusUnprocessableEntity {
		t.Fatalf("shareSnapshotCoordinate() error = %#v, want 422", err)
	}
}

func TestShareSnapshotCoordinateIgnoresForgedPlainSnapshot(t *testing.T) {
	coordinate, err := shareSnapshotCoordinate(models.Location{
		ID:        12,
		UserID:    4,
		Latitude:  31.2304,
		Longitude: 121.4737,
		AddressDiagnostics: sql.NullString{
			Valid:  true,
			String: `{"preferred_address":"服务端地址","preferred_city":"上海市"}`,
		},
	})
	if err != nil {
		t.Fatalf("shareSnapshotCoordinate() error = %v", err)
	}
	if coordinate.Latitude != 31.2304 || coordinate.Longitude != 121.4737 || coordinate.Address != "" || coordinate.City != "" {
		t.Fatalf("plain snapshot was not replaced with server data: %#v", coordinate)
	}
	if coordinate.AddressDiagnostics["preferred_address"] != "服务端地址" {
		t.Fatalf("server diagnostics were not preserved: %#v", coordinate.AddressDiagnostics)
	}
}

func TestPublicShareSnapshotRequiresServerProvenance(t *testing.T) {
	row := models.Location{ID: 15, UserID: 5, DisplayName: "成员丙", Role: "member", CreatedAt: time.Now()}
	payload := publicShareSnapshot([]models.Location{row}, map[int64]locationShareSnapshotInput{
		15: {ID: 15, Latitude: 23.123, Longitude: 113.456},
	})
	if len(payload) != 1 || payload[0]["_snapshot_provenance"] != publicShareSnapshotProvenance {
		t.Fatalf("server snapshot provenance is missing: %#v", payload)
	}
	if !consumeTrustedPublicShareSnapshot(payload) {
		t.Fatal("server snapshot provenance was rejected")
	}
	if _, exists := payload[0]["_snapshot_provenance"]; exists {
		t.Fatal("internal snapshot provenance leaked into the public payload")
	}
	if consumeTrustedPublicShareSnapshot([]map[string]any{{"id": float64(15)}}) {
		t.Fatal("legacy unverified snapshot was accepted")
	}
}
