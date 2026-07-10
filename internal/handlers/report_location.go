package handlers

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"familylocation/location-v3/internal/config"
	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/repositories"
	"familylocation/location-v3/internal/services"
	"familylocation/location-v3/internal/session"
)

type ReportLocationHandler struct {
	cfg      config.Config
	db       *sql.DB
	scope    scopedHandler
	users    repositories.UserRepository
	reports  repositories.EnvironmentReportRepository
	settings repositories.SettingRepository
	rates    repositories.RateLimitRepository
}

func NewReportLocationHandler(cfg config.Config, db *sql.DB, sessions session.Reader) ReportLocationHandler {
	return ReportLocationHandler{
		cfg:      cfg,
		db:       db,
		scope:    newScopedHandler(db, sessions),
		users:    repositories.NewUserRepository(db),
		reports:  repositories.NewEnvironmentReportRepository(db),
		settings: repositories.NewSettingRepository(db),
		rates:    repositories.NewRateLimitRepository(db),
	}
}

func (handler ReportLocationHandler) Report(w http.ResponseWriter, r *http.Request) {
	var data map[string]any
	if err := httpx.DecodeJSON(r, &data); err != nil {
		httpx.Error(w, httpx.BadRequest("请求格式不正确。"))
		return
	}
	groupName := stringFromMap(data, "group_name", 100)
	scope, _, err := handler.scope.requireScope(r, groupName)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	allowed, err := handler.rates.Hit(r.Context(), "report_location", fmt.Sprintf("user:%d", scope.User.ID), 900, time.Hour)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if !allowed {
		httpx.Error(w, httpx.APIError{Status: http.StatusTooManyRequests, Message: "请求过于频繁，请稍后再试。"})
		return
	}
	if _, ok := requestDeviceFingerprint(r, handler.cfg.App.DeviceCookieName); !ok {
		httpx.Error(w, httpx.Forbidden("请使用新版 App 上报位置。"))
		return
	}

	p2pEnabled := scope.Membership.P2PEnabledAt.Valid
	encryptedPayloadJSON, encryptedPresent, err := encryptedPayload(data["encrypted_payload"])
	if err != nil {
		httpx.Error(w, err)
		return
	}
	p2pKeyVersion := intFromMap(data, "p2p_key_version", scope.Membership.P2PKeyVersion)
	if p2pEnabled && !encryptedPresent {
		httpx.Error(w, httpx.Unprocessable("当前家庭组已开启端到端加密，请使用新版 App 上报。"))
		return
	}
	if !p2pEnabled && encryptedPresent {
		httpx.Error(w, httpx.Unprocessable("当前家庭组未开启端到端加密。"))
		return
	}

	deviceReport, _ := data["device_report"].(map[string]any)
	policy, err := handler.settings.SecurityPolicy(r.Context())
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if err := services.EnforceDeviceReportPolicy(*scope.User, deviceReport, policy); err != nil {
		httpx.Error(w, err)
		return
	}

	diagnostics := sanitizeAddressDiagnostics(mapFromAny(data["address_diagnostics"]))
	diagnosticsJSON := marshalOptional(diagnostics)
	if len(diagnosticsJSON) > handler.cfg.Location.MaxDiagnosticsBytes {
		diagnosticsJSON = diagnosticsJSON[:handler.cfg.Location.MaxDiagnosticsBytes]
	}
	addressMismatch := diagnostics != nil && boolFromMap(diagnostics, "mismatch")

	locationID := int64FromMap(data, "location_id", 0)
	if locationID > 0 {
		handler.updateDiagnostics(w, r, scope, locationID, p2pEnabled, encryptedPayloadJSON, p2pKeyVersion, diagnosticsJSON, addressMismatch)
		return
	}
	if p2pEnabled {
		handler.insertEncryptedLocation(w, r, scope, encryptedPayloadJSON, p2pKeyVersion, deviceReport)
		return
	}
	handler.insertPlainLocation(w, r, scope, data, diagnosticsJSON, addressMismatch, deviceReport)
}

