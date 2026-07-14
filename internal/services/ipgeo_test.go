package services

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"familylocation/location-v3/internal/config"
)

type ipGeoRoundTripFunc func(*http.Request) (*http.Response, error)

func (fn ipGeoRoundTripFunc) RoundTrip(request *http.Request) (*http.Response, error) {
	return fn(request)
}

func ipGeoJSONResponse(body string) *http.Response {
	return &http.Response{
		StatusCode: http.StatusOK,
		Header:     make(http.Header),
		Body:       io.NopCloser(strings.NewReader(body)),
	}
}

func allowProviderFetch(_ context.Context, _ string) error {
	return nil
}

func TestLookupIPGeoDisablesCleartextIPAPI(t *testing.T) {
	var calls atomic.Int32
	client := &http.Client{Transport: ipGeoRoundTripFunc(func(_ *http.Request) (*http.Response, error) {
		calls.Add(1)
		return ipGeoJSONResponse(`{"status":"success"}`), nil
	})}
	service := newIPGeoService(client, time.Hour, 32)

	payload, ok, err := service.lookupIPGeoWithBudget(context.Background(), "8.8.8.8", "ip-api", config.ExternalConfig{}, allowProviderFetch)
	if err != nil || ok || payload != nil {
		t.Fatalf("lookupIPGeo(ip-api) = %#v, %v, %v; want disabled provider", payload, ok, err)
	}
	if calls.Load() != 0 {
		t.Fatalf("disabled ip-api provider made %d outbound requests", calls.Load())
	}
}

func TestLookupIPGeoUsesHTTPSCacheAndPayloadCopies(t *testing.T) {
	var calls atomic.Int32
	var budgetCalls atomic.Int32
	client := &http.Client{Transport: ipGeoRoundTripFunc(func(request *http.Request) (*http.Response, error) {
		calls.Add(1)
		if request.URL.Scheme != "https" || request.URL.Hostname() != "api.ip2location.io" {
			t.Fatalf("outbound URL = %s, want fixed HTTPS IP2Location host", request.URL)
		}
		return ipGeoJSONResponse(`{"country_name":"China","region_name":"Guangdong","city_name":"Shenzhen","latitude":22.5,"longitude":114.0}`), nil
	})}
	service := newIPGeoService(client, time.Hour, 32)
	cfg := config.ExternalConfig{IP2LocationKey: "test-key"}
	budget := func(_ context.Context, provider string) error {
		budgetCalls.Add(1)
		if provider != "ip2location" {
			t.Fatalf("budget provider = %q, want ip2location", provider)
		}
		return nil
	}

	first, ok, err := service.lookupIPGeoWithBudget(context.Background(), "8.8.8.8", "ip2location", cfg, budget)
	if err != nil || !ok {
		t.Fatalf("first lookup = %#v, %v, %v", first, ok, err)
	}
	first["city"] = "mutated"
	second, ok, err := service.lookupIPGeoWithBudget(context.Background(), "8.8.8.8", "ip2location", cfg, budget)
	if err != nil || !ok {
		t.Fatalf("cached lookup = %#v, %v, %v", second, ok, err)
	}
	if second["city"] != "Shenzhen" {
		t.Fatalf("cached payload city = %#v, want independent Shenzhen copy", second["city"])
	}
	if calls.Load() != 1 {
		t.Fatalf("two identical lookups made %d outbound requests, want 1", calls.Load())
	}
	if budgetCalls.Load() != 1 {
		t.Fatalf("cache hit consumed %d shared budget slots, want 1", budgetCalls.Load())
	}
}

func TestLookupIPGeoCoalescesConcurrentCacheMisses(t *testing.T) {
	var calls atomic.Int32
	var budgetCalls atomic.Int32
	release := make(chan struct{})
	client := &http.Client{Transport: ipGeoRoundTripFunc(func(_ *http.Request) (*http.Response, error) {
		calls.Add(1)
		<-release
		return ipGeoJSONResponse(`{"country_name":"China","city_name":"Shenzhen"}`), nil
	})}
	service := newIPGeoService(client, time.Hour, 32)
	cfg := config.ExternalConfig{IP2LocationKey: "test-key"}
	budget := func(_ context.Context, _ string) error {
		budgetCalls.Add(1)
		return nil
	}

	const workers = 8
	var wait sync.WaitGroup
	wait.Add(workers)
	errorsFound := make(chan error, workers)
	for index := 0; index < workers; index++ {
		go func() {
			defer wait.Done()
			_, ok, err := service.lookupIPGeoWithBudget(context.Background(), "1.1.1.1", "ip2location", cfg, budget)
			if err != nil {
				errorsFound <- err
			} else if !ok {
				errorsFound <- errors.New("lookup returned no result")
			}
		}()
	}
	deadline := time.After(time.Second)
	for calls.Load() == 0 {
		select {
		case <-deadline:
			t.Fatal("outbound lookup did not start")
		default:
			time.Sleep(time.Millisecond)
		}
	}
	close(release)
	wait.Wait()
	close(errorsFound)
	for err := range errorsFound {
		t.Fatal(err)
	}
	if calls.Load() != 1 {
		t.Fatalf("concurrent identical lookups made %d outbound requests, want 1", calls.Load())
	}
	if budgetCalls.Load() != 1 {
		t.Fatalf("concurrent identical lookups consumed %d shared budget slots, want 1", budgetCalls.Load())
	}
}

