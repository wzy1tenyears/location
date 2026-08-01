package handlers

import (
	"net/http"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/session"
)

type AdminHeartbeatHandler struct {
	sessions session.Reader
}

func NewAdminHeartbeatHandler(sessions session.Reader) AdminHeartbeatHandler {
	return AdminHeartbeatHandler{sessions: sessions}
}

func (handler AdminHeartbeatHandler) Touch(w http.ResponseWriter, r *http.Request) {
	if !handler.sessions.IsAdmin(r) {
		httpx.Error(w, httpx.Unauthorized("请先登录后台。"))
		return
	}
	httpx.OK(w, map[string]any{
		"ok": true,
	})
}
