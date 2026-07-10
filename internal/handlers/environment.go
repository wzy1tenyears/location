package handlers

import (
	"database/sql"
	"encoding/json"
	"net/http"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/repositories"
	"familylocation/location-v3/internal/services"
	"familylocation/location-v3/internal/session"
)

type EnvironmentHandler struct {
	scope    scopedHandler
	reports  repositories.EnvironmentReportRepository
	settings repositories.SettingRepository
}

func NewEnvironmentHandler(db *sql.DB, sessions session.Reader) EnvironmentHandler {
	return EnvironmentHandler{
		scope:    newScopedHandler(db, sessions),
		reports:  repositories.NewEnvironmentReportRepository(db),
		settings: repositories.NewSettingRepository(db),
	}
}

type environmentReportRequest struct {
	Report map[string]any `json:"report"`
	Force  bool           `json:"force"`
}

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

	duplicatePattern := `%"installed_apps"%`
	tooLargeMessage := "环境数据过大。"
	if deviceIntegrity {
		req.Report["report_kind"] = "device_integrity"
		req.Report["forced_device_report"] = true
		duplicatePattern = `%"report_kind":"device_integrity"%`
		tooLargeMessage = "设备数据过大。"
	}

	payload, err := json.Marshal(req.Report)
	if err != nil || len(payload) > 200000 {
		httpx.Error(w, httpx.Unprocessable(tooLargeMessage))
		return
	}
	if !req.Force {
		exists, err := handler.reports.HasReportTodayLike(r.Context(), scope.User.ID, duplicatePattern)
		if err != nil {
			httpx.Error(w, err)
			return
		}
		if exists {
			httpx.OK(w, map[string]any{"ok": true, "skipped": true})
			return
		}
	}
	if err := handler.reports.Insert(r.Context(), scope.User.ID, string(payload)); err != nil {
		httpx.Error(w, err)
		return
	}
	httpx.OK(w, map[string]any{"ok": true})
}
