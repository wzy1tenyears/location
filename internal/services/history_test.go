package services

import (
	"database/sql"
	"encoding/json"
	"math"
	"testing"
	"time"

	"familylocation/location-v3/internal/models"
)

func TestMergeLocationHistoryDistanceThresholdIsInclusive(t *testing.T) {
	base := time.Date(2026, 7, 16, 12, 0, 0, 0, time.UTC)
	tests := []struct {
		name      string
		distance  float64
		wantStays int
	}{
		{name: "24.9 meters", distance: 24.9, wantStays: 1},
		{name: "25 meters", distance: 25, wantStays: 1},
		{name: "25.1 meters", distance: 25.1, wantStays: 2},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			rows := []models.Location{
				testLocation(1, 11, "family-a", 0, base),
				testLocation(2, 11, "family-a", test.distance, base.Add(time.Minute)),
			}
			got := MergeLocationHistory(rows, 25)
			if len(got) != test.wantStays {
				t.Fatalf("len(MergeLocationHistory()) = %d, want %d (distance %.1f, measured %.9f)", len(got), test.wantStays, test.distance, haversineMeters(rows[0].Latitude, rows[0].Longitude, rows[1].Latitude, rows[1].Longitude))
			}
		})
	}
}

func TestMergeLocationHistoryUsesFirstPointAnchor(t *testing.T) {
	base := time.Date(2026, 7, 16, 12, 0, 0, 0, time.UTC)
	rows := []models.Location{
		testLocation(1, 11, "family-a", 0, base),
		testLocation(2, 11, "family-a", 20, base.Add(time.Minute)),
		testLocation(3, 11, "family-a", 40, base.Add(2*time.Minute)),
	}

	got := MergeLocationHistory(rows, 25)
	if len(got) != 2 {
		t.Fatalf("len(MergeLocationHistory()) = %d, want 2", len(got))
	}
	if got[0].ID != 3 || got[0].ReportCount != 1 {
		t.Fatalf("newest stay = id %d count %d, want id 3 count 1", got[0].ID, got[0].ReportCount)
	}
	if got[1].ID != 2 || got[1].ReportCount != 2 {
		t.Fatalf("anchored stay = id %d count %d, want id 2 count 2", got[1].ID, got[1].ReportCount)
	}
}

func TestMergeLocationHistoryDoesNotJoinARevisit(t *testing.T) {
	base := time.Date(2026, 7, 16, 12, 0, 0, 0, time.UTC)
	rows := []models.Location{
		testLocation(1, 11, "family-a", 0, base),
		testLocation(2, 11, "family-a", 100, base.Add(time.Minute)),
		testLocation(3, 11, "family-a", 0, base.Add(2*time.Minute)),
	}

	got := MergeLocationHistory(rows, 25)
	if len(got) != 3 {
		t.Fatalf("len(MergeLocationHistory()) = %d, want 3 separate visits", len(got))
	}
	for _, stay := range got {
		if stay.ReportCount != 1 {
			t.Fatalf("stay id %d report_count = %d, want 1", stay.ID, stay.ReportCount)
		}
	}
}

func TestMergeLocationHistoryPartitionsAndSorts(t *testing.T) {
	base := time.Date(2026, 7, 16, 12, 0, 0, 0, time.UTC)
	rows := []models.Location{
		testLocation(1, 11, "family-a", 0, base),
		testLocation(2, 12, "family-a", 0, base.Add(time.Minute)),
		testLocation(3, 11, "family-b", 0, base.Add(2*time.Minute)),
		testLocation(4, 11, "family-b", 5, base.Add(3*time.Minute)),
	}

	got := MergeLocationHistory(rows, 25)
	if len(got) != 3 {
		t.Fatalf("len(MergeLocationHistory()) = %d, want 3 partitions/stays", len(got))
	}
	if got[0].ID != 4 || got[0].GroupName != "family-b" || got[0].UserID != 11 || got[0].ReportCount != 2 {
		t.Fatalf("newest merged stay = %#v", got[0])
	}
	if got[1].ID != 2 || got[2].ID != 1 {
		t.Fatalf("newest-first ids = [%d %d %d], want [4 2 1]", got[0].ID, got[1].ID, got[2].ID)
	}
}