func (handler ReportLocationHandler) updateDiagnostics(w http.ResponseWriter, r *http.Request, scope *userScope, locationID int64, p2pEnabled bool, encryptedPayloadJSON string, p2pKeyVersion int, diagnosticsJSON string, addressMismatch bool) {
	tx, err := handler.db.BeginTx(r.Context(), nil)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	defer func() { _ = tx.Rollback() }()

	var createdAt time.Time
	err = tx.QueryRowContext(r.Context(), `
SELECT created_at
FROM locations
WHERE id = ? AND user_id = ? AND group_name = ?
LIMIT 1`, locationID, scope.User.ID, scope.Membership.GroupName).Scan(&createdAt)
	if err == sql.ErrNoRows {
		httpx.Error(w, httpx.APIError{Status: http.StatusNotFound, Message: "位置记录不存在或无权更新。"})
		return
	}
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if createdAt.Before(time.Now().Add(-time.Duration(handler.cfg.Location.DiagnosticsUpdateSeconds) * time.Second)) {
		httpx.Error(w, httpx.Unprocessable("位置诊断更新已过期。"))
		return
	}

	if p2pEnabled {
		_, err = tx.ExecContext(r.Context(), `
UPDATE locations
SET encryption_mode = 'p2p-v1', encrypted_payload = ?, p2p_key_version = ?, address_diagnostics = NULL, address_mismatch = 0
WHERE id = ? AND user_id = ? AND group_name = ?`,
			encryptedPayloadJSON, p2pKeyVersion, locationID, scope.User.ID, scope.Membership.GroupName)
		if err == nil {
			_, err = tx.ExecContext(r.Context(), `
UPDATE latest_group_locations
SET encryption_mode = 'p2p-v1', encrypted_payload = ?, p2p_key_version = ?, address_diagnostics = NULL, address_mismatch = 0
WHERE user_id = ? AND group_name = ? AND latest_location_id = ?`,
				encryptedPayloadJSON, p2pKeyVersion, scope.User.ID, scope.Membership.GroupName, locationID)
		}
	} else {
		_, err = tx.ExecContext(r.Context(), `
UPDATE locations
SET address_diagnostics = ?, address_mismatch = ?
WHERE id = ? AND user_id = ? AND group_name = ?`,
			nullString(diagnosticsJSON), addressMismatch, locationID, scope.User.ID, scope.Membership.GroupName)
		if err == nil {
			_, err = tx.ExecContext(r.Context(), `
UPDATE latest_group_locations
SET address_diagnostics = ?, address_mismatch = ?
WHERE user_id = ? AND group_name = ? AND latest_location_id = ?`,
				nullString(diagnosticsJSON), addressMismatch, scope.User.ID, scope.Membership.GroupName, locationID)
		}
	}
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if err := tx.Commit(); err != nil {
		httpx.Error(w, err)
		return
	}
	httpx.OK(w, map[string]any{
		"ok":          true,
		"message":     "位置诊断已更新。",
		"location_id": locationID,
		"reported_at": nowString(),
	})
}

