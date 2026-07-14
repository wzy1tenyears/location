package session

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"net/http"
	"time"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/repositories"
)

var ErrUserCredentialChanged = errors.New("user credentials changed during session creation")

type Store struct {
	CookieName string
	Repo       *repositories.SessionRepository
	Lifetime   time.Duration
}

func (store Store) StartUserSession(w http.ResponseWriter, r *http.Request, userID int64, expectedPasswordHash string) (string, error) {
	return store.writeSession(w, r, &userID, expectedPasswordHash)
}

func (store Store) StartAdminSession(w http.ResponseWriter, r *http.Request) (string, error) {
	return store.writeSession(w, r, nil, "")
}

func (store Store) writeSession(w http.ResponseWriter, r *http.Request, userID *int64, expectedPasswordHash string) (string, error) {
	sessionID, err := randomHex(20)
	if err != nil {
		return "", err
	}
	expiresAt := time.Now().Add(store.Lifetime)
	if userID != nil {
		created, err := store.Repo.CreateUserSessionIfPasswordMatches(r.Context(), sessionID, *userID, expectedPasswordHash, expiresAt)
		if err != nil {
			return "", err
		}
		if !created {
			return "", ErrUserCredentialChanged
		}
	} else if err := store.Repo.UpsertAdmin(r.Context(), sessionID, expiresAt); err != nil {
		return "", err
	}
	http.SetCookie(w, &http.Cookie{
		Name:     store.CookieName,
		Value:    sessionID,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Secure:   httpx.RequestIsHTTPS(r),
		MaxAge:   int(store.Lifetime.Seconds()),
		Expires:  expiresAt,
	})
	return sessionID, nil
}

func randomHex(bytes int) (string, error) {
	buffer := make([]byte, bytes)
	if _, err := rand.Read(buffer); err != nil {
		return "", err
	}
	return hex.EncodeToString(buffer), nil
}
