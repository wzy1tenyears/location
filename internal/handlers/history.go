package handlers

import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	"familylocation/location-v3/internal/config"
	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/models"
	"familylocation/location-v3/internal/repositories"
	"familylocation/location-v3/internal/services"
	"familylocation/location-v3/internal/session"
)

const (
	maxHistorySnapshotMembers = 64
	maxHistoryMemberPageSize  = 64
	maxHistorySnapshotRawRows = 10000
	maxHistoryMapRows         = 2000
	maxHistoryResponseBytes   = 2 << 20

	historyTooManyMembersMessage   = "家庭组成员过多，请选择单个成员查看历史。"
	historyTooManyRowsMessage      = "历史记录过多，请选择单个成员查看。"
	historyTooManyMapRowsMessage   = "地图历史记录过多，请选择单个成员查看。"
	historyResponseTooLargeMessage = "历史快照超过 2 MiB，无法一次返回，请缩短时间范围或选择单个成员。"
)

type HistoryHandler struct {
	cfg       config.Config
	scope     scopedHandler
	groups    repositories.GroupRepository
	locations repositories.LocationRepository
	rates     hitLimiter
}

const (
	historyReadUserMaxHits    = 30
	historyReadIPMaxHits      = 90
	historyMembersUserMaxHits = 60
	historyMembersIPMaxHits   = 180
	historyReadRateWindow     = time.Minute
)

func NewHistoryHandler(cfg config.Config, db *sql.DB, sessions session.Reader) HistoryHandler {
	return HistoryHandler{
		cfg:       cfg,
		scope:     newScopedHandler(db, sessions),
		groups:    repositories.NewGroupRepository(db),
		locations: repositories.NewLocationRepository(db),
		rates:     repositories.NewRateLimitRepository(db),
	}
}

func (handler HistoryHandler) Members(w http.ResponseWriter, r *http.Request) {
	groupName := strings.TrimSpace(r.URL.Query().Get("group_name"))
	page := maxInt(1, httpx.IntQuery(r, "page", 1))
	scope, _, err := handler.scope.requireScope(r, selectedGroupName(r, groupName))
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if !allowScopedRead(w, r, handler.rates, "history_members_read", scope.User.ID, historyMembersUserMaxHits, historyMembersIPMaxHits, historyReadRateWindow) {
		return
	}
	total, err := handler.groups.CountMembers(r.Context(), scope.Membership.GroupName)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	totalPages := (total + maxHistoryMemberPageSize - 1) / maxHistoryMemberPageSize
	if totalPages < 1 {
		totalPages = 1
	}
	if page > totalPages {
		page = totalPages
	}
	members, err := handler.groups.MembersPage(
		r.Context(),
		scope.Membership.GroupName,
		maxHistoryMemberPageSize,
		(page-1)*maxHistoryMemberPageSize,
	)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	httpx.OK(w, map[string]any{
		"ok":      true,
		"members": memberPayloads(members),
		"pagination": map[string]any{
			"page":        page,
			"per_page":    maxHistoryMemberPageSize,
			"total":       total,
			"total_pages": totalPages,
		},
	})
}

type historyRequest struct {
	GroupName           string `json:"group_name"`
	Page                int    `json:"page"`
	PerPage             int    `json:"per_page"`
	MapPerUser          int    `json:"map_per_user"`
	UserID              int64  `json:"user_id"`
	RangeHours          int    `json:"range_hours"`
	ClientMergeSnapshot bool   `json:"client_merge_snapshot"`
}

