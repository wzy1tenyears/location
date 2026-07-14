package handlers

import (
	"context"
	"database/sql"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"familylocation/location-v3/internal/config"
	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/repositories"
	"familylocation/location-v3/internal/services"
	"familylocation/location-v3/internal/session"
)

const (
	diagnosticProviderQuotaBucket         = "diagnostic-provider-lookup"
	diagnosticProviderQuotaMaxHits        = 600
	diagnosticProviderQuotaWindow         = time.Hour
	diagnosticProviderUserMissQuotaBucket = "diagnostic-provider-user-miss"
	diagnosticProviderSharedQuotaBucket   = "diagnostic-provider-upstream"
)

type diagnosticRateLimiter interface {
	Hit(context.Context, string, string, int, time.Duration) (bool, error)
}

type DiagnosticHandler struct {
	cfg   config.Config
	scope scopedHandler
	geo   repositories.GeoRepository
	rates diagnosticRateLimiter
}

func NewDiagnosticHandler(cfg config.Config, db *sql.DB, sessions session.Reader) DiagnosticHandler {
	return DiagnosticHandler{
		cfg:   cfg,
		scope: newScopedHandler(db, sessions),
		geo:   repositories.NewGeoRepository(db),
		rates: repositories.NewRateLimitRepository(db),
	}
}

func (handler DiagnosticHandler) IPProbe(w http.ResponseWriter, r *http.Request) {
	if _, _, err := handler.scope.requireScope(r, ""); err != nil {
		httpx.Error(w, err)
		return
	}
	httpx.OK(w, map[string]any{
		"ok": true,
		"ip": httpx.ClientIP(r),
	})
}

func (handler DiagnosticHandler) CloudflareLocation(w http.ResponseWriter, r *http.Request) {
	if _, _, err := handler.scope.requireScope(r, ""); err != nil {
		httpx.Error(w, err)
		return
	}

	ip := validIP(headerText(r, "CF-Connecting-IP", 80), "")
	ipv6 := validIP(headerText(r, "CF-Connecting-IPv6", 80), "ipv6")
	countryCode := strings.ToUpper(headerText(r, "CF-IPCountry", 8))
	city := headerText(r, "CF-IPCity", 120)
	region := headerText(r, "CF-Region", 120)
	latitude := headerFloat(r, "CF-IPLatitude", -90, 90)
	longitude := headerFloat(r, "CF-IPLongitude", -180, 180)

	httpx.OK(w, map[string]any{
		"ok":           true,
		"available":    ip != "" || ipv6 != "" || countryCode != "" || city != "" || region != "" || latitude != nil || longitude != nil,
		"ip":           ip,
		"ipv6":         ipv6,
		"country":      countryCode,
		"country_code": countryCode,
		"city":         city,
		"region":       region,
		"region_code":  headerText(r, "CF-Region-Code", 40),
		"continent":    headerText(r, "CF-IPContinent", 40),
		"latitude":     latitude,
		"longitude":    longitude,
		"metro_code":   headerText(r, "CF-Metro-Code", 40),
		"postal_code":  headerText(r, "CF-Postal-Code", 40),
		"timezone":     headerText(r, "CF-Timezone", 80),
	})
}

func (handler DiagnosticHandler) GeoAliases(w http.ResponseWriter, r *http.Request) {
	regions, err := handler.geo.ChinaRegions(r.Context())
	if err != nil {
		httpx.Error(w, err)
		return
	}

	cityAliases := map[string]string{}
	regionAliases := map[string]string{}
	for _, region := range regions {
		if region.Level == 1 {
			addGeoAlias(regionAliases, region.Name)
		} else if region.Level == 2 || region.Level == 3 {
			addGeoAlias(cityAliases, region.Name)
		}
	}

	httpx.OK(w, map[string]any{
		"ok":        true,
		"available": len(cityAliases) > 0 || len(regionAliases) > 0,
		"aliases": map[string]any{
			"CITY_ALIASES":   cityAliases,
			"REGION_ALIASES": regionAliases,
		},
		"counts": map[string]any{
			"cities":  len(cityAliases),
			"regions": len(regionAliases),
		},
	})
}

func (handler DiagnosticHandler) IPGeo(w http.ResponseWriter, r *http.Request) {
	scope, _, err := handler.scope.requireScope(r, "")
	if err != nil {
		httpx.Error(w, err)
		return
	}

	var req struct {
		IP       string `json:"ip"`
		Provider string `json:"provider"`
	}
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.Error(w, err)
		return
	}
	if net.ParseIP(strings.TrimSpace(req.IP)) == nil {
		httpx.Error(w, httpx.Unprocessable("IP 地址不正确。"))
		return
	}
	if err := handler.consumeProviderQuota(r.Context(), scope.User.ID); err != nil {
		httpx.Error(w, err)
		return
	}

	payload, ok, err := services.LookupIPGeoContextWithBudget(
		r.Context(),
		req.IP,
		req.Provider,
		handler.cfg.External,
		handler.providerFetchBudget(scope.User.ID),
	)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if !ok {
		httpx.Error(w, httpx.APIError{Status: http.StatusNotFound, Message: "IP 查询源未配置或无结果。"})
		return
	}
	payload["ok"] = true
	httpx.OK(w, payload)
}

