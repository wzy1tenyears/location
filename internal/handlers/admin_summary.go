package handlers

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"

	"familylocation/location-v3/internal/config"
	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/models"
	"familylocation/location-v3/internal/repositories"
	"familylocation/location-v3/internal/services"
	"familylocation/location-v3/internal/session"
)

type AdminSummaryHandler struct {
	cfg      config.Config
	db       *sql.DB
	sessions session.Reader
	settings repositories.SettingRepository
}

func NewAdminSummaryHandler(cfg config.Config, db *sql.DB, sessions session.Reader) AdminSummaryHandler {
	return AdminSummaryHandler{
		cfg:      cfg,
		db:       db,
		sessions: sessions,
		settings: repositories.NewSettingRepository(db),
	}
}

func (handler AdminSummaryHandler) Summary(w http.ResponseWriter, r *http.Request) {
	if !handler.sessions.IsAdmin(r) {
		httpx.Error(w, httpx.Unauthorized("请先登录后台。"))
		return
	}
	stats, err := handler.stats(r)
	if err != nil {
		log.Printf("admin summary stats failed: %v", err)
		httpx.Error(w, err)
		return
	}
	groups, err := handler.groups(r)
	if err != nil {
		log.Printf("admin summary groups failed: %v", err)
		httpx.Error(w, err)
		return
	}
	users, err := handler.users(r)
	if err != nil {
		log.Printf("admin summary users failed: %v", err)
		httpx.Error(w, err)
		return
	}
	environmentReports, environmentByUser, err := handler.environmentReports(r)
	if err != nil {
		log.Printf("admin summary environment reports failed: %v", err)
		httpx.Error(w, err)
		return
	}
	locations, err := handler.locations(r, environmentByUser)
	if err != nil {
		log.Printf("admin summary locations failed: %v", err)
		httpx.Error(w, err)
		return
	}
	memberships, err := handler.memberships(r)
	if err != nil {
		log.Printf("admin summary memberships failed: %v", err)
		httpx.Error(w, err)
		return
	}
	announcement, err := handler.announcement(r)
	if err != nil {
		log.Printf("admin summary announcement failed: %v", err)
		httpx.Error(w, err)
		return
	}
	invites, err := handler.invites(r)
	if err != nil {
		log.Printf("admin summary invites failed: %v", err)
		httpx.Error(w, err)
		return
	}
	devices, err := handler.devices(r)
	if err != nil {
		log.Printf("admin summary devices failed: %v", err)
		httpx.Error(w, err)
		return
	}
	logs, logLocations, logFilters, logTypes, logTotal, err := handler.logs(r)
	if err != nil {
		log.Printf("admin summary logs failed: %v", err)
		httpx.Error(w, err)
		return
	}
	tickets, err := handler.tickets(r)
	if err != nil {
		log.Printf("admin summary tickets failed: %v", err)
		httpx.Error(w, err)
		return
	}
	securitySettings, err := handler.settings.SecurityPolicy(r.Context())
	if err != nil {
		log.Printf("admin summary security settings failed: %v", err)
		httpx.Error(w, err)
		return
	}

	httpx.OK(w, map[string]any{
		"ok":                  true,
		"admin_username":      handler.cfg.Auth.AdminUsername,
		"stats":               stats,
		"groups":              groups,
		"memberships":         memberships,
		"security_settings":   securitySettings,
		"users":               users,
		"locations":           locations,
		"environment_reports": environmentReports,
		"devices":             devices,
		"logs":                logs,
		"log_locations":       logLocations,
		"log_filters":         logFilters,
		"log_types":           logTypes,
		"log_total":           logTotal,
		"announcement":        announcement,
		"invites":             invites,
		"tickets":             tickets,
		"server_time":         nowString(),
	})
}

