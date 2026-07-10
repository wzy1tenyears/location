package handlers

import (
	"database/sql"
	"net/http"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/repositories"
	"familylocation/location-v3/internal/session"
)

type SessionHandler struct {
	users    repositories.UserRepository
	sessions session.Reader
}

func NewSessionHandler(db *sql.DB, sessions session.Reader) SessionHandler {
	return SessionHandler{
		users:    repositories.NewUserRepository(db),
		sessions: sessions,
	}
}

func (handler SessionHandler) Logout(w http.ResponseWriter, r *http.Request) {
	if sessionID, ok := handler.sessions.SessionID(r); ok {
		handler.sessions.DeleteByID(sessionID)
	}
	if userID, ok := handler.sessions.UserID(r); ok {
		id := userID
		_ = handler.users.RecordLog(r.Context(), &id, "", "offline", "用户退出登录", nil, httpx.ClientIP(r), r.UserAgent())
	}
	if handler.sessions.IsAdmin(r) {
		_ = handler.users.RecordLog(r.Context(), nil, "", "admin_logout", "管理员退出登录", nil, httpx.ClientIP(r), r.UserAgent())
	}
	handler.sessions.Clear(w)
	httpx.OK(w, map[string]any{"ok": true})
}
