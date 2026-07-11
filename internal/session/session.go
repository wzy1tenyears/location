package session

import (
	"context"
	"net/http"
	"strings"
	"time"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/repositories"
)

type Reader struct {
	CookieName string
	Repo       *repositories.SessionRepository
}

func (reader Reader) SessionID(r *http.Request) (string, bool) {
	return reader.cookieValue(r)
}

func (reader Reader) Clear(w http.ResponseWriter, r *http.Request) {
	http.SetCookie(w, &http.Cookie{
		Name:     reader.CookieName,
		Value:    "",
		Path:     "/",
		MaxAge:   -1,
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Secure:   httpx.RequestIsHTTPS(r),
	})
}

func (reader Reader) DeleteByID(ctx context.Context, sessionID string) error {
	if !safeSessionID(sessionID) {
		return nil
	}
	return reader.Repo.Delete(ctx, sessionID)
}

func (reader Reader) UserID(r *http.Request) (int64, bool) {
	record, ok := reader.Record(r)
	if !ok || !record.UserID.Valid || record.UserID.Int64 <= 0 {
		return 0, false
	}
	return record.UserID.Int64, true
}

func (reader Reader) IsAdmin(r *http.Request) bool {
	record, ok := reader.Record(r)
	if !ok {
		return false
	}
	return record.AdminLoggedIn
}

func (reader Reader) Record(r *http.Request) (*repositories.SessionRecord, bool) {
	sessionID, ok := reader.cookieValue(r)
	if !ok || !safeSessionID(sessionID) {
		return nil, false
	}
	record, err := reader.Repo.FindActive(r.Context(), sessionID, time.Now())
	if err != nil || record == nil {
		return nil, false
	}
	reader.Repo.DeleteExpiredIfDue(r.Context(), time.Now(), time.Hour)
	return record, true
}

func (reader Reader) cookieValue(r *http.Request) (string, bool) {
	cookie, err := r.Cookie(reader.CookieName)
	if err != nil {
		return "", false
	}
	value := strings.TrimSpace(cookie.Value)
	return value, value != ""
}

func safeSessionID(sessionID string) bool {
	if len(sessionID) < 16 || len(sessionID) > 160 {
		return false
	}
	for _, ch := range sessionID {
		if (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '-' || ch == ',' {
			continue
		}
		return false
	}
	return true
}