func (handler HistoryHandler) List(w http.ResponseWriter, r *http.Request) {
	req := historyRequest{Page: 1, PerPage: 20, MapPerUser: 20, RangeHours: 24}
	if r.Method == http.MethodPost {
		if err := httpx.DecodeJSON(r, &req); err != nil {
			httpx.Error(w, err)
			return
		}
	} else {
		req.GroupName = strings.TrimSpace(r.URL.Query().Get("group_name"))
		req.Page = maxInt(1, httpx.IntQuery(r, "page", 1))
		req.PerPage = httpx.IntQuery(r, "per_page", 20)
		req.MapPerUser = httpx.IntQuery(r, "map_per_user", 20)
		req.UserID = int64(httpx.IntQuery(r, "user_id", 0))
		req.RangeHours = httpx.IntQuery(r, "range_hours", 24)
	}
	if req.Page < 1 {
		req.Page = 1
	}
	if !containsInt([]int{20, 50, 100}, req.PerPage) {
		req.PerPage = 20
	}
	if !containsInt([]int{20, 50, 100}, req.MapPerUser) {
		req.MapPerUser = 20
	}
	req.RangeHours = normalizeHistoryRangeHours(req.RangeHours)

	scope, _, err := handler.scope.requireScope(r, selectedGroupName(r, req.GroupName))
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if !allowScopedRead(w, r, handler.rates, "history_read", scope.User.ID, historyReadUserMaxHits, historyReadIPMaxHits, historyReadRateWindow) {
		return
	}

	boundedMembers, err := handler.groups.MembersBounded(r.Context(), scope.Membership.GroupName, maxHistorySnapshotMembers+1)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if req.UserID <= 0 && len(boundedMembers) > maxHistorySnapshotMembers {
		handler.respondMemberSelectionRequired(w, scope, boundedMembers[:maxHistorySnapshotMembers], req)
		return
	}
	var members []models.GroupMember
	responseMembers := boundedMembers
	membersTruncated := false
	if req.UserID > 0 {
		if len(boundedMembers) <= maxHistorySnapshotMembers {
			members = filterMembers(boundedMembers, req.UserID)
		} else {
			member, memberErr := handler.groups.Member(r.Context(), scope.Membership.GroupName, req.UserID)
			if memberErr != nil {
				httpx.Error(w, memberErr)
				return
			}
			if member != nil {
				members = []models.GroupMember{*member}
				responseMembers = members
				membersTruncated = true
			}
		}
		if len(members) == 0 {
			httpx.Error(w, httpx.Forbidden("无权查看这个成员。"))
			return
		}
	} else {
		members = boundedMembers
	}
	if err := validateHistoryMemberCount(len(members)); err != nil {
		httpx.Error(w, err)
		return
	}

	rangeStart := time.Now().Add(-time.Duration(req.RangeHours) * time.Hour)
	rawRows, err := handler.locations.RetainedHistoryForUsersBoundedSince(
		r.Context(),
		scope.Membership.GroupName,
		memberUserIDs(members),
		handler.cfg.Location.HistoryLimit,
		maxHistorySnapshotRawRows,
		maxHistoryResponseBytes,
		rangeStart,
	)
	if err != nil {
		httpx.Error(w, historyRepositoryError(err))
		return
	}
	composeRows := rawRows
	if !req.ClientMergeSnapshot {
		composeRows = historyRowsAtOrAfter(rawRows, rangeStart)
	}
	view := composeLocationHistory(composeRows, req.Page, req.PerPage, req.MapPerUser)
	if req.ClientMergeSnapshot {
		view.clientMergeRows = rawRows
	}
	if err := validateHistoryMapRowCount(len(view.mapRows)); err != nil {
		httpx.Error(w, err)
		return
	}
	req.Page = view.page
	response := map[string]any{
		"ok":                true,
		"user":              services.PublicUserPayloadForGroups(*scope.User, scope.Groups, scope.Membership),
		"selected_group":    services.GroupPayload(*scope.Membership),
		"members":           memberPayloads(responseMembers),
		"members_truncated": membersTruncated,
		"selection_limited": membersTruncated,
		"history":           locationPayloads(view.rows, false),
		"map_history":       locationPayloads(view.mapRows, false),
		"range_hours":       req.RangeHours,
		"range_start":       services.FormatDatetime(rangeStart),
		"pagination": map[string]any{
			"page":         req.Page,
			"per_page":     req.PerPage,
			"map_per_user": req.MapPerUser,
			"total":        view.total,
			"total_pages":  view.totalPages,
			"user_id":      req.UserID,
		},
		"server_time": time.Now().Format("2006-01-02 15:04:05"),
	}
	if membersTruncated {
		response["member_selector"] = map[string]any{
			"endpoint":  "/api/history-members",
			"page_size": maxHistoryMemberPageSize,
		}
	}
	appendClientMergeSnapshot(response, view, req.ClientMergeSnapshot, handler.cfg.Location.HistoryLimit)
	if err := validateHistoryResponseSize(response); err != nil {
		httpx.Error(w, err)
		return
	}
	httpx.OK(w, response)
}

func (handler HistoryHandler) respondMemberSelectionRequired(w http.ResponseWriter, scope *userScope, members []models.GroupMember, req historyRequest) {
	response := map[string]any{
		"ok":                 true,
		"user":               services.PublicUserPayloadForGroups(*scope.User, scope.Groups, scope.Membership),
		"selected_group":     services.GroupPayload(*scope.Membership),
		"members":            memberPayloads(members),
		"members_truncated":  true,
		"selection_limited":  true,
		"selection_required": true,
		"member_selector": map[string]any{
			"endpoint":  "/api/history-members",
			"page_size": maxHistoryMemberPageSize,
		},
		"history":     []map[string]any{},
		"map_history": []map[string]any{},
		"range_hours": req.RangeHours,
		"range_start": services.FormatDatetime(time.Now().Add(-time.Duration(req.RangeHours) * time.Hour)),
		"pagination": map[string]any{
			"page":         1,
			"per_page":     req.PerPage,
			"map_per_user": req.MapPerUser,
			"total":        0,
			"total_pages":  1,
			"user_id":      0,
		},
		"server_time": time.Now().Format("2006-01-02 15:04:05"),
	}
	if err := validateHistoryResponseSize(response); err != nil {
		httpx.Error(w, err)
		return
	}
	httpx.OK(w, response)
}

