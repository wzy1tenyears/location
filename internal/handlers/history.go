package handlers

import (
	"database/sql"
	"net/http"
	"sort"
	"strings"
	"time"

	"familylocation/location-v3/internal/config"
	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/models"
	"familylocation/location-v3/internal/repositories"
	"familylocation/location-v3/internal/services"
	"familylocation/location-v3/internal/session"
)

type HistoryHandler struct {
	cfg       config.Config
	scope     scopedHandler
	groups    repositories.GroupRepository
	locations repositories.LocationRepository
}

func NewHistoryHandler(cfg config.Config, db *sql.DB, sessions session.Reader) HistoryHandler {
	return HistoryHandler{
		cfg:       cfg,
		scope:     newScopedHandler(db, sessions),
		groups:    repositories.NewGroupRepository(db),
		locations: repositories.NewLocationRepository(db),
	}
}

type historyRequest struct {
	GroupName  string `json:"group_name"`
	Page       int    `json:"page"`
	PerPage    int    `json:"per_page"`
	MapPerUser int    `json:"map_per_user"`
	UserID     int64  `json:"user_id"`
}

func (handler HistoryHandler) List(w http.ResponseWriter, r *http.Request) {
	req := historyRequest{Page: 1, PerPage: 20, MapPerUser: 20}
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

	scope, _, err := handler.scope.requireScope(r, selectedGroupName(r, req.GroupName))
	if err != nil {
		httpx.Error(w, err)
		return
	}

	allMembers, err := handler.groups.Members(r.Context(), scope.Membership.GroupName)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	members := allMembers
	if req.UserID > 0 {
		members = filterMembers(allMembers, req.UserID)
		if len(members) == 0 {
			httpx.Error(w, httpx.Forbidden("无权查看这个成员。"))
			return
		}
	}

	total, err := handler.locations.CountHistory(r.Context(), scope.Membership.GroupName, req.UserID)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	totalPages := total / req.PerPage
	if total%req.PerPage != 0 {
		totalPages++
	}
	if totalPages < 1 {
		totalPages = 1
	}
	if req.Page > totalPages {
		req.Page = totalPages
	}

	rows, err := handler.locations.HistoryPage(r.Context(), scope.Membership.GroupName, req.UserID, req.PerPage, (req.Page-1)*req.PerPage)
	if err != nil {
		httpx.Error(w, err)
		return
	}

	mapRows := make([]models.Location, 0, len(members)*req.MapPerUser)
	for _, member := range members {
		memberRows, err := handler.locations.HistoryForUser(r.Context(), scope.Membership.GroupName, member.UserID, req.MapPerUser)
		if err != nil {
			httpx.Error(w, err)
			return
		}
		mapRows = append(mapRows, memberRows...)
	}
	sort.SliceStable(mapRows, func(i, j int) bool {
		if mapRows[i].CreatedAt.Equal(mapRows[j].CreatedAt) {
			return mapRows[i].ID > mapRows[j].ID
		}
		return mapRows[i].CreatedAt.After(mapRows[j].CreatedAt)
	})

	httpx.OK(w, map[string]any{
		"ok":             true,
		"user":           services.PublicUserPayloadForGroups(*scope.User, scope.Groups, scope.Membership),
		"selected_group": services.GroupPayload(*scope.Membership),
		"members":        memberPayloads(allMembers),
		"history":        locationPayloads(rows, false),
		"map_history":    locationPayloads(mapRows, false),
		"pagination": map[string]any{
			"page":         req.Page,
			"per_page":     req.PerPage,
			"map_per_user": req.MapPerUser,
			"total":        total,
			"total_pages":  totalPages,
			"user_id":      req.UserID,
		},
		"server_time": time.Now().Format("2006-01-02 15:04:05"),
	})
}

func containsInt(values []int, target int) bool {
	for _, value := range values {
		if value == target {
			return true
		}
	}
	return false
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
