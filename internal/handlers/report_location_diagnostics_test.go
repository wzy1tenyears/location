package handlers

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"testing"

	"familylocation/location-v3/internal/httpx"
)

func TestSanitizeAddressDiagnosticsKeepsSafeProviderAttemptSummary(t *testing.T) {
	diagnostics := sanitizeAddressDiagnostics(map[string]any{
		"sources": []any{map[string]any{
			"type": "ip",
			"provider_attempts": []any{
				map[string]any{"provider": "ipdata", "status": "success", "precision": "district", "ip": "203.0.113.7", "raw_response": "secret"},
				map[string]any{"provider": "ipregistry", "status": "failed", "precision": "invalid", "reason": "not_configured", "raw_error": "secret"},
			},
		}},
	})
	source := diagnostics["sources"].([]map[string]any)[0]
	attempts := source["provider_attempts"].([]map[string]any)
	if len(attempts) != 2 || attempts[0]["precision"] != "district" || attempts[1]["precision"] != "none" {
		t.Fatalf("unexpected provider attempts: %#v", attempts)
	}
	if _, exists := attempts[0]["raw_response"]; exists {
		t.Fatal("provider attempt leaked raw response")
	}
	if attempts[0]["ip"] != "203.0.113.7" {
		t.Fatalf("safe provider observed IP = %#v", attempts[0]["ip"])
	}
	if attempts[1]["reason"] != "not_configured" {
		t.Fatalf("safe provider failure reason = %#v, want not_configured", attempts[1]["reason"])
	}
	if _, exists := attempts[1]["raw_error"]; exists {
		t.Fatal("provider attempt leaked raw error")
	}
}

func TestSanitizeAddressDiagnosticsKeepsSafeWebRTCFailure(t *testing.T) {
	diagnostics := sanitizeAddressDiagnostics(map[string]any{
		"sources": []any{map[string]any{
			"type": "webrtc", "probe_status": "failed", "failure_reason": "probe_timeout",
			"raw_error": "renderer details",
		}},
	})
	source := diagnostics["sources"].([]map[string]any)[0]
	if source["probe_status"] != "failed" || source["failure_reason"] != "probe_timeout" {
		t.Fatalf("sanitized WebRTC failure = %#v", source)
	}
	if _, exists := source["raw_error"]; exists {
		t.Fatal("WebRTC source leaked raw error")
	}
}

func TestSanitizeAddressDiagnosticsPreservesPreciseSourceFields(t *testing.T) {
	diagnostics := sanitizeAddressDiagnostics(map[string]any{
		"preferred_country":     "中国",
		"preferred_region":      "广东省",
		"preferred_detail":      "科技园 A 座",
		"preferred_district":    "南山区",
		"preferred_street":      "粤海街道",
		"preferred_postal_code": "518000",
		"sources": []any{map[string]any{
			"type":        "ip",
			"address":     "广东省深圳市南山区粤海街道科技园 A 座",
			"detail":      "科技园 A 座",
			"poi":         "科技园",
			"district":    "南山区",
			"street":      "粤海街道",
			"postal_code": "518000",
		}},
	})

	for key, want := range map[string]string{
		"preferred_country":     "中国",
		"preferred_region":      "广东省",
		"preferred_detail":      "科技园 A 座",
		"preferred_district":    "南山区",
		"preferred_street":      "粤海街道",
		"preferred_postal_code": "518000",
	} {
		if got := diagnostics[key]; got != want {
			t.Fatalf("diagnostics[%q] = %#v, want %q", key, got, want)
		}
	}
	sources := diagnostics["sources"].([]map[string]any)
	for key, want := range map[string]string{
		"address": "广东省深圳市南山区粤海街道科技园 A 座", "detail": "科技园 A 座", "poi": "科技园",
		"district": "南山区", "street": "粤海街道", "postal_code": "518000",
	} {
		if got := sources[0][key]; got != want {
			t.Fatalf("source[%q] = %#v, want %q", key, got, want)
		}
	}
}

func TestSanitizeAddressDiagnosticsPreservesWebRTCCandidateLocation(t *testing.T) {
	diagnostics := sanitizeAddressDiagnostics(map[string]any{
		"sources": []any{map[string]any{
			"type": "webrtc",
			"candidates": []any{map[string]any{
				"ip": "203.0.113.7", "latitude": 22.5431, "longitude": 114.0579,
				"coordinate_system": "wgs84", "address": "深圳市福田区", "detail": "市民中心 A 区",
				"poi": "市民中心", "district": "福田区", "street": "福中路", "postal_code": "518000",
			}},
		}},
	})

	source := diagnostics["sources"].([]map[string]any)[0]
	candidate := source["candidates"].([]map[string]any)[0]
	for key, want := range map[string]any{
		"latitude": 22.5431, "longitude": 114.0579, "coordinate_system": "wgs84",
		"address": "深圳市福田区", "detail": "市民中心 A 区", "poi": "市民中心",
		"district": "福田区", "street": "福中路", "postal_code": "518000",
	} {
		if got := candidate[key]; got != want {
			t.Fatalf("candidate[%q] = %#v, want %#v", key, got, want)
		}
	}
}

