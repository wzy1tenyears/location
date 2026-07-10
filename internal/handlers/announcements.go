package handlers

import (
	"database/sql"
	"net/http"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/repositories"
	"familylocation/location-v3/internal/services"
	"familylocation/location-v3/internal/session"
)

type AnnouncementHandler struct {
	users         repositories.UserRepository
	announcements repositories.AnnouncementRepository
	sessions      session.Reader
}

func NewAnnouncementHandler(db *sql.DB, sessions session.Reader) AnnouncementHandler {
	return AnnouncementHandler{
		users:         repositories.NewUserRepository(db),
		announcements: repositories.NewAnnouncementRepository(db),
		sessions:      sessions,
	}
}

func (handler AnnouncementHandler) Latest(w http.ResponseWriter, r *http.Request) {
	if userID, ok := handler.sessions.UserID(r); ok {
		user, err := handler.users.FindActiveByID(r.Context(), userID)
		if err != nil {
			httpx.Error(w, err)
			return
		}
		if user != nil && !services.UserTermsAccepted(*user) {
			httpx.Error(w, httpx.Forbidden("请先同意用户协议、隐私条约和用户数据跨境加密传输协议。"))
			return
		}
	}

	announcement, err := handler.announcements.LatestActive(r.Context())
	if err != nil {
		httpx.Error(w, err)
		return
	}

	var payload any
	if announcement != nil {
		payload = map[string]any{
			"id":         announcement.ID,
			"title":      announcement.Title,
			"body":       announcement.Body,
			"version":    announcement.Version,
			"updated_at": services.FormatDatetime(announcement.UpdatedAt),
		}
	}

	httpx.OK(w, map[string]any{
		"ok":           true,
		"announcement": payload,
	})
}