func TestMergeLocationHistoryKeepsUnverifiableRecordsSingle(t *testing.T) {
	base := time.Date(2026, 7, 16, 12, 0, 0, 0, time.UTC)
	rows := []models.Location{
		testLocation(1, 11, "p2p-break", 0, base),
		testLocation(2, 11, "p2p-break", 0, base.Add(time.Minute)),
		testLocation(3, 11, "p2p-break", 0, base.Add(2*time.Minute)),
		testLocation(4, 12, "zero", 0, base),
		testLocation(5, 12, "zero", 0, base.Add(time.Minute)),
		testLocation(6, 13, "invalid", 0, base),
		testLocation(7, 13, "invalid", 0, base.Add(time.Minute)),
		testLocation(8, 14, "systems", 0, base),
		testLocation(9, 14, "systems", 0, base.Add(time.Minute)),
	}
	rows[1].EncryptionMode = "p2p-v1"
	rows[1].EncryptedPayload = `{"ciphertext":"opaque"}`
	rows[3].Latitude, rows[3].Longitude = 0, 0
	rows[4].Latitude, rows[4].Longitude = 0, 0
	rows[5].Latitude = 91
	rows[6].Latitude = 91
	rows[8].LocationMeta = sql.NullString{String: `{"coordinate_system":"gcj02"}`, Valid: true}

	got := MergeLocationHistory(rows, 25)
	if len(got) != len(rows) {
		t.Fatalf("len(MergeLocationHistory()) = %d, want %d single records", len(got), len(rows))
	}
	for _, stay := range got {
		if stay.ReportCount != 1 || stay.StayDurationSeconds != 0 {
			t.Fatalf("unverifiable stay id %d count=%d duration=%d", stay.ID, stay.ReportCount, stay.StayDurationSeconds)
		}
	}
}

func TestMergeLocationHistoryUsesLatestRecordAndLatestUsableAddress(t *testing.T) {
	base := time.Date(2026, 7, 16, 12, 0, 0, 0, time.UTC)
	rows := []models.Location{
		testLocation(1, 11, "family-a", 0, base),
		testLocation(2, 11, "family-a", 5, base.Add(time.Minute)),
		testLocation(3, 11, "family-a", 10, base.Add(2*time.Minute)),
		testLocation(4, 11, "family-a", 15, base.Add(3*time.Minute)),
	}
	rows[0].AddressDiagnostics = sql.NullString{String: `{"preferred_address":"old"}`, Valid: true}
	rows[1].AddressDiagnostics = sql.NullString{String: `{"preferred_poi":"newest usable"}`, Valid: true}
	rows[1].AddressMismatch = true
	rows[2].AddressDiagnostics = sql.NullString{String: `{not-json`, Valid: true}
	rows[3].AddressDiagnostics = sql.NullString{String: `{}`, Valid: true}
	rows[3].Username = "latest-user-snapshot"

	got := MergeLocationHistory(rows, 25)
	if len(got) != 1 {
		t.Fatalf("len(MergeLocationHistory()) = %d, want 1", len(got))
	}
	stay := got[0]
	if stay.ID != 4 || stay.Username != "latest-user-snapshot" || stay.Latitude != rows[3].Latitude || !stay.CreatedAt.Equal(rows[3].CreatedAt) {
		t.Fatalf("representative is not the latest record: %#v", stay)
	}
	diagnostics := decodeHistoryDiagnostics(t, stay.AddressDiagnostics)
	if diagnostics["preferred_poi"] != "newest usable" {
		t.Fatalf("merged address diagnostics = %#v, want latest precise POI package", diagnostics)
	}
	if _, mixed := diagnostics["preferred_address"]; mixed {
		t.Fatalf("preferred address was mixed into a different selected package: %#v", diagnostics)
	}
	if !stay.AddressMismatch {
		t.Fatal("address_mismatch did not follow the diagnostics selected from the earlier row")
	}
	if !stay.FirstReportedAt.Equal(base) || !stay.LastReportedAt.Equal(base.Add(3*time.Minute)) {
		t.Fatalf("stay range = %s to %s", stay.FirstReportedAt, stay.LastReportedAt)
	}
	if stay.StayDurationSeconds != 180 || stay.ReportCount != 4 {
		t.Fatalf("duration/count = %d/%d, want 180/4", stay.StayDurationSeconds, stay.ReportCount)
	}
}

