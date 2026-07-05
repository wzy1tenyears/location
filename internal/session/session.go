package session

import (
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

type Reader struct {
	CookieName string
	SavePath   string
}

func (reader Reader) UserID(r *http.Request) (int64, bool) {
	value, ok := reader.cookieValue(r)
	if !ok {
		return 0, false
	}

	session, ok := reader.phpSession(value)
	if !ok {
		return 0, false
	}
	id, ok := sessionInt(session, "user_id")
	return id, ok && id > 0
}

func (reader Reader) IsAdmin(r *http.Request) bool {
	value, ok := reader.cookieValue(r)
	if !ok {
		return false
	}

	session, ok := reader.phpSession(value)
	if !ok {
		return false
	}
	return sessionBool(session, "admin_logged_in")
}

func (reader Reader) cookieValue(r *http.Request) (string, bool) {
	cookie, err := r.Cookie(reader.CookieName)
	if err != nil {
		return "", false
	}
	value := strings.TrimSpace(cookie.Value)
	return value, value != ""
}

func (reader Reader) phpSession(sessionID string) (map[string]string, bool) {
	if strings.TrimSpace(reader.SavePath) == "" || !safeSessionID(sessionID) {
		return nil, false
	}

	path := filepath.Join(reader.SavePath, "sess_"+sessionID)
	data, err := os.ReadFile(path)
	if err != nil || len(data) == 0 {
		return nil, false
	}

	return parsePHPSession(string(data)), true
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

var phpSessionEntryPattern = regexp.MustCompile(`([A-Za-z0-9_]+)\|([bis]):([^;]+);`)

func parsePHPSession(raw string) map[string]string {
	values := make(map[string]string)
	for _, match := range phpSessionEntryPattern.FindAllStringSubmatch(raw, -1) {
		if len(match) == 4 {
			values[match[1]] = match[2] + ":" + match[3]
		}
	}
	return values
}

func sessionInt(values map[string]string, key string) (int64, bool) {
	raw, ok := values[key]
	if !ok || !strings.HasPrefix(raw, "i:") {
		return 0, false
	}
	value, err := strconv.ParseInt(strings.TrimPrefix(raw, "i:"), 10, 64)
	if err != nil {
		return 0, false
	}
	return value, true
}

func sessionBool(values map[string]string, key string) bool {
	raw, ok := values[key]
	return ok && raw == "b:1"
}
