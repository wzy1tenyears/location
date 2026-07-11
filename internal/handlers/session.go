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
	record, authenticated := handler.sessions.Record(r)
	if authenticated && record.UserID.Valid && record.UserID.Int64 > 0 {
		id := record.UserID.Int64
		_ = handler.users.RecordLog(r.Context(), &id, "", "offline", "用户退出登录", nil, httpx.ClientIP(r), r.UserAgent())
	}
	if authenticated && record.AdminLoggedIn {
		_ = handler.users.RecordLog(r.Context(), nil, "", "admin_logout", "管理员退出登录", nil, httpx.ClientIP(r), r.UserAgent())
	}
	if sessionID, ok := handler.sessions.SessionID(r); ok {
		_ = handler.sessions.DeleteByID(r.Context(), sessionID)
	}
	handler.sessions.Clear(w, r)
	httpx.OK(w, map[string]any{"ok": true})
}