func TestMergeLocationHistoryKeepsLatestDiagnosticsWhenStayHasNoAddress(t *testing.T) {
	base := time.Date(2026, 7, 16, 12, 0, 0, 0, time.UTC)
	rows := []models.Location{
		testLocation(1, 11, "family-a", 0, base),
		testLocation(2, 11, "family-a", 5, base.Add(time.Minute)),
	}
	rows[0].AddressDiagnostics = sql.NullString{String: `{"provider":"old-probe"}`, Valid: true}
	rows[1].AddressDiagnostics = sql.NullString{String: `{"provider":"new-probe"}`, Valid: true}

	got := MergeLocationHistory(rows, 25)
	if len(got) != 1 || got[0].AddressDiagnostics.String != rows[1].AddressDiagnostics.String {
		t.Fatalf("diagnostics = %#v, want latest no-address diagnostics %q", got, rows[1].AddressDiagnostics.String)
	}
}

func TestMergeLocationHistoryPreservesLatestUsableSourcePerProbeType(t *testing.T) {
	base := time.Date(2026, 7, 16, 12, 0, 0, 0, time.UTC)
	rows := []models.Location{
		testLocation(1, 11, "family-a", 0, base),
		testLocation(2, 11, "family-a", 5, base.Add(time.Minute)),
	}
	rows[0].AddressDiagnostics = sql.NullString{String: `{
		"checked_at":"2026-07-16T12:00:00Z",
		"preferred_source":"ip",
		"preferred_address":"Earlier IP address",
		"earlier_probe_metadata":"retained",
		"sources":[
			{"type":"gps","source":"device-gps","provider":"gps-old","address":"Old GPS address"},
			{"type":"ip","provider":"ip-old","address":"Precise IP address","detail":"Building A","latitude":31.2304,"longitude":121.4737,"variants":[{"address":"IP variant address","detail":"Suite 8"}]},
			{"type":"webrtc","provider":"webrtc-old","address":"Precise WebRTC address","latitude":22.5431,"longitude":114.0579}
		]
	}`, Valid: true}
	rows[0].AddressMismatch = true
	rows[1].AddressDiagnostics = sql.NullString{String: `{
		"checked_at":"2026-07-16T12:01:00Z",
		"complete":true,
		"preferred_source":"gps",
		"preferred_address":"Latest GPS address",
		"latest_probe_metadata":"retained",
		"sources":[{"type":"gps","source":"device-gps","provider":"gps-new","address":"Latest GPS address"}]
	}`, Valid: true}
	rows[1].AddressMismatch = false

	got := MergeLocationHistory(rows, 25)
	if len(got) != 1 {
		t.Fatalf("len(MergeLocationHistory()) = %d, want 1", len(got))
	}
	diagnostics := decodeHistoryDiagnostics(t, got[0].AddressDiagnostics)
	if diagnostics["checked_at"] != "2026-07-16T12:01:00Z" || diagnostics["preferred_source"] != "gps" || diagnostics["preferred_address"] != "Latest GPS address" {
		t.Fatalf("latest root diagnostics were not retained: %#v", diagnostics)
	}
	if diagnostics["earlier_probe_metadata"] != "retained" || diagnostics["latest_probe_metadata"] != "retained" {
		t.Fatalf("root diagnostic metadata was lost: %#v", diagnostics)
	}
	sources := historyDiagnosticSourcesByType(t, diagnostics)
	if len(sources) != 3 {
		t.Fatalf("source types = %#v, want gps, ip, and webrtc", sources)
	}
	if sources["gps"]["provider"] != "gps-new" || sources["gps"]["address"] != "Latest GPS address" {
		t.Fatalf("GPS source = %#v, want latest source", sources["gps"])
	}
	if sources["ip"]["address"] != "Precise IP address" || sources["ip"]["detail"] != "Building A" {
		t.Fatalf("IP source was lost: %#v", sources["ip"])
	}
	if sources["webrtc"]["address"] != "Precise WebRTC address" {
		t.Fatalf("WebRTC source was lost: %#v", sources["webrtc"])
	}
	if got[0].AddressMismatch {
		t.Fatal("address_mismatch did not follow the latest diagnostic run")
	}
}

