package handlers

import (
	"context"
	"errors"
	"net/http"
	"testing"
	"time"

	"familylocation/location-v3/internal/config"
	"familylocation/location-v3/internal/httpx"
)

type diagnosticRateLimiterStub struct {
	allowed   bool
	allowance []bool
	err       error
	bucket    string
	identity  string
	maxHits   int
	window    time.Duration
	calls     []diagnosticRateLimitCall
}

type diagnosticRateLimitCall struct {
	bucket   string
	identity string
	maxHits  int
	window   time.Duration
}

type diagnosticCountingRateLimiter struct {
	counts map[string]int
}

func (limits *diagnosticCountingRateLimiter) Hit(_ context.Context, bucket string, identity string, maxHits int, _ time.Duration) (bool, error) {
	if limits.counts == nil {
		limits.counts = make(map[string]int)
	}
	key := bucket + "|" + identity
	limits.counts[key]++
	return limits.counts[key] <= maxHits, nil
}

func (stub *diagnosticRateLimiterStub) Hit(_ context.Context, bucket string, identity string, maxHits int, window time.Duration) (bool, error) {
	stub.bucket = bucket
	stub.identity = identity
	stub.maxHits = maxHits
	stub.window = window
	stub.calls = append(stub.calls, diagnosticRateLimitCall{bucket: bucket, identity: identity, maxHits: maxHits, window: window})
	allowed := stub.allowed
	if len(stub.allowance) > 0 {
		allowed = stub.allowance[0]
		stub.allowance = stub.allowance[1:]
	}
	return allowed, stub.err
}

func TestDiagnosticProviderQuotaUsesAuthenticatedUserIdentity(t *testing.T) {
	limits := &diagnosticRateLimiterStub{allowed: true}
	handler := DiagnosticHandler{rates: limits}
	if err := handler.consumeProviderQuota(context.Background(), 42); err != nil {
		t.Fatalf("consumeProviderQuota() error = %v", err)
	}
	if limits.bucket != diagnosticProviderQuotaBucket || limits.identity != "42" {
		t.Fatalf("quota identity = %q/%q, want %q/42", limits.bucket, limits.identity, diagnosticProviderQuotaBucket)
	}
	if limits.maxHits != diagnosticProviderQuotaMaxHits || limits.window != diagnosticProviderQuotaWindow {
		t.Fatalf("quota = %d/%s, want %d/%s", limits.maxHits, limits.window, diagnosticProviderQuotaMaxHits, diagnosticProviderQuotaWindow)
	}
}

func TestDiagnosticProviderQuotaRejectsExhaustedUser(t *testing.T) {
	handler := DiagnosticHandler{rates: &diagnosticRateLimiterStub{allowed: false}}
	err := handler.consumeProviderQuota(context.Background(), 42)
	var apiErr httpx.APIError
	if !errors.As(err, &apiErr) || apiErr.Status != http.StatusTooManyRequests {
		t.Fatalf("consumeProviderQuota() error = %#v, want HTTP 429", err)
	}
}

func TestValidateIPGeoProviderReportsSafeAvailabilityReason(t *testing.T) {
	cfg := config.ExternalConfig{
		IP2LocationKey: "configured",
		IPDataKey:      "configured",
		IPRegistryKey:  "configured",
	}
	if provider, err := validateIPGeoProvider(cfg, " IPDATA "); err != nil || provider != "ipdata" {
		t.Fatalf("validate configured provider = %q, %v; want ipdata", provider, err)
	}
	for _, provider := range []string{"ip-api", "uapis", "baidu", "iping", "xxapi"} {
		if normalized, err := validateIPGeoProvider(cfg, provider); err != nil || normalized != provider {
			t.Fatalf("validate public provider %q = %q, %v", provider, normalized, err)
		}
	}

	tests := []struct {
		name     string
		cfg      config.ExternalConfig
		provider string
		code     string
	}{
		{name: "missing key", cfg: config.ExternalConfig{}, provider: "ip2location", code: "not_configured"},
		{name: "unsupported", cfg: cfg, provider: "unknown", code: "unsupported_provider"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			_, err := validateIPGeoProvider(test.cfg, test.provider)
			var apiErr httpx.APIError
			if !errors.As(err, &apiErr) || apiErr.Code != test.code {
				t.Fatalf("validateIPGeoProvider() error = %#v, want code %q", err, test.code)
			}
		})
	}
}

