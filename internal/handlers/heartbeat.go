package handlers

import (
	"database/sql"
	"net/http"
	"time"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/repositories"
	"familylocation/location-v3/internal/session"
)

type HeartbeatHandler struct {
	scope scopedHandler
	users repositories.UserRepository
}

func NewHeartbeatHandler(db *sql.DB, sessions session.Reader) HeartbeatHandler {
	return HeartbeatHandler{
		scope: newScopedHandler(db, sessions),
		users: repositories.NewUserRepository(db),
	}
}

type heartbeatRequest struct {
	GroupName string `json:"group_name"`
}

func (handler HeartbeatHandler) Touch(w http.ResponseWriter, r *http.Request) {
	var req heartbeatRequest
	_ = httpx.DecodeJSON(r, &req)
	scope, _, err := handler.scope.requireScope(r, selectedGroupName(r, req.GroupName))
	if err != nil {
		httpx.Error(w, err)
		return
	}
	groupName := ""
	if scope.Membership != nil {
		groupName = scope.Membership.GroupName
	}
	if err := handler.users.TouchPresence(r.Context(), scope.User.ID, groupName, r.UserAgent(), httpx.ClientIP(r)); err != nil {
		httpx.Error(w, err)
		return
	}
	id := scope.User.ID
	_ = handler.users.RecordLog(r.Context(), &id, groupName, "online", "用户心跳", nil, httpx.ClientIP(r), r.UserAgent())
	httpx.OK(w, map[string]any{
		"ok":          true,
		"server_time": time.Now().Format("2006-01-02 15:04:05"),
	})
}