func TestSanitizeAddressDiagnosticsPreservesIdentityAliasesAndAccuracy(t *testing.T) {
	diagnostics := sanitizeAddressDiagnostics(map[string]any{
		"sources": []any{map[string]any{
			"type": "ip", "ip": "198.51.100.8", "server_ip": "198.51.100.8",
			"ipv4": "198.51.100.8", "ipv6": "2001:db8::8", "accuracy": 12.5,
			"address": "上海市黄浦区人民大道 200 号", "detail": "人民广场",
			"variants": []any{map[string]any{
				"ip": "198.51.100.9", "server_ip": "198.51.100.9", "ipv4": "198.51.100.9",
				"ipv6": "2001:db8::9", "accuracy": 7, "address": "精确 IP 地址", "detail": "A 座 9 层",
			}},
			"candidates": []any{map[string]any{
				"ip": "203.0.113.7", "server_ip": "203.0.113.7", "ipv4": "203.0.113.7",
				"ipv6": "2001:db8::7", "accuracy": 4, "address": "精确 WebRTC 地址", "detail": "B 座 4 层",
			}},
		}},
	})

	source := diagnostics["sources"].([]map[string]any)[0]
	for key, want := range map[string]any{
		"ip": "198.51.100.8", "server_ip": "198.51.100.8", "ipv4": "198.51.100.8",
		"ipv6": "2001:db8::8", "accuracy": 12.5, "address": "上海市黄浦区人民大道 200 号", "detail": "人民广场",
	} {
		if source[key] != want {
			t.Fatalf("source[%q] = %#v, want %#v", key, source[key], want)
		}
	}
	for _, nestedKey := range []string{"variants", "candidates"} {
		nested := source[nestedKey].([]map[string]any)[0]
		for _, identityKey := range []string{"ip", "server_ip", "ipv4", "ipv6", "accuracy", "address", "detail"} {
			if _, exists := nested[identityKey]; !exists {
				t.Fatalf("%s lost %q: %#v", nestedKey, identityKey, nested)
			}
		}
	}
}

func TestSanitizeAddressDiagnosticsDoesNotDiscardBestCandidateAfterLegacyCap(t *testing.T) {
	candidates := make([]any, 0, 14)
	for index := 0; index < 13; index++ {
		candidates = append(candidates, map[string]any{
			"ip": "203.0.113.7", "stun_server": "stun.example:3478", "provider": "coarse", "city": "深圳市",
		})
	}
	candidates = append(candidates, map[string]any{
		"ip": "203.0.113.7", "stun_server": "stun.example:3478", "provider": "precise",
		"address": "深圳市南山区科技园 A 座", "detail": "9 层 901", "accuracy": 3,
	})
	diagnostics := sanitizeAddressDiagnostics(map[string]any{
		"sources": []any{map[string]any{"type": "webrtc", "candidates": candidates}},
	})

	sanitizedCandidates := diagnostics["sources"].([]map[string]any)[0]["candidates"].([]map[string]any)
	if len(sanitizedCandidates) != 14 {
		t.Fatalf("sanitizer kept %d candidates, want all 14 before byte-aware compaction", len(sanitizedCandidates))
	}
	best := sanitizedCandidates[bestDiagnosticEvidenceIndex(sanitizedCandidates)]
	if best["provider"] != "precise" || best["detail"] != "9 层 901" || best["accuracy"] != float64(3) {
		t.Fatalf("best candidate beyond the old cap was not retained: %#v", best)
	}
}

func TestSanitizeAddressDiagnosticsPreservesIPVariantPreciseLocation(t *testing.T) {
	diagnostics := sanitizeAddressDiagnostics(map[string]any{
		"sources": []any{map[string]any{
			"type": "ip",
			"variants": []any{map[string]any{
				"ip": "198.51.100.8", "latitude": 31.2304, "longitude": 121.4737,
				"coordinate_system": "gcj02", "address": "上海市黄浦区人民大道 200 号",
				"detail": "人民广场", "poi": "上海博物馆", "district": "黄浦区",
				"street": "人民大道", "postal_code": "200003",
			}},
		}},
	})

	variant := diagnostics["sources"].([]map[string]any)[0]["variants"].([]map[string]any)[0]
	for key, want := range map[string]any{
		"latitude": 31.2304, "longitude": 121.4737, "coordinate_system": "gcj02",
		"address": "上海市黄浦区人民大道 200 号", "detail": "人民广场", "poi": "上海博物馆",
		"district": "黄浦区", "street": "人民大道", "postal_code": "200003",
	} {
		if got := variant[key]; got != want {
			t.Fatalf("variant[%q] = %#v, want %#v", key, got, want)
		}
	}
}

