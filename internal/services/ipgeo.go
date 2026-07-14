package services

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"familylocation/location-v3/internal/config"
)

type IPGeoPayload map[string]any

type ProviderFetchBudget func(context.Context, string) error

const ipGeoResponseLimit int64 = 2 << 20

var ipGeoAllowedHosts = map[string]struct{}{
	"api.ip2location.io": {},
	"api.ipdata.co":      {},
	"api.ipinfo.io":      {},
	"api.ipregistry.co":  {},
}

type ipGeoCacheEntry struct {
	payload   IPGeoPayload
	ok        bool
	expiresAt time.Time
}

type ipGeoInflight struct {
	done    chan struct{}
	payload IPGeoPayload
	ok      bool
	err     error
}

type ipGeoService struct {
	client     *http.Client
	cacheTTL   time.Duration
	maxEntries int
	now        func() time.Time

	mu       sync.Mutex
	cache    map[string]ipGeoCacheEntry
	inflight map[string]*ipGeoInflight
}

var defaultIPGeoService = newIPGeoService(secureIPGeoClient(4*time.Second), 30*time.Minute, 2048)

func secureIPGeoClient(timeout time.Duration) *http.Client {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	return &http.Client{
		Timeout:   timeout,
		Transport: transport,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
}

func newIPGeoService(client *http.Client, cacheTTL time.Duration, maxEntries int) *ipGeoService {
	if client == nil {
		client = secureIPGeoClient(4 * time.Second)
	}
	if cacheTTL <= 0 {
		cacheTTL = 30 * time.Minute
	}
	if maxEntries <= 0 {
		maxEntries = 2048
	}
	return &ipGeoService{
		client:     client,
		cacheTTL:   cacheTTL,
		maxEntries: maxEntries,
		now:        time.Now,
		cache:      make(map[string]ipGeoCacheEntry),
		inflight:   make(map[string]*ipGeoInflight),
	}
}

func LookupIPGeoContextWithBudget(ctx context.Context, ip string, provider string, cfg config.ExternalConfig, budget ProviderFetchBudget) (IPGeoPayload, bool, error) {
	return defaultIPGeoService.lookupIPGeoWithBudget(ctx, ip, provider, cfg, budget)
}

func (service *ipGeoService) lookupIPGeoWithBudget(ctx context.Context, ip string, provider string, cfg config.ExternalConfig, budget ProviderFetchBudget) (IPGeoPayload, bool, error) {
	ip = strings.TrimSpace(ip)
	parsedIP := net.ParseIP(ip)
	if parsedIP == nil {
		return nil, false, nil
	}
	ip = parsedIP.String()

	switch strings.ToLower(strings.TrimSpace(provider)) {
	case "ip-api":
		// The free ip-api endpoint does not support TLS, so it is intentionally unavailable.
		return nil, false, nil
	case "ip2location":
		if strings.TrimSpace(cfg.IP2LocationKey) == "" {
			return nil, false, nil
		}
		return service.cached(ctx, "ip2location|"+ip, func(ctx context.Context) (IPGeoPayload, bool, error) {
			if err := authorizeProviderFetch(ctx, budget, "ip2location"); err != nil {
				return nil, false, err
			}
			return service.lookupIP2Location(ctx, ip, cfg.IP2LocationKey)
		})
	case "ipdata":
		if strings.TrimSpace(cfg.IPDataKey) == "" {
			return nil, false, nil
		}
		return service.cached(ctx, "ipdata|"+ip, func(ctx context.Context) (IPGeoPayload, bool, error) {
			if err := authorizeProviderFetch(ctx, budget, "ipdata"); err != nil {
				return nil, false, err
			}
			return service.lookupIPData(ctx, ip, cfg.IPDataKey)
		})
	case "ipregistry":
		if strings.TrimSpace(cfg.IPRegistryKey) == "" {
			return nil, false, nil
		}
		return service.cached(ctx, "ipregistry|"+ip, func(ctx context.Context) (IPGeoPayload, bool, error) {
			if err := authorizeProviderFetch(ctx, budget, "ipregistry"); err != nil {
				return nil, false, err
			}
			return service.lookupIPRegistry(ctx, ip, cfg.IPRegistryKey)
		})
	default:
		return nil, false, nil
	}
}

func LookupIPInfoLiteContextWithBudget(ctx context.Context, ip string, token string, budget ProviderFetchBudget) (IPGeoPayload, bool, error) {
	return defaultIPGeoService.lookupIPInfoLiteWithBudget(ctx, ip, token, budget)
}

func (service *ipGeoService) lookupIPInfoLiteWithBudget(ctx context.Context, ip string, token string, budget ProviderFetchBudget) (IPGeoPayload, bool, error) {
	ip = strings.TrimSpace(ip)
	parsedIP := net.ParseIP(ip)
	if parsedIP == nil || strings.TrimSpace(token) == "" {
		return nil, false, nil
	}
	ip = parsedIP.String()

	return service.cached(ctx, "ipinfo-lite|"+ip, func(ctx context.Context) (IPGeoPayload, bool, error) {
		if err := authorizeProviderFetch(ctx, budget, "ipinfo-lite"); err != nil {
			return nil, false, err
		}
		var data map[string]any
		endpoint := "https://api.ipinfo.io/lite/" + url.PathEscape(ip) + "?token=" + url.QueryEscape(token)
		if err := service.getJSON(ctx, endpoint, &data); err != nil {
			return nil, false, err
		}

		return IPGeoPayload{
			"ip":        ip,
			"country":   stringValue(data, "country", "country_code"),
			"region":    stringValue(data, "region"),
			"city":      stringValue(data, "city"),
			"latitude":  numericOrNil(data["latitude"]),
			"longitude": numericOrNil(data["longitude"]),
			"provider":  "IPinfo Lite",
		}, true, nil
	})
}

func authorizeProviderFetch(ctx context.Context, budget ProviderFetchBudget, provider string) error {
	if budget == nil {
		return fmt.Errorf("provider fetch budget is required")
	}
	return budget(ctx, provider)
}

func (service *ipGeoService) lookupIP2Location(ctx context.Context, ip string, key string) (IPGeoPayload, bool, error) {
	var data map[string]any
	endpoint := "https://api.ip2location.io/?key=" + url.QueryEscape(key) + "&ip=" + url.QueryEscape(ip) + "&format=json"
	if err := service.getJSON(ctx, endpoint, &data); err != nil {
		return nil, false, err
	}

	return ipGeoPayload(ip, "IP2Location.io", firstAny(data["country_name"], data["country_code"]), data["region_name"], data["city_name"], data["latitude"], data["longitude"], map[string]any{
		"asn": firstAny(data["asn"], data["as"]),
		"isp": data["isp"],
		"org": data["as"],
	}), true, nil
}

func (service *ipGeoService) lookupIPData(ctx context.Context, ip string, key string) (IPGeoPayload, bool, error) {
	var data map[string]any
	endpoint := "https://api.ipdata.co/" + url.PathEscape(ip) + "?api-key=" + url.QueryEscape(key)
	if err := service.getJSON(ctx, endpoint, &data); err != nil {
		return nil, false, err
	}
	asn := nestedMap(data, "asn")

	return ipGeoPayload(ip, "ipdata.co", firstAny(data["country_name"], data["country_code"]), data["region"], data["city"], data["latitude"], data["longitude"], map[string]any{
		"asn": asn["asn"],
		"isp": asn["name"],
		"org": firstAny(data["organisation"], data["organization"]),
	}), true, nil
}

func (service *ipGeoService) lookupIPRegistry(ctx context.Context, ip string, key string) (IPGeoPayload, bool, error) {
	var data map[string]any
	endpoint := "https://api.ipregistry.co/" + url.PathEscape(ip) + "?key=" + url.QueryEscape(key)
	if err := service.getJSON(ctx, endpoint, &data); err != nil {
		return nil, false, err
	}
	location := nestedMap(data, "location")
	country := nestedMap(location, "country")
	region := nestedMap(location, "region")
	connection := nestedMap(data, "connection")

	return ipGeoPayload(ip, "Ipregistry", firstAny(country["name"], country["code"]), region["name"], location["city"], location["latitude"], location["longitude"], map[string]any{
		"asn": connection["asn"],
		"isp": connection["isp"],
		"org": connection["organization"],
	}), true, nil
}

func (service *ipGeoService) cached(ctx context.Context, key string, load func(context.Context) (IPGeoPayload, bool, error)) (IPGeoPayload, bool, error) {
	service.mu.Lock()
	now := service.now()
	if entry, exists := service.cache[key]; exists {
		if now.Before(entry.expiresAt) {
			service.mu.Unlock()
			return cloneIPGeoPayload(entry.payload), entry.ok, nil
		}
		delete(service.cache, key)
	}
	if call, exists := service.inflight[key]; exists {
		done := call.done
		service.mu.Unlock()
		select {
		case <-done:
			return cloneIPGeoPayload(call.payload), call.ok, call.err
		case <-ctx.Done():
			return nil, false, ctx.Err()
		}
	}

	call := &ipGeoInflight{done: make(chan struct{})}
	service.inflight[key] = call
	service.mu.Unlock()

	payload, ok, err := load(ctx)
	payload = cloneIPGeoPayload(payload)

	service.mu.Lock()
	call.payload = cloneIPGeoPayload(payload)
	call.ok = ok
	call.err = err
	if err == nil {
		service.evictLocked(service.now())
		service.cache[key] = ipGeoCacheEntry{
			payload:   cloneIPGeoPayload(payload),
			ok:        ok,
			expiresAt: service.now().Add(service.cacheTTL),
		}
	}
	delete(service.inflight, key)
	close(call.done)
	service.mu.Unlock()

	return payload, ok, err
}

func (service *ipGeoService) evictLocked(now time.Time) {
	for key, entry := range service.cache {
		if !now.Before(entry.expiresAt) {
			delete(service.cache, key)
		}
	}
	for len(service.cache) >= service.maxEntries {
		for key := range service.cache {
			delete(service.cache, key)
			break
		}
	}
}

func (service *ipGeoService) getJSON(ctx context.Context, endpoint string, target any) error {
	if err := validateIPGeoEndpoint(endpoint); err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "loc-app-server")

	resp, err := service.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < http.StatusOK || resp.StatusCode >= http.StatusMultipleChoices {
		return fmt.Errorf("request failed with status %d", resp.StatusCode)
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, ipGeoResponseLimit+1))
	if err != nil {
		return err
	}
	if int64(len(body)) > ipGeoResponseLimit {
		return fmt.Errorf("response body exceeds %d bytes", ipGeoResponseLimit)
	}
	return json.Unmarshal(body, target)
}