func TestTwoUsersShareProviderBudgetAndExhaustionStopsUpstream(t *testing.T) {
	var outboundCalls atomic.Int32
	client := &http.Client{Transport: ipGeoRoundTripFunc(func(_ *http.Request) (*http.Response, error) {
		outboundCalls.Add(1)
		return ipGeoJSONResponse(`{"country_name":"China"}`), nil
	})}
	service := newIPGeoService(client, time.Hour, 32)
	cfg := config.ExternalConfig{IP2LocationKey: "test-key"}
	remaining := atomic.Int32{}
	remaining.Store(1)
	var usersMu sync.Mutex
	budgetUsers := make([]int64, 0, 2)
	errBudgetExhausted := errors.New("shared provider budget exhausted")
	budgetForUser := func(userID int64) ProviderFetchBudget {
		return func(_ context.Context, provider string) error {
			if provider != "ip2location" {
				t.Fatalf("budget provider = %q, want ip2location", provider)
			}
			usersMu.Lock()
			budgetUsers = append(budgetUsers, userID)
			usersMu.Unlock()
			if remaining.Add(-1) < 0 {
				return errBudgetExhausted
			}
			return nil
		}
	}

	if _, ok, err := service.lookupIPGeoWithBudget(context.Background(), "1.1.1.1", "ip2location", cfg, budgetForUser(101)); err != nil || !ok {
		t.Fatalf("first user lookup = ok %v, error %v", ok, err)
	}
	if _, _, err := service.lookupIPGeoWithBudget(context.Background(), "8.8.8.8", "ip2location", cfg, budgetForUser(202)); !errors.Is(err, errBudgetExhausted) {
		t.Fatalf("second user lookup error = %v, want shared budget exhaustion", err)
	}
	if outboundCalls.Load() != 1 {
		t.Fatalf("exhausted shared budget allowed %d outbound calls, want 1", outboundCalls.Load())
	}
	usersMu.Lock()
	defer usersMu.Unlock()
	if len(budgetUsers) != 2 || budgetUsers[0] != 101 || budgetUsers[1] != 202 {
		t.Fatalf("budget users = %v, want [101 202]", budgetUsers)
	}
}

func TestProviderBudgetRunsBeforeEverySupportedUpstream(t *testing.T) {
	errBudgetExhausted := errors.New("shared provider budget exhausted")
	cfg := config.ExternalConfig{
		IPInfoLiteToken: "test-token",
		IP2LocationKey:  "test-key",
		IPDataKey:       "test-key",
		IPRegistryKey:   "test-key",
	}
	tests := []struct {
		name     string
		provider string
		lookup   func(*ipGeoService, ProviderFetchBudget) error
	}{
		{
			name:     "IP2Location",
			provider: "ip2location",
			lookup: func(service *ipGeoService, budget ProviderFetchBudget) error {
				_, _, err := service.lookupIPGeoWithBudget(context.Background(), "8.8.8.8", "ip2location", cfg, budget)
				return err
			},
		},
		{
			name:     "ipdata",
			provider: "ipdata",
			lookup: func(service *ipGeoService, budget ProviderFetchBudget) error {
				_, _, err := service.lookupIPGeoWithBudget(context.Background(), "8.8.8.8", "ipdata", cfg, budget)
				return err
			},
		},
		{
			name:     "ipregistry",
			provider: "ipregistry",
			lookup: func(service *ipGeoService, budget ProviderFetchBudget) error {
				_, _, err := service.lookupIPGeoWithBudget(context.Background(), "8.8.8.8", "ipregistry", cfg, budget)
				return err
			},
		},
		{
			name:     "IPinfo Lite",
			provider: "ipinfo-lite",
			lookup: func(service *ipGeoService, budget ProviderFetchBudget) error {
				_, _, err := service.lookupIPInfoLiteWithBudget(context.Background(), "8.8.8.8", cfg.IPInfoLiteToken, budget)
				return err
			},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			var outboundCalls atomic.Int32
			client := &http.Client{Transport: ipGeoRoundTripFunc(func(_ *http.Request) (*http.Response, error) {
				outboundCalls.Add(1)
				return ipGeoJSONResponse(`{"country_name":"China"}`), nil
			})}
			service := newIPGeoService(client, time.Hour, 32)
			var budgetCalls atomic.Int32
			budget := func(_ context.Context, provider string) error {
				budgetCalls.Add(1)
				if provider != test.provider {
					t.Fatalf("budget provider = %q, want %q", provider, test.provider)
				}
				return errBudgetExhausted
			}

			if err := test.lookup(service, budget); !errors.Is(err, errBudgetExhausted) {
				t.Fatalf("lookup error = %v, want shared budget exhaustion", err)
			}
			if budgetCalls.Load() != 1 {
				t.Fatalf("budget calls = %d, want 1", budgetCalls.Load())
			}
			if outboundCalls.Load() != 0 {
				t.Fatalf("exhausted provider made %d outbound calls, want 0", outboundCalls.Load())
			}
		})
	}
}