func TestMarshalAddressDiagnosticsPrunesNestedEvidenceAndKeepsValidJSON(t *testing.T) {
	candidates := make([]any, 0, 12)
	variants := make([]any, 0, 12)
	for index := 0; index < 12; index++ {
		candidates = append(candidates, map[string]any{
			"ip":      "203.0.113.7",
			"address": strings.Repeat("候选地址", 150),
		})
		variants = append(variants, map[string]any{
			"ip":      "198.51.100.8",
			"address": strings.Repeat("探测地址", 150),
		})
	}
	diagnostics := sanitizeAddressDiagnostics(map[string]any{
		"preferred_address": "深圳市南山区科技园",
		"sources": []any{
			map[string]any{
				"type": "ip", "address": "上海市黄浦区人民大道", "latitude": 31.2304, "longitude": 121.4737,
				"variants": variants,
			},
			map[string]any{
				"type": "webrtc", "address": "深圳市南山区科技园", "latitude": 22.5431, "longitude": 114.0579,
				"candidates": candidates,
			},
		},
	})
	unpruned, err := json.Marshal(diagnostics)
	if err != nil {
		t.Fatal(err)
	}
	const maxBytes = 3000
	if len(unpruned) <= maxBytes {
		t.Fatalf("test fixture is only %d bytes; want more than %d", len(unpruned), maxBytes)
	}

	payload, err := marshalAddressDiagnostics(diagnostics, maxBytes)
	if err != nil {
		t.Fatalf("marshalAddressDiagnostics() error = %v", err)
	}
	if len(payload) > maxBytes || !json.Valid([]byte(payload)) {
		t.Fatalf("bounded diagnostics are invalid or oversized: bytes=%d payload=%q", len(payload), payload)
	}
	var decoded map[string]any
	if err := json.Unmarshal([]byte(payload), &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded["preferred_address"] != "深圳市南山区科技园" {
		t.Fatalf("preferred address was pruned: %#v", decoded)
	}
	sources := decoded["sources"].([]any)
	remainingEvidence := 0
	for _, rawSource := range sources {
		source := rawSource.(map[string]any)
		remainingEvidence += len(diagnosticEvidenceMaps(source["variants"]))
		remainingEvidence += len(diagnosticEvidenceMaps(source["candidates"]))
	}
	if remainingEvidence >= 24 {
		t.Fatalf("oversized nested evidence was not reduced incrementally: %d items", remainingEvidence)
	}
	originalSources := diagnostics["sources"].([]map[string]any)
	if _, exists := originalSources[0]["variants"]; !exists {
		t.Fatal("bounded marshaling mutated the sanitized IP diagnostics")
	}
	if _, exists := originalSources[1]["candidates"]; !exists {
		t.Fatal("bounded marshaling mutated the sanitized diagnostics")
	}
}

func TestMarshalAddressDiagnosticsPromotesBestCoherentCandidate(t *testing.T) {
	diagnostics := sanitizeAddressDiagnostics(map[string]any{
		"preferred_address": "深圳市南山区科技园",
		"sources": []any{map[string]any{
			"type": "webrtc", "ip": "203.0.113.7", "server_ip": "203.0.113.7",
			"stun_server": "stun.example:3478", "provider": "parent-provider", "city": "深圳市", "accuracy": 80,
			"candidates": []any{
				map[string]any{"ip": "203.0.113.7", "server_ip": "203.0.113.7", "stun_server": "stun.example:3478", "provider": "coarse", "city": "深圳市", "accuracy": 50},
				map[string]any{"ip": "203.0.113.7", "server_ip": "203.0.113.7", "stun_server": "stun.example:3478", "provider": "precise", "address": "深圳市南山区科技园 A 座", "detail": "9 层 901", "accuracy": 3},
				map[string]any{"ip": "203.0.113.7", "server_ip": "203.0.113.7", "stun_server": "stun.example:3478", "provider": "verbose", "address": strings.Repeat("冗余地址", 150), "accuracy": 20},
			},
		}},
	})
	minimum := cloneAddressDiagnosticValue(diagnostics).(map[string]any)
	minimumSources := minimum["sources"].([]map[string]any)
	minimumSources[0] = promoteBestDiagnosticPackage(minimumSources[0])
	minimumJSON, err := json.Marshal(minimum)
	if err != nil {
		t.Fatal(err)
	}
	unboundedJSON, err := json.Marshal(diagnostics)
	if err != nil {
		t.Fatal(err)
	}
	if len(unboundedJSON) <= len(minimumJSON) {
		t.Fatalf("fixture does not require compaction: full=%d minimum=%d", len(unboundedJSON), len(minimumJSON))
	}

	payload, err := marshalAddressDiagnostics(diagnostics, len(minimumJSON))
	if err != nil {
		t.Fatalf("marshalAddressDiagnostics() error = %v", err)
	}
	var decoded map[string]any
	if err := json.Unmarshal([]byte(payload), &decoded); err != nil {
		t.Fatal(err)
	}
	source := decoded["sources"].([]any)[0].(map[string]any)
	if source["provider"] != "precise" || source["ip"] != "203.0.113.7" ||
		source["server_ip"] != "203.0.113.7" || source["stun_server"] != "stun.example:3478" ||
		source["address"] != "深圳市南山区科技园 A 座" || source["detail"] != "9 层 901" || source["accuracy"] != float64(3) {
		t.Fatalf("best candidate was not promoted as one coherent package: %#v", source)
	}
	if _, exists := source["candidates"]; exists {
		t.Fatalf("promoted source still contains redundant candidates: %#v", source)
	}
}

func TestDiagnosticCompactionRankingPrefersRicherSameLevelPackage(t *testing.T) {
	rich := map[string]any{
		"address": "上海市黄浦区人民大道 200 号",
		"detail":  "人民广场",
		"street":  "人民大道",
	}
	sparse := map[string]any{"detail": "新探测门牌"}
	if !handlerDiagnosticPackageBetter(rich, sparse) || handlerDiagnosticPackageBetter(sparse, rich) {
		t.Fatalf("same-level package ranking did not prefer richer evidence: rich=%#v sparse=%#v", rich, sparse)
	}
}

func TestMarshalAddressDiagnosticsRejectsRatherThanDroppingDistinctBestIdentity(t *testing.T) {
	diagnostics := sanitizeAddressDiagnostics(map[string]any{
		"preferred_address": "上海市",
		"sources": []any{map[string]any{
			"type": "ip", "ip": "198.51.100.8", "provider": "parent", "city": "上海市",
			"variants": []any{map[string]any{
				"ip": "198.51.100.9", "provider": "distinct-best", "address": strings.Repeat("精确地址", 150), "detail": "A 座 9 层",
			}},
		}},
	})
	fullJSON, err := json.Marshal(diagnostics)
	if err != nil {
		t.Fatal(err)
	}
	coarse := cloneAddressDiagnosticValue(diagnostics).(map[string]any)
	coarseSource := coarse["sources"].([]map[string]any)[0]
	delete(coarseSource, "variants")
	delete(coarseSource, "candidates")
	coarseJSON, err := json.Marshal(coarse)
	if err != nil {
		t.Fatal(err)
	}
	if len(fullJSON) <= len(coarseJSON)+1 {
		t.Fatalf("fixture does not distinguish full and coarse payloads: full=%d coarse=%d", len(fullJSON), len(coarseJSON))
	}
	maxBytes := len(coarseJSON) + (len(fullJSON)-len(coarseJSON))/2

	payload, err := marshalAddressDiagnostics(diagnostics, maxBytes)
	if payload != "" {
		t.Fatalf("coarser payload was silently stored: %s", payload)
	}
	var apiErr httpx.APIError
	if !errors.As(err, &apiErr) || apiErr.Status != http.StatusUnprocessableEntity {
		t.Fatalf("marshalAddressDiagnostics() error = %#v, want 422", err)
	}
}

func TestMarshalAddressDiagnosticsRejectsIrreducibleOversizePayload(t *testing.T) {
	diagnostics := sanitizeAddressDiagnostics(map[string]any{
		"preferred_address": strings.Repeat("精确地址", 150),
	})
	payload, err := marshalAddressDiagnostics(diagnostics, 64)
	if payload != "" {
		t.Fatalf("rejected payload = %q, want empty", payload)
	}
	var apiErr httpx.APIError
	if !errors.As(err, &apiErr) || apiErr.Status != http.StatusUnprocessableEntity {
		t.Fatalf("marshalAddressDiagnostics() error = %#v, want 422", err)
	}
	if apiErr.Message != "位置诊断数据过大，请缩减后重试。" {
		t.Fatalf("marshalAddressDiagnostics() message = %q", apiErr.Message)
	}
}