func TestMergeLocationHistoryPreservesDistinctCoherentSourceBundles(t *testing.T) {
	base := time.Date(2026, 7, 16, 12, 0, 0, 0, time.UTC)
	rows := []models.Location{
		testLocation(1, 11, "family-a", 0, base),
		testLocation(2, 11, "family-a", 5, base.Add(time.Minute)),
	}
	rows[0].AddressDiagnostics = sql.NullString{String: `{
		"checked_at":"old",
		"preferred_source":"ip",
		"preferred_address":"Precise old address",
		"preferred_detail":"Building A, Room 8",
		"sources":[{"type":"ip","provider":"old-provider","ip":"198.51.100.1","address":"Precise old address","detail":"Building A, Room 8","street":"Science Road","latitude":31.2304,"longitude":121.4737}]
	}`, Valid: true}
	rows[0].AddressMismatch = true
	rows[1].AddressDiagnostics = sql.NullString{String: `{
		"checked_at":"latest",
		"preferred_source":"ip",
		"preferred_city":"Shanghai",
		"sources":[{"type":"ip","provider":"latest-provider","ip":"198.51.100.2","city":"Shanghai","latitude":31.23,"longitude":121.47}]
	}`, Valid: true}

	got := MergeLocationHistory(rows, 25)
	diagnostics := decodeHistoryDiagnostics(t, got[0].AddressDiagnostics)
	if diagnostics["checked_at"] != "latest" || diagnostics["preferred_address"] != "Precise old address" || diagnostics["preferred_detail"] != "Building A, Room 8" {
		t.Fatalf("root precision/latest metadata merge = %#v", diagnostics)
	}
	if _, mixed := diagnostics["preferred_city"]; mixed {
		t.Fatalf("preferred place fields were mixed across distinct diagnostic packages: %#v", diagnostics)
	}
	ipSources := historyDiagnosticSourcesOfType(t, diagnostics, "ip")
	if len(ipSources) != 2 {
		t.Fatalf("IP source count = %d, want both distinct IP identities: %#v", len(ipSources), ipSources)
	}
	oldSource := historyDiagnosticSourceByField(t, ipSources, "ip", "198.51.100.1")
	latestSource := historyDiagnosticSourceByField(t, ipSources, "ip", "198.51.100.2")
	if oldSource["provider"] != "old-provider" || oldSource["address"] != "Precise old address" ||
		oldSource["detail"] != "Building A, Room 8" || oldSource["street"] != "Science Road" {
		t.Fatalf("precise old IP package was mixed or lost: %#v", oldSource)
	}
	if latestSource["provider"] != "latest-provider" || latestSource["city"] != "Shanghai" {
		t.Fatalf("latest IP package was mixed or lost: %#v", latestSource)
	}
	if !got[0].AddressMismatch {
		t.Fatal("address_mismatch did not follow the selected precise diagnostic bundle")
	}
}

func TestMergeLocationHistoryUsesLatestEquallyPreciseSource(t *testing.T) {
	base := time.Date(2026, 7, 16, 12, 0, 0, 0, time.UTC)
	rows := []models.Location{
		testLocation(1, 11, "family-a", 0, base),
		testLocation(2, 11, "family-a", 5, base.Add(time.Minute)),
	}
	rows[0].AddressDiagnostics = sql.NullString{String: `{"sources":[{"type":"webrtc","source":"stun-probe","provider":"old-provider","ip":"203.0.113.7","stun_server":"stun.example:3478","address":"Old exact address"}]}`, Valid: true}
	rows[1].AddressDiagnostics = sql.NullString{String: `{"sources":[{"type":"webrtc","source":"stun-probe","provider":"new-provider","server_ip":"203.0.113.7","stun_server":"stun.example:3478","address":"New exact address"}]}`, Valid: true}

	got := MergeLocationHistory(rows, 25)
	webrtc := historyDiagnosticSourcesByType(t, decodeHistoryDiagnostics(t, got[0].AddressDiagnostics))["webrtc"]
	if webrtc["provider"] != "new-provider" || webrtc["address"] != "New exact address" {
		t.Fatalf("equally precise WebRTC source = %#v, want latest", webrtc)
	}
}