func (handler ReportLocationHandler) insertEncryptedLocation(w http.ResponseWriter, r *http.Request, scope *userScope, encryptedPayloadJSON string, p2pKeyVersion int, deviceReport map[string]any) {
	tx, err := handler.db.BeginTx(r.Context(), nil)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	defer func() { _ = tx.Rollback() }()

	role := services.NormalizeRole(scope.Membership.Role)
	result, err := tx.ExecContext(r.Context(), `
INSERT INTO locations
	(user_id, group_name, role, latitude, longitude, encryption_mode, encrypted_payload, p2p_key_version, user_agent)
VALUES
	(?, ?, ?, 0, 0, 'p2p-v1', ?, ?, ?)`,
		scope.User.ID, scope.Membership.GroupName, role, encryptedPayloadJSON, p2pKeyVersion, truncateString(r.UserAgent(), 255))
	if err != nil {
		httpx.Error(w, err)
		return
	}
	locationID, _ := result.LastInsertId()
	_, err = tx.ExecContext(r.Context(), `
INSERT INTO latest_group_locations
	(user_id, group_name, role, latitude, longitude, latest_location_id, encryption_mode, encrypted_payload, p2p_key_version, updated_at)
VALUES
	(?, ?, ?, 0, 0, ?, 'p2p-v1', ?, ?, NOW())
ON DUPLICATE KEY UPDATE
	group_name = VALUES(group_name),
	role = VALUES(role),
	latitude = 0,
	longitude = 0,
	latest_location_id = VALUES(latest_location_id),
	encryption_mode = VALUES(encryption_mode),
	encrypted_payload = VALUES(encrypted_payload),
	p2p_key_version = VALUES(p2p_key_version),
	updated_at = NOW()`,
		scope.User.ID, scope.Membership.GroupName, role, locationID, encryptedPayloadJSON, p2pKeyVersion)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	handler.storeDeviceReportTx(r.Context(), tx, scope.User.ID, deviceReport, locationID, scope.Membership.GroupName)
	if err := tx.Commit(); err != nil {
		httpx.Error(w, err)
		return
	}
	handler.afterReport(r, scope, locationID, nil, false, "上报端到端加密位置", map[string]any{"location_id": locationID, "p2p_key_version": p2pKeyVersion})
	httpx.OK(w, map[string]any{"ok": true, "message": "加密位置已上报。", "location_id": locationID, "reported_at": nowString()})
}