func (handler DiagnosticHandler) IPInfoLite(w http.ResponseWriter, r *http.Request) {
	scope, _, err := handler.scope.requireScope(r, "")
	if err != nil {
		httpx.Error(w, err)
		return
	}

	var req struct {
		IP string `json:"ip"`
	}
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.Error(w, err)
		return
	}
	if net.ParseIP(strings.TrimSpace(req.IP)) == nil {
		httpx.Error(w, httpx.Unprocessable("IP 地址不正确。"))
		return
	}
	if err := handler.consumeProviderQuota(r.Context(), scope.User.ID); err != nil {
		httpx.Error(w, err)
		return
	}

	payload, ok, err := services.LookupIPInfoLiteContextWithBudget(
		r.Context(),
		req.IP,
		handler.cfg.External.IPInfoLiteToken,
		handler.providerFetchBudget(scope.User.ID),
	)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if !ok {
		httpx.Error(w, httpx.APIError{Status: http.StatusInternalServerError, Message: "IPinfo Lite 未配置。"})
		return
	}
	payload["ok"] = true
	httpx.OK(w, payload)
}

func (handler DiagnosticHandler) consumeProviderQuota(ctx context.Context, userID int64) error {
	allowed, err := handler.rates.Hit(
		ctx,
		diagnosticProviderQuotaBucket,
		strconv.FormatInt(userID, 10),
		diagnosticProviderQuotaMaxHits,
		diagnosticProviderQuotaWindow,
	)
	if err != nil {
		return err
	}
	if !allowed {
		return httpx.APIError{Status: http.StatusTooManyRequests, Message: "IP 查询过于频繁，请稍后再试。"}
	}
	return nil
}

func (handler DiagnosticHandler) providerFetchBudget(userID int64) services.ProviderFetchBudget {
	return func(ctx context.Context, provider string) error {
		return handler.consumeProviderMissQuotas(ctx, userID, provider)
	}
}

func (handler DiagnosticHandler) consumeProviderMissQuotas(ctx context.Context, userID int64, provider string) error {
	provider = strings.ToLower(strings.TrimSpace(provider))
	quota, ok := handler.cfg.External.IPGeoQuota(provider)
	if !ok || quota.AvailableRequests() <= 0 || quota.UserMaxMisses <= 0 || quota.Window <= 0 {
		return httpx.APIError{Status: http.StatusServiceUnavailable, Message: "IP 查询源配额未配置。"}
	}

	allowed, err := handler.rates.Hit(
		ctx,
		diagnosticProviderUserMissQuotaBucket,
		provider+"|"+strconv.FormatInt(userID, 10),
		quota.UserMaxMisses,
		quota.Window,
	)
	if err != nil {
		return err
	}
	if !allowed {
		return httpx.APIError{Status: http.StatusTooManyRequests, Message: "IP 查询次数过多，请稍后再试。"}
	}

	allowed, err = handler.rates.Hit(
		ctx,
		diagnosticProviderSharedQuotaBucket,
		provider,
		quota.AvailableRequests(),
		quota.Window,
	)
	if err != nil {
		return err
	}
	if !allowed {
		return httpx.APIError{Status: http.StatusTooManyRequests, Message: "IP 查询服务繁忙，请稍后再试。"}
	}
	return nil
}

func addGeoAlias(aliases map[string]string, name string) {
	name = strings.TrimSpace(name)
	if name == "" || name == "市辖区" || name == "县" {
		return
	}
	aliases[name] = name
	suffixes := []string{"特别行政区", "自治区", "自治州", "地区", "省", "市", "县", "区"}
	short := name
	for _, suffix := range suffixes {
		if strings.HasSuffix(short, suffix) {
			short = strings.TrimSpace(strings.TrimSuffix(short, suffix))
			break
		}
	}
	if short != "" && short != name {
		aliases[short] = name
	}
}

func headerText(r *http.Request, key string, maxLength int) string {
	text := strings.TrimSpace(r.Header.Get(key))
	if text == "" {
		return ""
	}
	if decoded, err := url.QueryUnescape(text); err == nil {
		text = decoded
	}
	runes := []rune(text)
	if len(runes) > maxLength {
		return string(runes[:maxLength])
	}
	return text
}

func headerFloat(r *http.Request, key string, min float64, max float64) any {
	text := headerText(r, key, 40)
	if text == "" {
		return nil
	}
	value, err := strconv.ParseFloat(text, 64)
	if err != nil || value < min || value > max {
		return nil
	}
	return value
}

func validIP(value string, family string) string {
	ip := net.ParseIP(strings.TrimSpace(value))
	if ip == nil {
		return ""
	}
	if family == "ipv6" && ip.To4() != nil {
		return ""
	}
	return value
}