func TestMergeLocationHistoryKeepsRicherPackageAtSamePrecisionLevel(t *testing.T) {
	base := time.Date(2026, 7, 16, 12, 0, 0, 0, time.UTC)
	rows := []models.Location{
		testLocation(1, 11, "family-a", 0, base),
		testLocation(2, 11, "family-a", 5, base.Add(time.Minute)),
	}
	rows[0].AddressDiagnostics = sql.NullString{Valid: true, String: `{
		"preferred_source":"ip","preferred_address":"上海市黄浦区人民大道 200 号","preferred_detail":"人民广场","preferred_street":"人民大道",
		"sources":[{
		"type":"ip","source":"server","ip":"198.51.100.8","provider":"rich",
		"address":"上海市黄浦区人民大道 200 号","detail":"人民广场","street":"人民大道"
	}]}`}
	rows[1].AddressDiagnostics = sql.NullString{Valid: true, String: `{
		"preferred_source":"ip","preferred_detail":"新探测门牌",
		"sources":[{
		"type":"ip","source":"server","server_ip":"198.51.100.8","provider":"sparse","detail":"新探测门牌"
	}]}`}

	got := MergeLocationHistory(rows, 25)
	diagnostics := decodeHistoryDiagnostics(t, got[0].AddressDiagnostics)
	if diagnostics["preferred_address"] != "上海市黄浦区人民大道 200 号" || diagnostics["preferred_detail"] != "人民广场" ||
		diagnostics["preferred_street"] != "人民大道" {
		t.Fatalf("richer preferred package was replaced or mixed: %#v", diagnostics)
	}
	ipSource := historyDiagnosticSourcesByType(t, diagnostics)["ip"]
	if ipSource["provider"] != "rich" || ipSource["address"] != "上海市黄浦区人民大道 200 号" ||
		ipSource["detail"] != "人民广场" || ipSource["street"] != "人民大道" {
		t.Fatalf("richer same-level package was replaced by a sparse package: %#v", ipSource)
	}
}

func TestMergeLocationHistoryIdentityFramingCannotCollideOnDelimiterText(t *testing.T) {
	base := time.Date(2026, 7, 16, 12, 0, 0, 0, time.UTC)
	rows := []models.Location{
		testLocation(1, 11, "family-a", 0, base),
		testLocation(2, 11, "family-a", 5, base.Add(time.Minute)),
	}
	rows[0].AddressDiagnostics = sql.NullString{Valid: true, String: `{"sources":[{
		"type":"ip","ip":"x|source=y","address":"First package"
	}]}`}
	rows[1].AddressDiagnostics = sql.NullString{Valid: true, String: `{"sources":[{
		"type":"ip","ip":"x","source":"y","address":"Second package"
	}]}`}

	got := MergeLocationHistory(rows, 25)
	ipSources := historyDiagnosticSourcesOfType(t, decodeHistoryDiagnostics(t, got[0].AddressDiagnostics), "ip")
	if len(ipSources) != 2 {
		t.Fatalf("delimiter-bearing identities collided: %#v", ipSources)
	}
	_ = historyDiagnosticSourceByField(t, ipSources, "address", "First package")
	_ = historyDiagnosticSourceByField(t, ipSources, "address", "Second package")
}

func TestDiagnosticPackageRankingDoesNotTreatZeroCoordinateAsEvidence(t *testing.T) {
	usable := map[string]any{"latitude": 22.5431, "longitude": 114.0579}
	zero := map[string]any{"latitude": float64(0), "longitude": float64(0), "accuracy": float64(1)}
	if !diagnosticPackageBetter(usable, zero) || diagnosticPackageBetter(zero, usable) {
		t.Fatalf("zero coordinate affected precise package ranking: usable=%#v zero=%#v", usable, zero)
	}
}

