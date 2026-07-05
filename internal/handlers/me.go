package handlers

import (
	"database/sql"
	"net/http"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/repositories"
	"familylocation/location-v3/internal/services"
	"familylocation/location-v3/internal/session"
)

type MeHandler struct {
	users    repositories.UserRepository
	sessions session.Reader
}

func NewMeHandler(db *sql.DB, sessions session.Reader) MeHandler {
	return MeHandler{
		users:    repositories.NewUserRepository(db),
		sessions: sessions,
	}
}

func (handler MeHandler) Show(w http.ResponseWriter, r *http.Request) {
	userID, ok := handler.sessions.UserID(r)
	if !ok {
		httpx.OK(w, map[string]any{
			"ok":   true,
			"user": nil,
		})
		return
	}

	user, err := handler.users.FindActiveByID(r.Context(), userID)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if user == nil {
		httpx.OK(w, map[string]any{
			"ok":   true,
			"user": nil,
		})
		return
	}
	if !services.UserTermsAccepted(*user) {
		httpx.Error(w, httpx.Forbidden("请先同意用户协议、隐私条约和用户数据跨境加密传输协议。"))
		return
	}

	httpx.OK(w, map[string]any{
		"ok":                      true,
		"user":                    services.PublicUserPayload(*user),
		"report_interval_seconds": services.NormalizeReportIntervalSeconds(user.ReportIntervalSeconds),
	})
}