func TestCacheMissWithoutProviderBudgetStopsUpstream(t *testing.T) {
	var outboundCalls atomic.Int32
	client := &http.Client{Transport: ipGeoRoundTripFunc(func(_ *http.Request) (*http.Response, error) {
		outboundCalls.Add(1)
		return ipGeoJSONResponse(`{"country_name":"China"}`), nil
	})}
	service := newIPGeoService(client, time.Hour, 32)
	cfg := config.ExternalConfig{IP2LocationKey: "test-key"}

	if _, _, err := service.lookupIPGeoWithBudget(context.Background(), "8.8.8.8", "ip2location", cfg, nil); err == nil {
		t.Fatal("cache miss without provider budget succeeded")
	}
	if outboundCalls.Load() != 0 {
		t.Fatalf("missing provider budget made %d outbound calls, want 0", outboundCalls.Load())
	}
}

func TestIPGeoCacheIsBoundedAndExpires(t *testing.T) {
	var calls atomic.Int32
	client := &http.Client{Transport: ipGeoRoundTripFunc(func(_ *http.Request) (*http.Response, error) {
		calls.Add(1)
		return ipGeoJSONResponse(`{"country_name":"China"}`), nil
	})}
	service := newIPGeoService(client, time.Minute, 2)
	now := time.Date(2026, time.July, 14, 0, 0, 0, 0, time.UTC)
	service.now = func() time.Time { return now }
	cfg := config.ExternalConfig{IP2LocationKey: "test-key"}

	for _, ip := range []string{"1.1.1.1", "8.8.8.8", "9.9.9.9"} {
		if _, ok, err := service.lookupIPGeoWithBudget(context.Background(), ip, "ip2location", cfg, allowProviderFetch); err != nil || !ok {
			t.Fatalf("lookupIPGeo(%s) = ok %v, error %v", ip, ok, err)
		}
	}
	service.mu.Lock()
	cacheEntries := len(service.cache)
	service.mu.Unlock()
	if cacheEntries > 2 {
		t.Fatalf("cache contains %d entries, want at most 2", cacheEntries)
	}

	now = now.Add(2 * time.Minute)
	before := calls.Load()
	if _, ok, err := service.lookupIPGeoWithBudget(context.Background(), "9.9.9.9", "ip2location", cfg, allowProviderFetch); err != nil || !ok {
		t.Fatalf("expired lookup = ok %v, error %v", ok, err)
	}
	if calls.Load() != before+1 {
		t.Fatalf("expired cache made %d additional outbound requests, want 1", calls.Load()-before)
	}
}

func TestSecureIPGeoClientRejectsRedirects(t *testing.T) {
	client := secureIPGeoClient(4 * time.Second)
	redirect, _ := http.NewRequest(http.MethodGet, "https://127.0.0.1/latest/meta-data/", nil)
	original, _ := http.NewRequest(http.MethodGet, "https://api.ipdata.co/8.8.8.8", nil)
	if err := client.CheckRedirect(redirect, []*http.Request{original}); !errors.Is(err, http.ErrUseLastResponse) {
		t.Fatalf("CheckRedirect() error = %v, want http.ErrUseLastResponse", err)
	}
}

func TestIPGeoRequestDoesNotFollowRedirectToInternalTarget(t *testing.T) {
	var calls atomic.Int32
	client := secureIPGeoClient(4 * time.Second)
	client.Transport = ipGeoRoundTripFunc(func(request *http.Request) (*http.Response, error) {
		calls.Add(1)
		response := &http.Response{
			StatusCode: http.StatusFound,
			Header:     make(http.Header),
			Body:       io.NopCloser(strings.NewReader("")),
			Request:    request,
		}
		response.Header.Set("Location", "http://127.0.0.1/latest/meta-data/")
		return response, nil
	})
	service := newIPGeoService(client, time.Hour, 32)

	var target map[string]any
	if err := service.getJSON(context.Background(), "https://api.ipdata.co/8.8.8.8", &target); err == nil {
		t.Fatal("redirecting provider response succeeded")
	}
	if calls.Load() != 1 {
		t.Fatalf("redirecting response caused %d requests, want only the fixed provider request", calls.Load())
	}
}

func TestValidateIPGeoEndpointRejectsUntrustedTargets(t *testing.T) {
	for _, endpoint := range []string{
		"http://api.ipdata.co/8.8.8.8",
		"https://127.0.0.1/latest/meta-data/",
		"https://api.ipdata.co.evil.example/8.8.8.8",
	} {
		if err := validateIPGeoEndpoint(endpoint); err == nil {
			t.Fatalf("validateIPGeoEndpoint(%q) succeeded", endpoint)
		}
	}
	if err := validateIPGeoEndpoint("https://api.ipdata.co/8.8.8.8"); err != nil {
		t.Fatalf("validateIPGeoEndpoint(allowed) error = %v", err)
	}
}