func TestMergeLocationHistoryMergesEvidenceByIdentityWithoutMixingPackages(t *testing.T) {
	base := time.Date(2026, 7, 16, 12, 0, 0, 0, time.UTC)
	rows := []models.Location{
		testLocation(1, 11, "family-a", 0, base),
		testLocation(2, 11, "family-a", 5, base.Add(time.Minute)),
	}
	rows[0].AddressDiagnostics = sql.NullString{Valid: true, String: `{
		"sources":[
			{"type":"ip","source":"server","provider":"coarse-provider","ip":"198.51.100.8","city":"Shanghai","variants":[
				{"source":"ipgeo","provider":"coarse-variant","ip":"198.51.100.8","city":"Shanghai"},
				{"source":"backup-ipgeo","provider":"backup-provider","ip":"198.51.100.9","address":"Backup address"}
			]},
			{"type":"webrtc","source":"stun-a","provider":"STUN A","ip":"203.0.113.7","stun_server":"stun-a.example:3478","address":"STUN A address"}
		]
	}`}
	rows[1].AddressDiagnostics = sql.NullString{Valid: true, String: `{
		"sources":[
			{"type":"ip","source":"server","provider":"precise-provider","server_ip":"198.51.100.8","ipv4":"198.51.100.8","address":"Shanghai exact address","detail":"Building 8","variants":[
				{"source":"ipgeo","provider":"precise-variant","server_ip":"198.51.100.8","address":"Variant exact address","detail":"Room 9","accuracy":8},
				{"source":"third-ipgeo","provider":"third-provider","ipv4":"198.51.100.10","address":"Third address"}
			]},
			{"type":"webrtc","source":"stun-b","provider":"STUN B","ip":"203.0.113.7","stun_server":"stun-b.example:3478","address":"STUN B address"}
		]
	}`}

	got := MergeLocationHistory(rows, 25)
	if len(got) != 1 {
		t.Fatalf("merged stay count = %d, want 1", len(got))
	}
	diagnostics := decodeHistoryDiagnostics(t, got[0].AddressDiagnostics)
	ipSources := historyDiagnosticSourcesOfType(t, diagnostics, "ip")
	if len(ipSources) != 1 {
		t.Fatalf("same IP/source alias was not deduplicated: %#v", ipSources)
	}
	ipSource := ipSources[0]
	if ipSource["provider"] != "precise-provider" || ipSource["server_ip"] != "198.51.100.8" ||
		ipSource["address"] != "Shanghai exact address" || ipSource["detail"] != "Building 8" {
		t.Fatalf("selected IP source is not one coherent precise package: %#v", ipSource)
	}
	variants, ok := ipSource["variants"].([]any)
	if !ok || len(variants) != 3 {
		t.Fatalf("merged IP variants = %#v, want three identities", ipSource["variants"])
	}
	variantMaps := make([]map[string]any, 0, len(variants))
	for _, rawVariant := range variants {
		variantMaps = append(variantMaps, rawVariant.(map[string]any))
	}
	preciseVariant := historyDiagnosticSourceByField(t, variantMaps, "server_ip", "198.51.100.8")
	if preciseVariant["provider"] != "precise-variant" || preciseVariant["detail"] != "Room 9" || preciseVariant["accuracy"] != float64(8) {
		t.Fatalf("same-identity variant was mixed instead of replaced whole: %#v", preciseVariant)
	}
	webrtcSources := historyDiagnosticSourcesOfType(t, diagnostics, "webrtc")
	if len(webrtcSources) != 2 {
		t.Fatalf("distinct STUN identities were collapsed: %#v", webrtcSources)
	}
	_ = historyDiagnosticSourceByField(t, webrtcSources, "stun_server", "stun-a.example:3478")
	_ = historyDiagnosticSourceByField(t, webrtcSources, "stun_server", "stun-b.example:3478")
}

