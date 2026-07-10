package session

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"time"
)

type Store struct {
	CookieName string
	SavePath   string
	Lifetime   time.Duration
}

func (store Store) StartUserSession(w http.ResponseWriter, r *http.Request, userID int64) (string, error) {
	return store.writeSession(w, r, map[string]string{
		"user_id": fmt.Sprintf("i:%d", userID),
	})
}

func (store Store) StartAdminSession(w http.ResponseWriter, r *http.Request) (string, error) {
	return store.writeSession(w, r, map[string]string{
		"admin_logged_in": "b:1",
	})
}

func (store Store) writeSession(w http.ResponseWriter, r *http.Request, values map[string]string) (string, error) {
	sessionID, err := randomHex(20)
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(store.SavePath, 0o755); err != nil {
		return "", err
	}
	body := ""
	for key, value := range values {
		body += key + "|" + value + ";"
	}
	if err := os.WriteFile(filepath.Join(store.SavePath, "sess_"+sessionID), []byte(body), 0o600); err != nil {
		return "", err
	}
	http.SetCookie(w, &http.Cookie{
		Name:     store.CookieName,
		Value:    sessionID,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Secure:   requestIsHTTPS(r),
		MaxAge:   int(store.Lifetime.Seconds()),
		Expires:  time.Now().Add(store.Lifetime),
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

func requestIsHTTPS(r *http.Request) bool {
	if r.TLS != nil {
		return true
	}
	if r.Header.Get("X-Forwarded-Proto") == "https" || r.Header.Get("X-Forwarded-Ssl") == "on" {
		return true
	}
	return false
}
