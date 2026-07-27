package handlers

import (
	"testing"
	"time"

	"familylocation/location-v3/internal/config"
)

func trustedLocationConfig() config.LocationConfig {
	return config.LocationConfig{
		MaxAccuracyMeters:        100,
		MaxLocationAgeSeconds:    60,
		MaxLocationFutureSeconds: 15,
		JumpAllowanceMeters:      100,
		MaxStationaryJumpMeters:  200,
		MaxStationarySpeedMPS:    2,
		MaxReasonableTravelMPS:   120,
	}
}

func TestValidateReportedLocationFix(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	base := map[string]any{
		"location_provider":          "gps",
		"location_time":              now.Add(-5 * time.Second).UnixMilli(),
		"location_coordinate_system": "wgs84",
		"location_mock_provider":     false,
		"accuracy":                   10.0,
	}
	tests := map[string]func(map[string]any){
		"fresh GPS":         func(map[string]any) {},
		"network provider":  func(data map[string]any) { data["location_provider"] = "network" },
		"stale GPS":         func(data map[string]any) { data["location_time"] = now.Add(-61 * time.Second).UnixMilli() },
		"future GPS":        func(data map[string]any) { data["location_time"] = now.Add(16 * time.Second).UnixMilli() },
		"mock GPS":          func(data map[string]any) { data["location_mock_provider"] = true },
		"wrong coordinates": func(data map[string]any) { data["location_coordinate_system"] = "gcj02" },
		"poor accuracy":     func(data map[string]any) { data["accuracy"] = 101.0 },
	}
	for name, mutate := range tests {
		t.Run(name, func(t *testing.T) {
			data := make(map[string]any, len(base))
			for key, value := range base {
				data[key] = value
			}
			mutate(data)
			err := validateReportedLocationFix(data, now, trustedLocationConfig())
			if name == "fresh GPS" && err != nil {
				t.Fatalf("fresh GPS rejected: %v", err)
			}
			if name != "fresh GPS" && err == nil {
				t.Fatalf("%s was accepted", name)
			}
		})
	}
}

func TestAssessLocationJumpRejectsStationaryJump(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	cfg := trustedLocationConfig()
	previousAt := now.Add(-82 * time.Second)
	if meta := assessLocationJump(35, -120, 10, previousAt, 35.0081, -120, 10, nil, now, cfg); meta == nil {
		t.Fatal("large jump without reported movement was accepted")
	}
	stationarySpeed := 0.5
	if meta := assessLocationJump(35, -120, 10, previousAt, 35.0081, -120, 10, &stationarySpeed, now, cfg); meta == nil {
		t.Fatal("large jump with stationary speed was accepted")
	}
	drivingSpeed := 12.0
	if meta := assessLocationJump(35, -120, 10, previousAt, 35.0081, -120, 10, &drivingSpeed, now, cfg); meta != nil {
		t.Fatalf("plausible moving fix was rejected: %#v", meta)
	}
	if meta := assessLocationJump(35, -120, 10, now.Add(-time.Second), 36, -120, 10, &drivingSpeed, now, cfg); meta == nil {
		t.Fatal("physically impossible jump was accepted")
	}
}

func TestDiagnosticsPlaceMismatchIncludesIPWebRTCConflict(t *testing.T) {
	conflict := []map[string]any{
		{"type": "ip", "country": "美国", "region": "密苏里州"},
		{"type": "webrtc", "country": "中国", "region": "广东省"},
	}
	if !diagnosticsPlaceMismatch(conflict) {
		t.Fatal("IP and WebRTC country conflict was not marked as mismatch")
	}
	consistent := []map[string]any{
		{"type": "ip", "country": "中国", "region": "广东省"},
		{"type": "webrtc", "country": "中国", "region": "广东"},
	}
	if diagnosticsPlaceMismatch(consistent) {
		t.Fatal("consistent IP and WebRTC locations were marked as mismatch")
	}
}