func TestMergeLocationHistoryUsesGPSCoordinateSystemBeforePreferredAddressSystem(t *testing.T) {
	base := time.Date(2026, 7, 16, 12, 0, 0, 0, time.UTC)
	rows := []models.Location{
		testLocation(1, 11, "family-a", 0, base),
		testLocation(2, 11, "family-a", 5, base.Add(time.Minute)),
		testLocation(3, 11, "family-a", 10, base.Add(2*time.Minute)),
	}
	rows[0].LocationMeta = sql.NullString{}
	rows[0].AddressDiagnostics = sql.NullString{String: `{"preferred_coordinate_system":"gcj02","sources":[{"type":"gps","coordinate_system":"wgs84"}]}`, Valid: true}
	rows[2].LocationMeta = sql.NullString{}
	rows[2].AddressDiagnostics = sql.NullString{String: `{"preferred_coordinate_system":"wgs84","sources":[{"type":"gps","coordinate_system":"gcj02"}]}`, Valid: true}

	got := MergeLocationHistory(rows, 25)
	if len(got) != 2 {
		t.Fatalf("len(MergeLocationHistory()) = %d, want GPS wgs84 pair merged and GPS gcj02 split", len(got))
	}
	if got[0].ID != 3 || got[0].ReportCount != 1 || got[1].ID != 2 || got[1].ReportCount != 2 {
		t.Fatalf("coordinate-system stays = ids/counts [%d/%d %d/%d]", got[0].ID, got[0].ReportCount, got[1].ID, got[1].ReportCount)
	}
}

func TestMergeLocationHistoryDoesNotTreatAddressPreferredSystemAsRawGPSSystem(t *testing.T) {
	base := time.Date(2026, 7, 16, 12, 0, 0, 0, time.UTC)
	rows := []models.Location{
		testLocation(1, 11, "family-a", 0, base),
		testLocation(2, 11, "family-a", 5, base.Add(time.Minute)),
	}
	rows[0].LocationMeta = sql.NullString{}
	rows[1].LocationMeta = sql.NullString{}
	rows[1].AddressDiagnostics = sql.NullString{
		String: `{"preferred_coordinate_system":"gcj02","sources":[{"type":"ip","coordinate_system":"gcj02"}]}`,
		Valid:  true,
	}

	got := MergeLocationHistory(rows, 25)
	if len(got) != 1 || got[0].ReportCount != 2 {
		t.Fatalf("address-only preferred coordinate system split raw GPS stay: %#v", got)
	}
}

func TestMergeLocationHistoryUsesIDAsAscendingTieBreaker(t *testing.T) {
	createdAt := time.Date(2026, 7, 16, 12, 0, 0, 0, time.UTC)
	rows := []models.Location{
		testLocation(2, 11, "family-a", 5, createdAt),
		testLocation(1, 11, "family-a", 0, createdAt),
	}

	got := MergeLocationHistory(rows, 25)
	if len(got) != 1 || got[0].ID != 2 || got[0].ReportCount != 2 {
		t.Fatalf("same-time merged result = %#v, want latest id 2 with count 2", got)
	}
}

func TestLocationPayloadIncludesStayContractAndKeepsCreatedAtLatest(t *testing.T) {
	first := time.Date(2026, 7, 16, 12, 0, 0, 0, time.Local)
	last := first.Add(3 * time.Minute)
	location := testLocation(9, 11, "family-a", 0, last)
	location.FirstReportedAt = first
	location.LastReportedAt = last
	location.StayDurationSeconds = 180
	location.ReportCount = 4

	payload := LocationPayload(location, 600)
	if payload["created_at"] != FormatDatetime(last) {
		t.Fatalf("created_at = %#v, want latest %q", payload["created_at"], FormatDatetime(last))
	}
	if payload["first_reported_at"] != FormatDatetime(first) || payload["last_reported_at"] != FormatDatetime(last) {
		t.Fatalf("reported range = %#v to %#v", payload["first_reported_at"], payload["last_reported_at"])
	}
	if payload["stay_duration_seconds"] != int64(180) || payload["report_count"] != 4 {
		t.Fatalf("stay payload duration/count = %#v/%#v", payload["stay_duration_seconds"], payload["report_count"])
	}
}