func historyRepositoryError(err error) error {
	if errors.Is(err, repositories.ErrLocationHistorySnapshotTooLarge) {
		return httpx.Unprocessable(historyTooManyRowsMessage)
	}
	if errors.Is(err, repositories.ErrLocationHistorySnapshotBytesTooLarge) {
		return httpx.Unprocessable(historyResponseTooLargeMessage)
	}
	return err
}

func validateHistoryMemberCount(count int) error {
	if count > maxHistorySnapshotMembers {
		return httpx.APIError{
			Status:  http.StatusUnprocessableEntity,
			Message: historyTooManyMembersMessage,
			Code:    "history_member_selection_required",
			Details: map[string]any{
				"member_selector": map[string]any{
					"endpoint":  "/api/history-members",
					"page_size": maxHistoryMemberPageSize,
				},
			},
		}
	}
	return nil
}

func validateHistoryMapRowCount(count int) error {
	if count > maxHistoryMapRows {
		return httpx.Unprocessable(historyTooManyMapRowsMessage)
	}
	return nil
}

type composedLocationHistory struct {
	rows            []models.Location
	mapRows         []models.Location
	clientMergeRows []models.Location
	page            int
	total           int
	totalPages      int
}

func composeLocationHistory(rawRows []models.Location, page int, perPage int, mapPerUser int) composedLocationHistory {
	mergedRows := services.MergeLocationHistory(rawRows, services.DefaultStayRadiusMeters)
	rows, normalizedPage, totalPages := services.PaginateLocationHistory(mergedRows, page, perPage)
	return composedLocationHistory{
		rows:            rows,
		mapRows:         services.LimitLocationHistoryPerUser(mergedRows, mapPerUser),
		clientMergeRows: rawRows,
		page:            normalizedPage,
		total:           len(mergedRows),
		totalPages:      totalPages,
	}
}

func appendClientMergeSnapshot(response map[string]any, view composedLocationHistory, enabled bool, perUserRawLimit int) {
	if !enabled {
		return
	}
	response["client_merge_history"] = locationPayloads(view.clientMergeRows, false)
	response["client_merge_complete"] = true
	response["client_merge_retention"] = map[string]any{
		"scope":                  "selected_members",
		"record_shape":           "raw",
		"merge_owner":            "client",
		"per_user_raw_limit":     perUserRawLimit,
		"complete_within_window": true,
		"note":                   "快照包含当前保留窗口内的全部原始记录；客户端需先解密端到端加密记录，再按 25 米停留规则重算。",
	}
}

func validateHistoryResponseSize(response map[string]any) error {
	raw, err := json.Marshal(response)
	if err != nil {
		return err
	}
	return validateHistoryResponseBytes(len(raw))
}

func validateHistoryResponseBytes(size int) error {
	// httpx writes JSON with Encoder.Encode, which appends one newline byte.
	if size >= maxHistoryResponseBytes {
		return httpx.Unprocessable(historyResponseTooLargeMessage)
	}
	return nil
}

func memberUserIDs(members []models.GroupMember) []int64 {
	userIDs := make([]int64, 0, len(members))
	for _, member := range members {
		userIDs = append(userIDs, member.UserID)
	}
	return userIDs
}

func containsInt(values []int, target int) bool {
	for _, value := range values {
		if value == target {
			return true
		}
	}
	return false
}

func normalizeHistoryRangeHours(value int) int {
	if containsInt([]int{1, 24, 168, 720}, value) {
		return value
	}
	return 24
}

func historyRowsAtOrAfter(rows []models.Location, since time.Time) []models.Location {
	filtered := make([]models.Location, 0, len(rows))
	for _, row := range rows {
		if !row.CreatedAt.Before(since) {
			filtered = append(filtered, row)
		}
	}
	return filtered
}

func filterMembers(members []models.GroupMember, userID int64) []models.GroupMember {
	filtered := make([]models.GroupMember, 0, 1)
	for _, member := range members {
		if member.UserID == userID {
			filtered = append(filtered, member)
		}
	}
	return filtered
}

func memberPayloads(members []models.GroupMember) []map[string]any {
	payloads := make([]map[string]any, 0, len(members))
	for _, member := range members {
		payloads = append(payloads, services.MemberPayload(member))
	}
	return payloads
}

func locationPayloads(locations []models.Location, includeStale bool) []map[string]any {
	payloads := make([]map[string]any, 0, len(locations))
	for _, location := range locations {
		payload := services.LocationPayload(location, 600)
		if !includeStale {
			delete(payload, "is_stale")
			delete(payload, "updated_at")
		}
		payloads = append(payloads, payload)
	}
	return payloads
}