func (handler ReportLocationHandler) insertPlainLocation(w http.ResponseWriter, r *http.Request, scope *userScope, data map[string]any, diagnosticsJSON string, addressMismatch bool, deviceReport map[string]any) {
	latitude, okLat := floatFromMap(data, "latitude")
	longitude, okLng := floatFromMap(data, "longitude")
	if !okLat || !okLng {
		httpx.Error(w, httpx.Unprocessable("定位数据不完整。"))
		return
	}
	if latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180 {
		httpx.Error(w, httpx.Unprocessable("定位经纬度不正确。"))
		return
	}
	altitude, hasAltitude := floatFromMap(data, "altitude")
	accuracy, hasAccuracy := floatFromMap(data, "accuracy")
	heading, hasHeading := floatFromMap(data, "heading")
	speed, hasSpeed := floatFromMap(data, "speed")
	if err := handler.validateMeasurements(optionalFloat(hasAccuracy, accuracy), optionalFloat(hasHeading, heading), optionalFloat(hasSpeed, speed), optionalFloat(hasAltitude, altitude)); err != nil {
		httpx.Error(w, err)
		return
	}
	if err := handler.assertPlausible(r, scope, latitude, longitude, optionalFloat(hasAccuracy, accuracy)); err != nil {
		httpx.Error(w, err)
		return
	}

	metaJSON := sanitizedLocationMeta(data)
	tx, err := handler.db.BeginTx(r.Context(), nil)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	defer func() { _ = tx.Rollback() }()

	role := services.NormalizeRole(scope.Membership.Role)
	result, err := tx.ExecContext(r.Context(), `
INSERT INTO locations
	(user_id, group_name, role, latitude, longitude, altitude, accuracy, heading, speed, location_meta, address_diagnostics, address_mismatch, user_agent)
VALUES
	(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		scope.User.ID,
		scope.Membership.GroupName,
		role,
		latitude,
		longitude,
		nullableNumber(hasAltitude, altitude),
		nullableNumber(hasAccuracy, accuracy),
		nullableNumber(hasHeading, heading),
		nullableNumber(hasSpeed, speed),
		nullString(metaJSON),
		nullString(diagnosticsJSON),
		addressMismatch,
		truncateString(r.UserAgent(), 255),
	)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	locationID, _ := result.LastInsertId()
	_, err = tx.ExecContext(r.Context(), `
INSERT INTO latest_group_locations
	(user_id, group_name, role, latitude, longitude, altitude, accuracy, heading, speed, location_meta, latest_location_id, address_diagnostics, address_mismatch, updated_at)
VALUES
	(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
ON DUPLICATE KEY UPDATE
	group_name = VALUES(group_name),
	role = VALUES(role),
	latitude = VALUES(latitude),
	longitude = VALUES(longitude),
	altitude = VALUES(altitude),
	accuracy = VALUES(accuracy),
	heading = VALUES(heading),
	speed = VALUES(speed),
	location_meta = VALUES(location_meta),
	latest_location_id = VALUES(latest_location_id),
	address_diagnostics = VALUES(address_diagnostics),
	address_mismatch = VALUES(address_mismatch),
	updated_at = NOW()`,
		scope.User.ID,
		scope.Membership.GroupName,
		role,
		latitude,
		longitude,
		nullableNumber(hasAltitude, altitude),
		nullableNumber(hasAccuracy, accuracy),
		nullableNumber(hasHeading, heading),
		nullableNumber(hasSpeed, speed),
		nullString(metaJSON),
		locationID,
		nullString(diagnosticsJSON),
		addressMismatch,
	)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	handler.storeDeviceReportTx(r.Context(), tx, scope.User.ID, deviceReport, locationID, scope.Membership.GroupName)
	if handler.cfg.Location.HistoryLimit > 0 {
		_, _ = tx.ExecContext(r.Context(), fmt.Sprintf(`
DELETE FROM locations
WHERE id NOT IN (
	SELECT id FROM (
		SELECT id FROM locations ORDER BY id DESC LIMIT %d
	) keep_rows
)`, handler.cfg.Location.HistoryLimit))
	}
	if err := tx.Commit(); err != nil {
		httpx.Error(w, err)
		return
	}
	handler.afterReport(r, scope, locationID, optionalFloat(hasAccuracy, accuracy), addressMismatch, "上报位置", map[string]any{"location_id": locationID, "accuracy": nullableNumber(hasAccuracy, accuracy), "address_mismatch": addressMismatch})
	httpx.OK(w, map[string]any{"ok": true, "message": "位置已上报。", "location_id": locationID, "reported_at": nowString()})
}

func (handler ReportLocationHandler) validateMeasurements(accuracy *float64, heading *float64, speed *float64, altitude *float64) error {
	if altitude != nil && (*altitude < -500 || *altitude > 12000) {
		return httpx.Unprocessable("定位高度异常，已拒绝上报。")
	}
	if accuracy != nil && (*accuracy < 0 || *accuracy > handler.cfg.Location.MaxAccuracyMeters) {
		return httpx.Unprocessable("定位精度异常，已拒绝上报。")
	}
	if heading != nil && (*heading < 0 || *heading > 360) {
		return httpx.Unprocessable("定位方向异常，已拒绝上报。")
	}
	if speed != nil && (*speed < 0 || *speed > handler.cfg.Location.MaxSpeedMPS) {
		return httpx.Unprocessable("定位速度异常，已拒绝上报。")
	}
	return nil
}

func (handler ReportLocationHandler) assertPlausible(r *http.Request, scope *userScope, latitude float64, longitude float64, accuracy *float64) error {
	if math.Abs(latitude) < 0.000001 && math.Abs(longitude) < 0.000001 {
		return httpx.Unprocessable("定位坐标异常，已拒绝上报。")
	}
	var previousLatitude float64
	var previousLongitude float64
	var previousAccuracy sql.NullFloat64
	var createdAt time.Time
	err := handler.db.QueryRowContext(r.Context(), `
SELECT latitude, longitude, accuracy, created_at
FROM locations
WHERE user_id = ? AND group_name = ?
ORDER BY created_at DESC, id DESC
LIMIT 1`, scope.User.ID, scope.Membership.GroupName).Scan(&previousLatitude, &previousLongitude, &previousAccuracy, &createdAt)
	if err == sql.ErrNoRows {
		return nil
	}
	if err != nil {
		return err
	}
	elapsed := time.Since(createdAt).Seconds()
	if elapsed >= 0 && elapsed < float64(handler.cfg.Location.MinReportSeconds) {
		return httpx.APIError{Status: http.StatusTooManyRequests, Message: "上报过于频繁，请稍后再试。"}
	}
	if elapsed <= 0 {
		return nil
	}
	distance := haversineMeters(previousLatitude, previousLongitude, latitude, longitude)
	previousAccuracyValue := 0.0
	if previousAccuracy.Valid && previousAccuracy.Float64 > 0 {
		previousAccuracyValue = previousAccuracy.Float64
	}
	currentAccuracy := 0.0
	if accuracy != nil && *accuracy > 0 {
		currentAccuracy = *accuracy
	}
	effectiveDistance := math.Max(0, distance-previousAccuracyValue-currentAccuracy-1000)
	speed := effectiveDistance / elapsed
	if speed > handler.cfg.Location.MaxReasonableTravelMPS {
		id := scope.User.ID
		_ = handler.users.RecordLog(r.Context(), &id, scope.Membership.GroupName, "location_jump_anomaly", "位置变化异常", map[string]any{
			"previous_latitude":   previousLatitude,
			"previous_longitude":  previousLongitude,
			"latitude":            latitude,
			"longitude":           longitude,
			"distance_meters":     math.Round(distance*100) / 100,
			"elapsed_seconds":     int(elapsed),
			"effective_speed_mps": math.Round(speed*100) / 100,
			"previous_accuracy":   previousAccuracyValue,
			"current_accuracy":    currentAccuracy,
		}, httpx.ClientIP(r), r.UserAgent())
	}
	return nil
}

func (handler ReportLocationHandler) storeDeviceReportTx(ctx context.Context, tx *sql.Tx, userID int64, report map[string]any, locationID int64, groupName string) {
	if len(report) == 0 {
		return
	}
	report["report_kind"] = "device_integrity"
	report["from_location_report"] = true
	report["location_id"] = locationID
	report["group_name"] = groupName
	report["reported_at"] = nowString()
	payload, err := json.Marshal(report)
	if err != nil || len(payload) > 200000 {
		return
	}
	_, _ = tx.ExecContext(ctx, "INSERT INTO environment_reports (user_id, report_json) VALUES (?, ?)", userID, string(payload))
}

func (handler ReportLocationHandler) afterReport(r *http.Request, scope *userScope, locationID int64, accuracy *float64, addressMismatch bool, message string, meta map[string]any) {
	_ = handler.users.TouchPresence(r.Context(), scope.User.ID, scope.Membership.GroupName, r.UserAgent(), httpx.ClientIP(r))
	id := scope.User.ID
	_ = handler.users.RecordLog(r.Context(), &id, scope.Membership.GroupName, "location_report", message, meta, httpx.ClientIP(r), r.UserAgent())
}

func encryptedPayload(value any) (string, bool, error) {
	if value == nil || value == "" {
		return "", false, nil
	}
	payload, ok := value.(map[string]any)
	if !ok {
		return "", false, httpx.Unprocessable("加密定位数据格式不正确。")
	}
	for _, field := range []string{"iv", "ciphertext"} {
		text, ok := payload[field].(string)
		if !ok || strings.TrimSpace(text) == "" {
			return "", false, httpx.Unprocessable("加密定位数据不完整。")
		}
	}
	raw, err := json.Marshal(payload)
	if err != nil || len(raw) > 500000 {
		return "", false, httpx.Unprocessable("加密定位数据过大。")
	}
	return string(raw), true, nil
}

func sanitizedLocationMeta(data map[string]any) string {
	meta := map[string]any{}
	addString(meta, "provider", stringFromMap(data, "location_provider", 40))
	addString(meta, "location_time", stringFromMap(data, "location_time", 40))
	addString(meta, "coordinate_system", stringFromMap(data, "location_coordinate_system", 16))
	if value, ok := data["location_mock_provider"]; ok {
		meta["mock_provider"] = truthyAny(value)
	}
	if value, ok := floatFromMap(data, "vertical_accuracy"); ok && value >= 0 && value <= 10000 {
		meta["vertical_accuracy"] = value
	}
	if value, ok := floatFromMap(data, "bearing_accuracy"); ok && value >= 0 && value <= 360 {
		meta["bearing_accuracy"] = value
	}
	if value, ok := floatFromMap(data, "speed_accuracy"); ok && value >= 0 && value <= 1000 {
		meta["speed_accuracy"] = value
	}
	return marshalOptional(meta)
}

func sanitizeAddressDiagnostics(diagnostics map[string]any) map[string]any {
	if len(diagnostics) == 0 {
		return nil
	}
	sources := make([]map[string]any, 0, 6)
	if rawSources, ok := diagnostics["sources"].([]any); ok {
		for _, rawSource := range rawSources {
			source, ok := rawSource.(map[string]any)
			if !ok {
				continue
			}
			sourceType := truncateString(fmt.Sprint(source["type"]), 24)
			if sourceType != "gps" && sourceType != "ip" && sourceType != "webrtc" {
				continue
			}
			clean := map[string]any{"type": sourceType}
			for key, limit := range map[string]int{
				"name": 40, "provider": 80, "source": 80, "source_region": 40, "coordinate_system": 16,
				"address": 600, "detail": 240, "poi": 120, "city": 80, "region": 80, "country": 80,
				"ip": 80, "ipv4": 80, "ipv6": 80, "server_ip": 80, "stun_server": 120, "stun_label": 80,
				"stun_scope": 20, "candidate_type": 24, "asn": 80, "isp": 120, "org": 120, "carrier": 120,
			} {
				addString(clean, key, truncateString(fmt.Sprint(source[key]), limit))
			}
			clean["domestic_source"] = truthyAny(source["domestic_source"])
			clean["mobile_network"] = truthyAny(source["mobile_network"])
			if value, ok := numericAny(source["latitude"]); ok && value >= -90 && value <= 90 {
				clean["latitude"] = value
			} else {
				clean["latitude"] = nil
			}
			if value, ok := numericAny(source["longitude"]); ok && value >= -180 && value <= 180 {
				clean["longitude"] = value
			} else {
				clean["longitude"] = nil
			}
			clean["variants"] = sanitizeProbeList(source["variants"], "ip_variant")
			clean["candidates"] = sanitizeProbeList(source["candidates"], "webrtc_candidate")
			sources = append(sources, clean)
			if len(sources) >= 6 {
				break
			}
		}
	}
	mismatch := diagnosticsPlaceMismatch(sources)
	mobileUncertain := diagnosticsMobileIPUncertain(sources)
	if mobileUncertain {
		for _, source := range sources {
			if source["type"] == "ip" {
				source["mobile_network_uncertain"] = true
			}
		}
	}
	result := map[string]any{
		"mismatch":                    mismatch,
		"mobile_ip_uncertain":         mobileUncertain && !mismatch,
		"checked_at":                  firstNonEmptyString(stringFromMap(diagnostics, "checked_at", 40), nowString()),
		"complete":                    truthyAny(diagnostics["complete"]),
		"preferred_source":            stringFromMap(diagnostics, "preferred_source", 24),
		"preferred_address":           stringFromMap(diagnostics, "preferred_address", 600),
		"preferred_city":              stringFromMap(diagnostics, "preferred_city", 80),
		"preferred_poi":               stringFromMap(diagnostics, "preferred_poi", 120),
		"preferred_coordinate_system": stringFromMap(diagnostics, "preferred_coordinate_system", 16),
		"sources":                     sources,
	}
	if value, ok := floatFromMap(diagnostics, "preferred_latitude"); ok && value >= -90 && value <= 90 {
		result["preferred_latitude"] = value
	} else {
		result["preferred_latitude"] = nil
	}
	if value, ok := floatFromMap(diagnostics, "preferred_longitude"); ok && value >= -180 && value <= 180 {
		result["preferred_longitude"] = value
	} else {
		result["preferred_longitude"] = nil
	}
	return result
}

func sanitizeProbeList(value any, kind string) []map[string]any {
	items, ok := value.([]any)
	if !ok {
		return []map[string]any{}
	}
	clean := make([]map[string]any, 0, 12)
	for _, raw := range items {
		item, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		entry := map[string]any{}
		if kind == "ip_variant" {
			for key, limit := range map[string]int{"label": 24, "ip": 80, "address": 240, "city": 80, "region": 80, "country": 80, "provider": 80, "source": 80, "source_region": 40, "asn": 80, "isp": 120, "org": 120, "carrier": 120} {
				addString(entry, key, truncateString(fmt.Sprint(item[key]), limit))
			}
			entry["domestic_source"] = truthyAny(item["domestic_source"])
			entry["mobile_network"] = truthyAny(item["mobile_network"])
			if number, ok := numericAny(item["latitude"]); ok && number >= -90 && number <= 90 {
				entry["latitude"] = number
			} else {
				entry["latitude"] = nil
			}
			if number, ok := numericAny(item["longitude"]); ok && number >= -180 && number <= 180 {
				entry["longitude"] = number
			} else {
				entry["longitude"] = nil
			}
		} else {
			for key, limit := range map[string]int{"ip": 80, "candidate_type": 24, "stun_server": 120, "stun_label": 80, "stun_scope": 20} {
				addString(entry, key, truncateString(fmt.Sprint(item[key]), limit))
			}
		}
		clean = append(clean, entry)
		if len(clean) >= 12 {
			break
		}
	}
	return clean
}

func diagnosticsPlaceMismatch(sources []map[string]any) bool {
	trusted := filterSources(sources, "gps")
	if len(trusted) < 2 {
		return false
	}
	for _, field := range []string{"country", "region"} {
		if distinctCompareValues(trusted, field) > 1 {
			return true
		}
	}
	if distinctCompareValues(trusted, "city") > 1 && !diagnosticsIPWebRTCSameCitySameRegion(sources) {
		return true
	}
	return false
}

func diagnosticsIPWebRTCSameCitySameRegion(sources []map[string]any) bool {
	gps := sourceByType(sources, "gps")
	ip := sourceByType(sources, "ip")
	webrtc := sourceByType(sources, "webrtc")
	if gps == nil || ip == nil || webrtc == nil {
		return false
	}
	ipCity := compareValue(ip["city"])
	webrtcCity := compareValue(webrtc["city"])
	if ipCity == "" || webrtcCity == "" || ipCity != webrtcCity {
		return false
	}
	for _, field := range []string{"country", "region"} {
		values := map[string]bool{}
		for _, source := range []map[string]any{gps, ip, webrtc} {
			if value := compareValue(source[field]); value != "" {
				values[value] = true
			}
		}
		if len(values) > 1 {
			return false
		}
	}
	return true
}

func diagnosticsMobileIPUncertain(sources []map[string]any) bool {
	ip := sourceByType(sources, "ip")
	if ip == nil || !sourceIsMobileNetwork(ip) {
		return false
	}
	for _, source := range sources {
		if source["type"] != "gps" {
			continue
		}
		for _, field := range []string{"country", "region"} {
			ipValue := compareValue(ip[field])
			gpsValue := compareValue(source[field])
			if ipValue != "" && gpsValue != "" && ipValue != gpsValue {
				return false
			}
		}
		ipCity := compareValue(ip["city"])
		gpsCity := compareValue(source["city"])
		if ipCity != "" && gpsCity != "" && ipCity != gpsCity {
			return true
		}
	}
	return false
}

func sourceIsMobileNetwork(source map[string]any) bool {
	if truthyAny(source["mobile_network"]) {
		return true
	}
	text := strings.ToLower(fmt.Sprint(source["asn"], " ", source["isp"], " ", source["org"], " ", source["carrier"], " ", source["provider"]))
	return strings.Contains(text, "china mobile") || strings.Contains(text, "cmnet") || strings.Contains(text, "cmi") || strings.Contains(text, "中国移动") || strings.Contains(text, "移动")
}

func sourceByType(sources []map[string]any, sourceType string) map[string]any {
	for _, source := range sources {
		if source["type"] == sourceType {
			return source
		}
	}
	return nil
}

func filterSources(sources []map[string]any, sourceType string) []map[string]any {
	filtered := make([]map[string]any, 0)
	for _, source := range sources {
		if source["type"] == sourceType {
			filtered = append(filtered, source)
		}
	}
	return filtered
}

func distinctCompareValues(sources []map[string]any, field string) int {
	values := map[string]bool{}
	for _, source := range sources {
		if value := compareValue(source[field]); value != "" {
			values[value] = true
		}
	}
	return len(values)
}

var compareSuffixPattern = regexp.MustCompile(`(壮族自治区|回族自治区|维吾尔自治区|特别行政区|自治区|自治州|地区|省|市|盟|州)$`)

func compareValue(value any) string {
	text := strings.ToLower(strings.Join(strings.Fields(fmt.Sprint(value)), ""))
	if text == "" || text == "<nil>" {
		return ""
	}
	text = strings.ReplaceAll(text, "中华人民共和国", "中国")
	return compareSuffixPattern.ReplaceAllString(text, "")
}

func haversineMeters(lat1 float64, lon1 float64, lat2 float64, lon2 float64) float64 {
	const radius = 6371000.0
	dLat := (lat2 - lat1) * math.Pi / 180
	dLon := (lon2 - lon1) * math.Pi / 180
	a := math.Sin(dLat/2)*math.Sin(dLat/2) + math.Cos(lat1*math.Pi/180)*math.Cos(lat2*math.Pi/180)*math.Sin(dLon/2)*math.Sin(dLon/2)
	return radius * 2 * math.Atan2(math.Sqrt(a), math.Sqrt(math.Max(0, 1-a)))
}

func mapFromAny(value any) map[string]any {
	if result, ok := value.(map[string]any); ok {
		return result
	}
	return nil
}

func stringFromMap(data map[string]any, key string, limit int) string {
	if data == nil {
		return ""
	}
	return truncateString(fmt.Sprint(data[key]), limit)
}

func floatFromMap(data map[string]any, key string) (float64, bool) {
	if data == nil {
		return 0, false
	}
	return numericAny(data[key])
}

func intFromMap(data map[string]any, key string, fallback int) int {
	value, ok := numericAny(data[key])
	if !ok {
		return fallback
	}
	return int(value)
}

func int64FromMap(data map[string]any, key string, fallback int64) int64 {
	value, ok := numericAny(data[key])
	if !ok {
		return fallback
	}
	return int64(value)
}

func numericAny(value any) (float64, bool) {
	switch typed := value.(type) {
	case float64:
		return typed, true
	case float32:
		return float64(typed), true
	case int:
		return float64(typed), true
	case int64:
		return float64(typed), true
	case json.Number:
		number, err := typed.Float64()
		return number, err == nil
	case string:
		number, err := strconv.ParseFloat(strings.TrimSpace(typed), 64)
		return number, err == nil
	default:
		return 0, false
	}
}

func boolFromMap(data map[string]any, key string) bool {
	if data == nil {
		return false
	}
	return truthyAny(data[key])
}

func truthyAny(value any) bool {
	switch typed := value.(type) {
	case bool:
		return typed
	case string:
		switch strings.ToLower(strings.TrimSpace(typed)) {
		case "1", "true", "yes", "on":
			return true
		}
	case float64:
		return typed != 0
	case int:
		return typed != 0
	}
	return false
}

func truncateString(value string, limit int) string {
	value = strings.TrimSpace(value)
	if value == "<nil>" {
		return ""
	}
	runes := []rune(value)
	if len(runes) > limit {
		return string(runes[:limit])
	}
	return value
}

func marshalOptional(value map[string]any) string {
	if len(value) == 0 {
		return ""
	}
	raw, err := json.Marshal(value)
	if err != nil {
		return ""
	}
	return string(raw)
}

func addString(target map[string]any, key string, value string) {
	if value != "" {
		target[key] = value
	}
}

func firstNonEmptyString(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return value
		}
	}
	return ""
}

func optionalFloat(valid bool, value float64) *float64 {
	if !valid {
		return nil
	}
	return &value
}

func nullableNumber(valid bool, value float64) any {
	if !valid {
		return nil
	}
	return value
}

func nullString(value string) any {
	if strings.TrimSpace(value) == "" {
		return nil
	}
	return value
}

func nowString() string {
	return time.Now().Format("2006-01-02 15:04:05")
}