func TestHistoryPaginationAndPerUserMapLimitRunOnMergedRows(t *testing.T) {
	base := time.Date(2026, 7, 16, 12, 0, 0, 0, time.UTC)
	raw := []models.Location{
		testLocation(1, 11, "family-a", 0, base),
		testLocation(2, 11, "family-a", 5, base.Add(time.Minute)),
		testLocation(3, 12, "family-a", 0, base.Add(2*time.Minute)),
		testLocation(4, 11, "family-a", 100, base.Add(3*time.Minute)),
		testLocation(5, 12, "family-a", 100, base.Add(4*time.Minute)),
		testLocation(6, 11, "family-a", 200, base.Add(5*time.Minute)),
	}
	merged := MergeLocationHistory(raw, 25)
	if len(merged) != 5 {
		t.Fatalf("merged total = %d, want 5", len(merged))
	}

	pageRows, page, totalPages := PaginateLocationHistory(merged, 2, 2)
	if page != 2 || totalPages != 3 || len(pageRows) != 2 || pageRows[0].ID != 4 || pageRows[1].ID != 3 {
		t.Fatalf("page = %d/%d rows=%v", page, totalPages, locationIDs(pageRows))
	}

	mapRows := LimitLocationHistoryPerUser(merged, 2)
	if got := locationIDs(mapRows); !equalInt64s(got, []int64{6, 5, 4, 3}) {
		t.Fatalf("map ids = %v, want [6 5 4 3]", got)
	}

	lastRows, clampedPage, _ := PaginateLocationHistory(merged, 99, 2)
	if clampedPage != 3 || !equalInt64s(locationIDs(lastRows), []int64{2}) {
		t.Fatalf("clamped page=%d rows=%v, want page 3 rows [2]", clampedPage, locationIDs(lastRows))
	}
}

func testLocation(id, userID int64, groupName string, northMeters float64, createdAt time.Time) models.Location {
	const baseLatitude = 30.0
	return models.Location{
		ID:           id,
		UserID:       userID,
		Username:     "user",
		GroupName:    groupName,
		Latitude:     baseLatitude + northMeters/earthRadiusMeters*180/math.Pi,
		Longitude:    120,
		LocationMeta: sql.NullString{String: `{"coordinate_system":"wgs84"}`, Valid: true},
		CreatedAt:    createdAt,
		UpdatedAt:    createdAt,
	}
}

func locationIDs(locations []models.Location) []int64 {
	ids := make([]int64, 0, len(locations))
	for _, location := range locations {
		ids = append(ids, location.ID)
	}
	return ids
}

func equalInt64s(left, right []int64) bool {
	if len(left) != len(right) {
		return false
	}
	for index := range left {
		if left[index] != right[index] {
			return false
		}
	}
	return true
}

func decodeHistoryDiagnostics(t *testing.T, value sql.NullString) map[string]any {
	t.Helper()
	if !value.Valid {
		t.Fatal("address diagnostics are NULL")
	}
	var diagnostics map[string]any
	if err := json.Unmarshal([]byte(value.String), &diagnostics); err != nil {
		t.Fatalf("decode address diagnostics: %v", err)
	}
	return diagnostics
}

func historyDiagnosticSourcesByType(t *testing.T, diagnostics map[string]any) map[string]map[string]any {
	t.Helper()
	rawSources, ok := diagnostics["sources"].([]any)
	if !ok {
		t.Fatalf("diagnostic sources = %#v, want array", diagnostics["sources"])
	}
	sources := make(map[string]map[string]any, len(rawSources))
	for _, rawSource := range rawSources {
		source, ok := rawSource.(map[string]any)
		if !ok {
			t.Fatalf("diagnostic source = %#v, want object", rawSource)
		}
		sourceType, _ := source["type"].(string)
		if _, duplicate := sources[sourceType]; duplicate {
			t.Fatalf("duplicate diagnostic source type %q: %#v", sourceType, rawSources)
		}
		sources[sourceType] = source
	}
	return sources
}

func historyDiagnosticSourcesOfType(t *testing.T, diagnostics map[string]any, sourceType string) []map[string]any {
	t.Helper()
	rawSources, ok := diagnostics["sources"].([]any)
	if !ok {
		t.Fatalf("diagnostic sources = %#v, want array", diagnostics["sources"])
	}
	sources := make([]map[string]any, 0, len(rawSources))
	for _, rawSource := range rawSources {
		source, ok := rawSource.(map[string]any)
		if !ok {
			t.Fatalf("diagnostic source = %#v, want object", rawSource)
		}
		if source["type"] == sourceType {
			sources = append(sources, source)
		}
	}
	return sources
}

func historyDiagnosticSourceByField(t *testing.T, sources []map[string]any, field string, want any) map[string]any {
	t.Helper()
	for _, source := range sources {
		if source[field] == want {
			return source
		}
	}
	t.Fatalf("no diagnostic source has %s=%#v: %#v", field, want, sources)
	return nil
}
