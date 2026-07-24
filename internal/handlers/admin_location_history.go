package handlers

import (
	"fmt"
	"log"
	"net/http"
	"strconv"
	"strings"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/models"
	"familylocation/location-v3/internal/services"
)

const (
	defaultAdminLocationHistoryLimit = 50
	maxAdminLocationHistoryLimit     = 100
	maxAdminLocationHistoryPage      = 1_000_000
)

type adminLocationHistoryFilter struct {
	UserID    int64
	GroupName string
	Page      int
	Limit     int
}

func (handler AdminSummaryHandler) LocationHistory(w http.ResponseWriter, r *http.Request) {
	if !handler.sessions.IsAdmin(r) {
		httpx.Error(w, httpx.Unauthorized("请先登录后台。"))
		return
	}
	filter, err := parseAdminLocationHistoryFilter(r)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	locations, total, err := handler.locationHistory(r, filter)
	if err != nil {
		log.Printf("admin location history failed: %v", err)
		httpx.Error(w, err)
		return
	}
	totalPages := 0
	if total > 0 {
		totalPages = (total + filter.Limit - 1) / filter.Limit
	}
	httpx.OK(w, map[string]any{
		"ok":          true,
		"locations":   locations,
		"total":       total,
		"page":        filter.Page,
		"limit":       filter.Limit,
		"total_pages": totalPages,
		"server_time": nowString(),
	})
}

func parseAdminLocationHistoryFilter(r *http.Request) (adminLocationHistoryFilter, error) {
	filter := adminLocationHistoryFilter{
		GroupName: strings.TrimSpace(r.URL.Query().Get("group_name")),
		Page:      1,
		Limit:     defaultAdminLocationHistoryLimit,
	}
	if len(filter.GroupName) > 100 {
		return filter, httpx.Unprocessable("家庭组筛选值过长。")
	}
	if raw := strings.TrimSpace(r.URL.Query().Get("user_id")); raw != "" {
		value, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || value <= 0 {
			return filter, httpx.Unprocessable("用户筛选值不正确。")
		}
		filter.UserID = value
	}
	if raw := strings.TrimSpace(r.URL.Query().Get("page")); raw != "" {
		value, err := strconv.Atoi(raw)
		if err != nil || value <= 0 || value > maxAdminLocationHistoryPage {
			return filter, httpx.Unprocessable("页码不正确。")
		}
		filter.Page = value
	}
	if raw := strings.TrimSpace(r.URL.Query().Get("limit")); raw != "" {
		value, err := strconv.Atoi(raw)
		if err != nil || value <= 0 || value > maxAdminLocationHistoryLimit {
			return filter, httpx.Unprocessable("每页条数不正确。")
		}
		filter.Limit = value
	}
	return filter, nil
}

func (handler AdminSummaryHandler) locationHistory(
	r *http.Request,
	filter adminLocationHistoryFilter,
) ([]map[string]any, int, error) {
	where := []string{"1 = 1"}
	args := make([]any, 0, 4)
	if filter.UserID > 0 {
		where = append(where, "l.user_id = ?")
		args = append(args, filter.UserID)
	}
	if filter.GroupName != "" {
		where = append(where, "l.group_name = ?")
		args = append(args, filter.GroupName)
	}
	whereSQL := strings.Join(where, " AND ")
	var total int
	if err := handler.db.QueryRowContext(
		r.Context(),
		"SELECT COUNT(*) FROM locations l WHERE "+whereSQL,
		args...,
	).Scan(&total); err != nil {
		return nil, 0, err
	}

	queryArgs := append(append([]any{}, args...), filter.Limit, (filter.Page-1)*filter.Limit)
	rows, err := handler.db.QueryContext(r.Context(), `
SELECT l.id, l.user_id, u.username, u.display_name, l.group_name, l.role,
	l.latitude, l.longitude, l.altitude, l.accuracy, l.heading, l.speed,
	l.location_meta, l.address_diagnostics, l.address_mismatch,
	COALESCE(l.encryption_mode, ''), COALESCE(l.encrypted_payload, ''), COALESCE(l.p2p_key_version, 0),
	l.created_at, l.created_at
FROM locations l
LEFT JOIN users u ON u.id = l.user_id
WHERE `+whereSQL+`
ORDER BY l.created_at DESC, l.id DESC
LIMIT ? OFFSET ?`, queryArgs...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	result := make([]map[string]any, 0, filter.Limit)
	for rows.Next() {
		var location models.Location
		if err := rows.Scan(
			&location.ID, &location.UserID, &location.Username, &location.DisplayName,
			&location.GroupName, &location.Role, &location.Latitude, &location.Longitude,
			&location.Altitude, &location.Accuracy, &location.Heading, &location.Speed,
			&location.LocationMeta, &location.AddressDiagnostics, &location.AddressMismatch,
			&location.EncryptionMode, &location.EncryptedPayload, &location.P2PKeyVersion,
			&location.CreatedAt, &location.UpdatedAt,
		); err != nil {
			return nil, 0, fmt.Errorf("scan admin location history: %w", err)
		}
		result = append(result, services.LocationPayload(location, 600))
	}
	if err := rows.Err(); err != nil {
		return nil, 0, err
	}
	return result, total, nil
}