func (handler AdminSummaryHandler) stats(r *http.Request) (map[string]any, error) {
	count := func(query string, args ...any) (int, error) {
		var value int
		err := handler.db.QueryRowContext(r.Context(), query, args...).Scan(&value)
		return value, err
	}
	users, err := count("SELECT COUNT(*) FROM users")
	if err != nil {
		return nil, err
	}
	activeUsers, err := count("SELECT COUNT(*) FROM users WHERE is_active = 1")
	if err != nil {
		return nil, err
	}
	groups, err := count("SELECT COUNT(*) FROM family_groups")
	if err != nil {
		return nil, err
	}
	locations, err := count("SELECT COUNT(*) FROM locations")
	if err != nil {
		return nil, err
	}
	onlineUsers, err := count("SELECT COUNT(*) FROM user_presence WHERE last_seen_at >= ?", time.Now().Add(-90*time.Second))
	if err != nil {
		return nil, err
	}
	openTickets, err := count("SELECT COUNT(*) FROM support_tickets WHERE status <> 'closed'")
	if err != nil {
		return nil, err
	}
	return map[string]any{
		"users":        users,
		"active_users": activeUsers,
		"groups":       groups,
		"locations":    locations,
		"online_users": onlineUsers,
		"open_tickets": openTickets,
	}, nil
}