func validateIPGeoEndpoint(endpoint string) error {
	parsed, err := url.Parse(endpoint)
	if err != nil {
		return fmt.Errorf("invalid IP geolocation endpoint: %w", err)
	}
	host := strings.ToLower(parsed.Hostname())
	_, allowed := ipGeoAllowedHosts[host]
	if parsed.Scheme != "https" || !allowed || parsed.User != nil || (parsed.Port() != "" && parsed.Port() != "443") {
		return fmt.Errorf("untrusted IP geolocation endpoint")
	}
	return nil
}

func cloneIPGeoPayload(payload IPGeoPayload) IPGeoPayload {
	if payload == nil {
		return nil
	}
	cloned := make(IPGeoPayload, len(payload))
	for key, value := range payload {
		cloned[key] = value
	}
	return cloned
}

func ipGeoPayload(ip string, provider string, country any, region any, city any, latitude any, longitude any, network map[string]any) IPGeoPayload {
	asn := strings.TrimSpace(fmt.Sprint(firstAny(network["asn"], "")))
	isp := strings.TrimSpace(fmt.Sprint(firstAny(network["isp"], "")))
	org := strings.TrimSpace(fmt.Sprint(firstAny(network["org"], "")))
	carrier := strings.TrimSpace(fmt.Sprint(firstAny(network["carrier"], "")))
	networkText := strings.ToLower(asn + " " + isp + " " + org + " " + carrier)
	mobileNetwork := truthy(network["mobile_network"]) ||
		strings.Contains(networkText, "china mobile") ||
		strings.Contains(networkText, "cmnet") ||
		strings.Contains(networkText, "cmi") ||
		strings.Contains(networkText, "中国移动") ||
		strings.Contains(networkText, "移动")

	return IPGeoPayload{
		"ip":             ip,
		"provider":       provider,
		"country":        strings.TrimSpace(fmt.Sprint(firstAny(country, ""))),
		"region":         strings.TrimSpace(fmt.Sprint(firstAny(region, ""))),
		"city":           strings.TrimSpace(fmt.Sprint(firstAny(city, ""))),
		"latitude":       numericOrNil(latitude),
		"longitude":      numericOrNil(longitude),
		"asn":            asn,
		"isp":            isp,
		"org":            org,
		"carrier":        carrier,
		"mobile_network": mobileNetwork,
	}
}

func numericOrNil(value any) any {
	switch typed := value.(type) {
	case float64:
		return typed
	case float32:
		return float64(typed)
	case int:
		return float64(typed)
	case int64:
		return float64(typed)
	case jsonNumber:
		value, err := typed.Float64()
		if err != nil {
			return nil
		}
		return value
	case string:
		text := strings.TrimSpace(typed)
		if text == "" {
			return nil
		}
		value, err := strconv.ParseFloat(text, 64)
		if err != nil {
			return nil
		}
		return value
	default:
		return nil
	}
}

type jsonNumber interface {
	Float64() (float64, error)
}

func nestedMap(data map[string]any, key string) map[string]any {
	if nested, ok := data[key].(map[string]any); ok {
		return nested
	}
	return map[string]any{}
}

func stringValue(data map[string]any, keys ...string) string {
	for _, key := range keys {
		text := strings.TrimSpace(fmt.Sprint(data[key]))
		if text != "" && text != "<nil>" {
			return text
		}
	}
	return ""
}

func firstAny(values ...any) any {
	for _, value := range values {
		if value == nil {
			continue
		}
		text := strings.TrimSpace(fmt.Sprint(value))
		if text != "" && text != "<nil>" {
			return value
		}
	}
	return ""
}
