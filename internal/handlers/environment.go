package handlers

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"strconv"
	"time"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/repositories"
	"familylocation/location-v3/internal/services"
	"familylocation/location-v3/internal/session"
)

type EnvironmentHandler struct {
	scope    scopedHandler
	reports  repositories.EnvironmentReportRepository
	rates    repositories.RateLimitRepository
	settings repositories.SettingRepository
}

func NewEnvironmentHandler(db *sql.DB, sessions session.Reader) EnvironmentHandler {
	return EnvironmentHandler{
		scope:    newScopedHandler(db, sessions),
		reports:  repositories.NewEnvironmentReportRepository(db),
		rates:    repositories.NewRateLimitRepository(db),
		settings: repositories.NewSettingRepository(db),
	}
}

type environmentReportRequest struct {
	Report map[string]any `json:"report"`
}

const (
	environmentReportWriteLimit = 12
	deviceReportWriteLimit      = 48
	reportWriteWindow           = 24 * time.Hour
)

func (handler EnvironmentHandler) EnvironmentReport(w http.ResponseWriter, r *http.Request) {
	handler.saveReport(w, r, false)
}

func (handler EnvironmentHandler) DeviceReport(w http.ResponseWriter, r *http.Request) {
	handler.saveReport(w, r, true)
}

func (handler EnvironmentHandler) saveReport(w http.ResponseWriter, r *http.Request, deviceIntegrity bool) {
	var req environmentReportRequest
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.Error(w, httpx.BadRequest("请求格式不正确。"))
		return
	}
	if req.Report == nil {
		req.Report = map[string]any{}
	}

	scope, _, err := handler.scope.requireScope(r, "")
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if !deviceIntegrity && !scope.User.EnvironmentDataConsentAt.Valid {
		httpx.Error(w, httpx.Forbidden("未同意环境数据上报。"))
		return
	}

	policy, err := handler.settings.SecurityPolicy(r.Context())
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if err := services.EnforceDeviceReportPolicy(*scope.User, req.Report, policy); err != nil {
		httpx.Error(w, err)
		return
	}

	reportKind := repositories.EnvironmentReportKindEnvironment
	rateBucket := "environment_report_write"
	rateLimit := environmentReportWriteLimit
	tooLargeMessage := "环境数据过大。"
	if deviceIntegrity {
		reportKind = repositories.EnvironmentReportKindDeviceIntegrity
		rateBucket = "device_report_write"
		rateLimit = deviceReportWriteLimit
		req.Report["forced_device_report"] = true
		tooLargeMessage = "设备数据过大。"
	} else {
		delete(req.Report, "forced_device_report")
	}
	req.Report["report_kind"] = reportKind

	payload, err := json.Marshal(req.Report)
	if err != nil || len(payload) > repositories.EnvironmentReportPayloadLimit {
		httpx.Error(w, httpx.Unprocessable(tooLargeMessage))
		return
	}
	allowed, err := handler.rates.Hit(r.Context(), rateBucket, strconv.FormatInt(scope.User.ID, 10), rateLimit, reportWriteWindow)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if !allowed {
		httpx.Error(w, httpx.APIError{Status: http.StatusTooManyRequests, Message: "上报过于频繁，请稍后再试。"})
		return
	}
	result, err := handler.reports.StoreDaily(r.Context(), scope.User.ID, reportKind, string(payload))
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if result == repositories.EnvironmentReportQuotaExceeded {
		httpx.Error(w, httpx.APIError{Status: http.StatusTooManyRequests, Message: "今日环境数据上报已达上限。"})
		return
	}
	if result == repositories.EnvironmentReportUnchanged {
		httpx.OK(w, map[string]any{"ok": true, "skipped": true})
		return
	}
	httpx.OK(w, map[string]any{"ok": true})
}