func (handler AdminSummaryHandler) groups(r *http.Request) ([]map[string]any, error) {
	rows, err := handler.db.QueryContext(r.Context(), `
SELECT fg.id, fg.group_name, fg.display_name, COALESCE(fg.group_code, ''), COALESCE(fg.owner_user_id, 0),
	fg.created_at, fg.updated_at, COUNT(ug.id)
FROM family_groups fg
LEFT JOIN user_groups ug ON ug.group_name = fg.group_name
GROUP BY fg.id, fg.group_name, fg.display_name, fg.group_code, fg.owner_user_id, fg.created_at, fg.updated_at
ORDER BY fg.display_name ASC, fg.group_name ASC
LIMIT 100`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make([]map[string]any, 0)
	for rows.Next() {
		var id, ownerID int64
		var name, displayName, code string
		var createdAt, updatedAt time.Time
		var memberCount int
		if err := rows.Scan(&id, &name, &displayName, &code, &ownerID, &createdAt, &updatedAt, &memberCount); err != nil {
			return nil, err
		}
		if displayName == "" {
			displayName = name
		}
		result = append(result, map[string]any{
			"id": id, "group_name": name, "display_name": displayName, "group_code": code,
			"owner_user_id": ownerID, "member_count": memberCount,
			"created_at": nowFormat(createdAt), "updated_at": nowFormat(updatedAt),
		})
	}
	return result, rows.Err()
}

func (handler AdminSummaryHandler) users(r *http.Request) ([]map[string]any, error) {
	rows, err := handler.db.QueryContext(r.Context(), `
SELECT u.id, u.username, u.display_name, u.group_name, u.role, u.is_active, u.debug_mode,
	u.report_interval_seconds, up.last_seen_at, COALESCE(up.last_group_name, ''), COALESCE(up.last_ip, '')
FROM users u
LEFT JOIN user_presence up ON up.user_id = u.id
ORDER BY u.id DESC
LIMIT 100`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	cutoff := time.Now().Add(-90 * time.Second)
	result := make([]map[string]any, 0)
	for rows.Next() {
		var id int64
		var username, displayName, groupName, role, lastGroupName, lastIP string
		var active, debug bool
		var interval int
		var lastSeen sql.NullTime
		if err := rows.Scan(&id, &username, &displayName, &groupName, &role, &active, &debug, &interval, &lastSeen, &lastGroupName, &lastIP); err != nil {
			return nil, err
		}
		result = append(result, map[string]any{
			"id": id, "username": username, "display_name": displayName, "group_name": groupName,
			"role": services.NormalizeRole(role), "role_label": services.RoleLabel(role),
			"is_active": active, "debug_mode": debug, "report_interval_seconds": interval,
			"last_seen_at": nullableTimeString(lastSeen), "last_group_name": lastGroupName, "last_ip": lastIP,
			"online": lastSeen.Valid && lastSeen.Time.After(cutoff),
		})
	}
	return result, rows.Err()
}

type reportRow struct {
	UserID     int64
	ReportJSON string
	CreatedAt  time.Time
}

func (handler AdminSummaryHandler) environmentReports(r *http.Request) ([]map[string]any, map[int64]map[string]any, error) {
	latest, err := handler.reportRows(r, "")
	if err != nil {
		return nil, nil, err
	}
	devices, err := handler.reportRows(r, `%"report_kind":"device_integrity"%`)
	if err != nil {
		return nil, nil, err
	}
	apps, err := handler.reportRows(r, `%"installed_apps"%`)
	if err != nil {
		return nil, nil, err
	}
	userIDs := map[int64]bool{}
	for id := range latest {
		userIDs[id] = true
	}
	for id := range devices {
		userIDs[id] = true
	}
	for id := range apps {
		userIDs[id] = true
	}
	byUser := map[int64]map[string]any{}
	list := make([]map[string]any, 0, len(userIDs))
	for userID := range userIDs {
		deviceRow, ok := devices[userID]
		if !ok {
			deviceRow, ok = latest[userID]
		}
		if !ok {
			deviceRow = apps[userID]
		}
		var appRow *reportRow
		if value, ok := apps[userID]; ok {
			copy := value
			appRow = &copy
		}
		payload := environmentReportPayload(deviceRow, appRow)
		byUser[userID] = payload
		list = append(list, payload)
	}
	return list, byUser, nil
}

func (handler AdminSummaryHandler) reportRows(r *http.Request, like string) (map[int64]reportRow, error) {
	query := `
SELECT er.user_id, er.report_json, er.created_at
FROM environment_reports er
INNER JOIN (
	SELECT user_id, MAX(id) AS id
	FROM environment_reports`
	args := []any{}
	if like != "" {
		query += " WHERE report_json LIKE ?"
		args = append(args, like)
	}
	query += `
	GROUP BY user_id
) latest ON latest.id = er.id
ORDER BY er.created_at DESC
LIMIT 200`
	rows, err := handler.db.QueryContext(r.Context(), query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := map[int64]reportRow{}
	for rows.Next() {
		var row reportRow
		if err := rows.Scan(&row.UserID, &row.ReportJSON, &row.CreatedAt); err != nil {
			return nil, err
		}
		result[row.UserID] = row
	}
	return result, rows.Err()
}

func environmentReportPayload(deviceRow reportRow, appsRow *reportRow) map[string]any {
	report := decodeMap(deviceRow.ReportJSON)
	appReport := report
	appCreated := ""
	if appsRow != nil {
		appReport = decodeMap(appsRow.ReportJSON)
		appCreated = nowFormat(appsRow.CreatedAt)
	}
	rawApps, _ := appReport["installed_apps"].([]any)
	installedApps := make([]map[string]any, 0)
	for index, raw := range rawApps {
		if index >= 120 {
			break
		}
		app, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		installedApps = append(installedApps, map[string]any{
			"label":        firstMapString(app, "label", "app_name", "name"),
			"package_name": firstMapString(app, "package_name", "package"),
			"version_name": firstMapString(app, "version_name"),
			"system":       truthyAny(app["system"]),
		})
	}
	safeReport := report
	if rawApps != nil {
		safeReport["installed_apps"] = installedApps
		safeReport["installed_apps_truncated"] = len(rawApps) > len(installedApps)
	}
	return map[string]any{
		"user_id":                  deviceRow.UserID,
		"created_at":               nowFormat(deviceRow.CreatedAt),
		"device_report_created_at": nowFormat(deviceRow.CreatedAt),
		"apps_report_created_at":   appCreated,
		"device": map[string]any{
			"manufacturer": firstMapString(report, "manufacturer"), "brand": firstMapString(report, "brand"),
			"model": firstMapString(report, "model"), "device": firstMapString(report, "device"),
			"product": firstMapString(report, "product"), "board": firstMapString(report, "board"),
			"hardware": firstMapString(report, "hardware"), "fingerprint": firstMapString(report, "fingerprint"),
			"supported_abis":  stringSlice(report["supported_abis"]),
			"android_release": firstMapString(report, "android_release"), "android_sdk": firstMapString(report, "android_sdk"),
			"locale": firstMapString(report, "locale"), "timezone": firstMapString(report, "timezone"),
			"app_version_name": firstMapString(report, "app_version_name"), "app_version_code": firstMapString(report, "app_version_code"),
			"package_name":    firstMapString(report, "package_name"),
			"screen_width_px": intFromAny(report["screen_width_px"]), "screen_height_px": intFromAny(report["screen_height_px"]),
			"screen_density_dpi": intFromAny(report["screen_density_dpi"]),
			"memory_total_bytes": int64FromAny(report["memory_total_bytes"]), "memory_available_bytes": int64FromAny(report["memory_available_bytes"]),
			"storage_total_bytes": int64FromAny(report["storage_total_bytes"]), "storage_available_bytes": int64FromAny(report["storage_available_bytes"]),
			"installed_package_count": intFromAny(report["installed_package_count"]),
			"adb_enabled":             report["adb_enabled"], "root_detected": report["root_detected"],
			"mock_location_enabled": report["mock_location_enabled"], "mock_location_risk": report["mock_location_risk"],
			"accessibility_risk": report["accessibility_risk"], "battery_optimization_ignored": report["battery_optimization_ignored"],
		},
		"installed_apps_count":     len(rawApps),
		"installed_apps_truncated": len(rawApps) > len(installedApps),
		"installed_apps":           installedApps,
		"report":                   safeReport,
	}
}

func (handler AdminSummaryHandler) locations(r *http.Request, environment map[int64]map[string]any) ([]map[string]any, error) {
	rows, err := handler.db.QueryContext(r.Context(), `
SELECT ll.latest_location_id, ll.user_id, u.username, u.display_name, ll.group_name, ug.role,
	ll.latitude, ll.longitude, ll.altitude, ll.accuracy, ll.heading, ll.speed, ll.location_meta,
	ll.address_diagnostics, ll.address_mismatch, COALESCE(ll.encryption_mode, ''), COALESCE(ll.encrypted_payload, ''), COALESCE(ll.p2p_key_version, 0),
	ll.updated_at, ll.updated_at
FROM latest_group_locations ll
INNER JOIN users u ON u.id = ll.user_id
INNER JOIN user_groups ug ON ug.user_id = ll.user_id AND ug.group_name = ll.group_name
WHERE u.is_active = 1
ORDER BY ll.updated_at DESC
LIMIT 100`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make([]map[string]any, 0)
	for rows.Next() {
		var location models.Location
		if err := rows.Scan(
			&location.ID, &location.UserID, &location.Username, &location.DisplayName, &location.GroupName, &location.Role,
			&location.Latitude, &location.Longitude, &location.Altitude, &location.Accuracy, &location.Heading, &location.Speed,
			&location.LocationMeta, &location.AddressDiagnostics, &location.AddressMismatch, &location.EncryptionMode,
			&location.EncryptedPayload, &location.P2PKeyVersion, &location.CreatedAt, &location.UpdatedAt,
		); err != nil {
			return nil, err
		}
		payload := services.LocationPayload(location, 600)
		payload["id"] = location.ID
		if value, ok := environment[location.UserID]; ok {
			payload["environment_report"] = value
		}
		result = append(result, payload)
	}
	return result, rows.Err()
}

func (handler AdminSummaryHandler) memberships(r *http.Request) ([]map[string]any, error) {
	rows, err := handler.db.QueryContext(r.Context(), `
SELECT ug.id, ug.user_id, ug.group_name, ug.role, u.username, u.display_name
FROM user_groups ug
INNER JOIN users u ON u.id = ug.user_id
ORDER BY ug.group_name ASC, u.username ASC
LIMIT 500`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make([]map[string]any, 0)
	for rows.Next() {
		var id, userID int64
		var groupName, role, username, displayName string
		if err := rows.Scan(&id, &userID, &groupName, &role, &username, &displayName); err != nil {
			return nil, err
		}
		result = append(result, map[string]any{"id": id, "user_id": userID, "group_name": groupName, "role": services.NormalizeRole(role), "role_label": services.RoleLabel(role), "username": username, "display_name": displayName})
	}
	return result, rows.Err()
}

func (handler AdminSummaryHandler) announcement(r *http.Request) (any, error) {
	var id int64
	var title, body string
	var active bool
	var version int
	var updatedAt time.Time
	err := handler.db.QueryRowContext(r.Context(), "SELECT id, title, body, is_active, version, updated_at FROM announcements ORDER BY id DESC LIMIT 1").Scan(&id, &title, &body, &active, &version, &updatedAt)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return map[string]any{"id": id, "title": title, "body": body, "is_active": active, "version": version, "updated_at": nowFormat(updatedAt)}, nil
}

func (handler AdminSummaryHandler) invites(r *http.Request) ([]map[string]any, error) {
	rows, err := handler.db.QueryContext(r.Context(), `
SELECT id, code, note, invite_type, allow_group_owner, max_uses, used_count, is_active, COALESCE(assigned_group_name, ''), created_at
FROM invite_codes
ORDER BY id DESC
LIMIT 50`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make([]map[string]any, 0)
	for rows.Next() {
		var id int64
		var code, note, inviteType, assignedGroup string
		var allowOwner, active bool
		var maxUses, usedCount int
		var createdAt time.Time
		if err := rows.Scan(&id, &code, &note, &inviteType, &allowOwner, &maxUses, &usedCount, &active, &assignedGroup, &createdAt); err != nil {
			return nil, err
		}
		label := "纯邀请"
		if inviteType == "group_create" {
			label = "组创建"
		}
		result = append(result, map[string]any{"id": id, "code": code, "note": note, "invite_type": inviteType, "invite_type_label": label, "allow_group_owner": allowOwner, "max_uses": maxUses, "used_count": usedCount, "is_active": active, "assigned_group_name": assignedGroup, "created_at": nowFormat(createdAt)})
	}
	return result, rows.Err()
}

func (handler AdminSummaryHandler) devices(r *http.Request) ([]map[string]any, error) {
	rows, err := handler.db.QueryContext(r.Context(), `
SELECT d.id, d.user_id, d.device_fingerprint, d.browser_fingerprint, d.user_agent, d.first_seen_at, d.last_seen_at, u.username, u.display_name
FROM user_devices d
INNER JOIN users u ON u.id = d.user_id
ORDER BY d.last_seen_at DESC, d.id DESC
LIMIT 200`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make([]map[string]any, 0)
	for rows.Next() {
		var id, userID int64
		var fingerprint, browserFingerprint, userAgent, username, displayName string
		var firstSeen, lastSeen time.Time
		if err := rows.Scan(&id, &userID, &fingerprint, &browserFingerprint, &userAgent, &firstSeen, &lastSeen, &username, &displayName); err != nil {
			return nil, err
		}
		result = append(result, map[string]any{"id": id, "user_id": userID, "username": username, "display_name": displayName, "device_fingerprint": fingerprint, "browser_fingerprint": browserFingerprint, "user_agent": userAgent, "first_seen_at": nowFormat(firstSeen), "last_seen_at": nowFormat(lastSeen)})
	}
	return result, rows.Err()
}

func (handler AdminSummaryHandler) logs(r *http.Request) ([]map[string]any, map[string]any, map[string]any, []string, int, error) {
	filters := map[string]any{
		"group":   strings.TrimSpace(r.URL.Query().Get("log_group")),
		"user_id": maxInt(0, httpx.IntQuery(r, "log_user_id", 0)),
		"type":    strings.TrimSpace(r.URL.Query().Get("log_type")),
		"keyword": strings.TrimSpace(r.URL.Query().Get("log_keyword")),
		"limit":   clampInt(httpx.IntQuery(r, "log_limit", 60), 20, 200),
	}
	where := make([]string, 0)
	args := make([]any, 0)
	if filters["group"].(string) != "" {
		where = append(where, "ul.group_name = ?")
		args = append(args, filters["group"])
	}
	if filters["user_id"].(int) > 0 {
		where = append(where, "ul.user_id = ?")
		args = append(args, filters["user_id"])
	}
	if filters["type"].(string) != "" {
		if filters["type"].(string) == "session" {
			where = append(where, "ul.event_type IN ('online', 'offline')")
		} else {
			where = append(where, "ul.event_type = ?")
			args = append(args, filters["type"])
		}
	}
	if filters["keyword"].(string) != "" {
		where = append(where, "(ul.message LIKE ? OR ul.meta_json LIKE ? OR ul.ip LIKE ? OR ul.user_agent LIKE ? OR u.username LIKE ? OR u.display_name LIKE ?)")
		like := "%" + filters["keyword"].(string) + "%"
		for i := 0; i < 6; i++ {
			args = append(args, like)
		}
	}
	whereSQL := ""
	if len(where) > 0 {
		whereSQL = " WHERE " + strings.Join(where, " AND ")
	}
	var total int
	if err := handler.db.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM user_logs ul LEFT JOIN users u ON u.id = ul.user_id"+whereSQL, args...).Scan(&total); err != nil {
		return nil, nil, nil, nil, 0, err
	}
	queryArgs := append(append([]any{}, args...), filters["limit"])
	rows, err := handler.db.QueryContext(r.Context(), `
SELECT ul.id, ul.user_id, ul.group_name, ul.event_type, ul.message, ul.meta_json, ul.ip, ul.user_agent, ul.created_at, u.username, u.display_name
FROM user_logs ul
LEFT JOIN users u ON u.id = ul.user_id`+whereSQL+`
ORDER BY ul.created_at DESC, ul.id DESC
LIMIT ?`, queryArgs...)
	if err != nil {
		return nil, nil, nil, nil, 0, err
	}
	defer rows.Close()
	logs := make([]map[string]any, 0)
	locationIDs := make([]int64, 0)
	for rows.Next() {
		var id int64
		var userID sql.NullInt64
		var groupName, eventType, message, ip, userAgent string
		var metaJSON, username, displayName sql.NullString
		var createdAt time.Time
		if err := rows.Scan(&id, &userID, &groupName, &eventType, &message, &metaJSON, &ip, &userAgent, &createdAt, &username, &displayName); err != nil {
			return nil, nil, nil, nil, 0, err
		}
		meta := decodeMap(metaJSON.String)
		if locationID := int64FromAny(meta["location_id"]); locationID > 0 {
			locationIDs = append(locationIDs, locationID)
		}
		logs = append(logs, map[string]any{"id": id, "user_id": nullableInt64(userID), "username": username.String, "display_name": displayName.String, "group_name": groupName, "event_type": eventType, "message": message, "meta": meta, "ip": ip, "user_agent": userAgent, "created_at": nowFormat(createdAt)})
	}
	logLocations, err := handler.logLocations(r, locationIDs)
	if err != nil {
		return nil, nil, nil, nil, 0, err
	}
	typeRows, err := handler.db.QueryContext(r.Context(), "SELECT DISTINCT event_type FROM user_logs ORDER BY event_type ASC")
	if err != nil {
		return nil, nil, nil, nil, 0, err
	}
	defer typeRows.Close()
	types := make([]string, 0)
	for typeRows.Next() {
		var value string
		_ = typeRows.Scan(&value)
		types = append(types, value)
	}
	return logs, logLocations, filters, types, total, nil
}

func (handler AdminSummaryHandler) logLocations(r *http.Request, ids []int64) (map[string]any, error) {
	if len(ids) == 0 {
		return map[string]any{}, nil
	}
	unique := map[int64]bool{}
	for _, id := range ids {
		unique[id] = true
	}
	placeholders := make([]string, 0, len(unique))
	args := make([]any, 0, len(unique))
	for id := range unique {
		placeholders = append(placeholders, "?")
		args = append(args, id)
	}
	rows, err := handler.db.QueryContext(r.Context(), `
SELECT l.id, l.user_id, u.username, u.display_name, l.group_name, l.role, l.latitude, l.longitude, l.altitude, l.accuracy, l.heading, l.speed,
	l.location_meta, l.address_diagnostics, l.address_mismatch, COALESCE(l.encryption_mode, ''), COALESCE(l.encrypted_payload, ''), COALESCE(l.p2p_key_version, 0), l.created_at, l.created_at
FROM locations l
LEFT JOIN users u ON u.id = l.user_id
WHERE l.id IN (`+strings.Join(placeholders, ",")+`)`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := map[string]any{}
	for rows.Next() {
		var location models.Location
		if err := rows.Scan(&location.ID, &location.UserID, &location.Username, &location.DisplayName, &location.GroupName, &location.Role, &location.Latitude, &location.Longitude, &location.Altitude, &location.Accuracy, &location.Heading, &location.Speed, &location.LocationMeta, &location.AddressDiagnostics, &location.AddressMismatch, &location.EncryptionMode, &location.EncryptedPayload, &location.P2PKeyVersion, &location.CreatedAt, &location.UpdatedAt); err != nil {
			return nil, err
		}
		payload := services.LocationPayload(location, 600)
		payload["id"] = location.ID
		result[strconv.FormatInt(location.ID, 10)] = payload
	}
	return result, rows.Err()
}

func (handler AdminSummaryHandler) tickets(r *http.Request) ([]map[string]any, error) {
	rows, err := handler.db.QueryContext(r.Context(), `
SELECT t.id, t.user_id, u.username, u.display_name, t.group_name, t.subject, t.status,
	COALESCE(latest.message, ''), latest.created_at, t.created_at, t.updated_at
FROM support_tickets t
INNER JOIN users u ON u.id = t.user_id
LEFT JOIN support_ticket_messages latest ON latest.id = (
	SELECT id FROM support_ticket_messages WHERE ticket_id = t.id ORDER BY id DESC LIMIT 1
)
ORDER BY t.updated_at DESC, t.id DESC
LIMIT 50`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make([]map[string]any, 0)
	for rows.Next() {
		var id, userID int64
		var username, displayName, groupName, subject, status, lastMessage string
		var lastMessageAt sql.NullTime
		var createdAt, updatedAt time.Time
		if err := rows.Scan(&id, &userID, &username, &displayName, &groupName, &subject, &status, &lastMessage, &lastMessageAt, &createdAt, &updatedAt); err != nil {
			return nil, err
		}
		payload := ticketPayload(id, groupName, subject, status, lastMessage, lastMessageAt, createdAt, updatedAt)
		payload["user_id"] = userID
		payload["username"] = username
		payload["display_name"] = displayName
		result = append(result, payload)
	}
	return result, rows.Err()
}

func decodeMap(raw string) map[string]any {
	result := map[string]any{}
	_ = json.Unmarshal([]byte(raw), &result)
	return result
}

func firstMapString(data map[string]any, keys ...string) string {
	for _, key := range keys {
		if value := strings.TrimSpace(fmt.Sprint(data[key])); value != "" && value != "<nil>" {
			return value
		}
	}
	return ""
}

func stringSlice(value any) []string {
	items, ok := value.([]any)
	if !ok {
		return []string{}
	}
	result := make([]string, 0, len(items))
	for _, item := range items {
		result = append(result, fmt.Sprint(item))
	}
	return result
}

func intFromAny(value any) int {
	number, _ := numericAny(value)
	return int(number)
}

func int64FromAny(value any) int64 {
	number, _ := numericAny(value)
	return int64(number)
}

func nullableInt64(value sql.NullInt64) int64 {
	if !value.Valid {
		return 0
	}
	return value.Int64
}

func nullableTimeString(value sql.NullTime) string {
	if !value.Valid {
		return ""
	}
	return nowFormat(value.Time)
}

func clampInt(value int, min int, max int) int {
	if value < min {
		return min
	}
	if value > max {
		return max
	}
	return value
}

func maxInt(left int, right int) int {
	if left > right {
		return left
	}
	return right
}