func TestDiagnosticEndpointQuotaAllowsSupportedAndroidCadence(t *testing.T) {
	limits := &diagnosticCountingRateLimiter{}
	handler := DiagnosticHandler{rates: limits}
	const supportedCalls = 9 * 60
	if diagnosticProviderQuotaMaxHits <= supportedCalls {
		t.Fatalf("endpoint quota = %d, must exceed supported %d calls/hour", diagnosticProviderQuotaMaxHits, supportedCalls)
	}
	for call := 0; call < supportedCalls; call++ {
		if err := handler.consumeProviderQuota(context.Background(), 42); err != nil {
			t.Fatalf("supported Android call %d rejected: %v", call+1, err)
		}
	}
	for call := supportedCalls; call < diagnosticProviderQuotaMaxHits; call++ {
		if err := handler.consumeProviderQuota(context.Background(), 42); err != nil {
			t.Fatalf("endpoint margin call %d rejected: %v", call+1, err)
		}
	}
	err := handler.consumeProviderQuota(context.Background(), 42)
	var apiErr httpx.APIError
	if !errors.As(err, &apiErr) || apiErr.Status != http.StatusTooManyRequests {
		t.Fatalf("overflow endpoint call error = %#v, want HTTP 429", err)
	}
}

func testIPGeoQuota() config.IPGeoProviderQuota {
	return config.IPGeoProviderQuota{
		MaxRequests:     100,
		ReserveRequests: 20,
		UserMaxMisses:   10,
		Window:          24 * time.Hour,
	}
}

func testDiagnosticHandler(limits diagnosticRateLimiter) DiagnosticHandler {
	return DiagnosticHandler{
		cfg: config.Config{External: config.ExternalConfig{
			IPGeoProviderQuotas: map[string]config.IPGeoProviderQuota{"ipdata": testIPGeoQuota()},
		}},
		rates: limits,
	}
}

func TestDiagnosticProviderMissQuotaIsPerUserAndProvider(t *testing.T) {
	limits := &diagnosticRateLimiterStub{allowance: []bool{true, true, false}}
	handler := testDiagnosticHandler(limits)
	if err := handler.consumeProviderMissQuotas(context.Background(), 101, "ipdata"); err != nil {
		t.Fatalf("first miss error = %v", err)
	}
	err := handler.consumeProviderMissQuotas(context.Background(), 101, "ipdata")
	var apiErr httpx.APIError
	if !errors.As(err, &apiErr) || apiErr.Status != http.StatusTooManyRequests {
		t.Fatalf("second miss error = %#v, want HTTP 429", err)
	}
	if len(limits.calls) != 3 {
		t.Fatalf("quota calls = %d, want user/global then user rejection", len(limits.calls))
	}
	if limits.calls[0].bucket != diagnosticProviderUserMissQuotaBucket || limits.calls[0].identity != "ipdata|101" || limits.calls[0].maxHits != 10 {
		t.Fatalf("user miss quota call = %#v", limits.calls[0])
	}
	if limits.calls[1].bucket != diagnosticProviderSharedQuotaBucket || limits.calls[1].identity != "ipdata" || limits.calls[1].maxHits != 80 {
		t.Fatalf("global provider quota call = %#v", limits.calls[1])
	}
}

func TestDiagnosticSharedProviderQuotaIsSharedAcrossUsers(t *testing.T) {
	limits := &diagnosticRateLimiterStub{allowance: []bool{true, true, true, false}}
	handler := testDiagnosticHandler(limits)

	for index, userID := range []int64{101, 202} {
		err := handler.consumeProviderMissQuotas(context.Background(), userID, "ipdata")
		if index == 0 && err != nil {
			t.Fatalf("first user %d shared quota error = %v", userID, err)
		}
		if index == 1 {
			var apiErr httpx.APIError
			if !errors.As(err, &apiErr) || apiErr.Status != http.StatusTooManyRequests {
				t.Fatalf("second user %d shared quota error = %#v, want HTTP 429", userID, err)
			}
		}
	}

	if len(limits.calls) != 4 {
		t.Fatalf("shared quota calls = %d, want 4", len(limits.calls))
	}
	for _, call := range []diagnosticRateLimitCall{limits.calls[1], limits.calls[3]} {
		if call.bucket != diagnosticProviderSharedQuotaBucket || call.identity != "ipdata" {
			t.Fatalf("shared quota identity = %q/%q, want %q/ipdata", call.bucket, call.identity, diagnosticProviderSharedQuotaBucket)
		}
		if call.maxHits != 80 || call.window != 24*time.Hour {
			t.Fatalf("shared quota = %d/%s, want 80/24h", call.maxHits, call.window)
		}
	}
}

func TestDiagnosticProviderMissQuotaFailsClosedWithoutTrustedConfig(t *testing.T) {
	limits := &diagnosticRateLimiterStub{allowed: true}
	handler := DiagnosticHandler{rates: limits}
	err := handler.consumeProviderMissQuotas(context.Background(), 101, "ipdata")
	var apiErr httpx.APIError
	if !errors.As(err, &apiErr) || apiErr.Status != http.StatusServiceUnavailable {
		t.Fatalf("missing provider quota error = %#v, want HTTP 503", err)
	}
	if apiErr.Code != "quota_unconfigured" {
		t.Fatalf("missing provider quota code = %q, want quota_unconfigured", apiErr.Code)
	}
	if len(limits.calls) != 0 {
		t.Fatalf("missing provider quota made %d rate-limit calls", len(limits.calls))
	}
}
