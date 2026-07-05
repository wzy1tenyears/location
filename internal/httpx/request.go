package httpx

import (
	"encoding/json"
	"net"
	"net/http"
	"strconv"
	"strings"
)

func IntQuery(r *http.Request, key string, fallback int) int {
	raw := strings.TrimSpace(r.URL.Query().Get(key))
	if raw == "" {
		return fallback
	}
	value, err := strconv.Atoi(raw)
	if err != nil {
		return fallback
	}
	return value
}

func PublicURL(r *http.Request, path string) string {
	target := "/" + strings.TrimLeft(path, "/")
	host := strings.TrimSpace(r.Header.Get("X-Forwarded-Host"))
	if host == "" {
		host = strings.TrimSpace(r.Host)
	}
	if host == "" {
		return target
	}

	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	}
	if proto := firstHeaderPart(r.Header.Get("X-Forwarded-Proto")); strings.EqualFold(proto, "https") {
		scheme = "https"
	}
	if strings.EqualFold(r.Header.Get("X-Forwarded-Ssl"), "on") {
		scheme = "https"
	}
	if strings.Contains(strings.ToLower(r.Header.Get("Cf-Visitor")), `"scheme":"https"`) {
		scheme = "https"
	}

	return scheme + "://" + host + target
}

func ClientIP(r *http.Request) string {
	for _, key := range []string{"CF-Connecting-IP", "X-Real-IP", "X-Forwarded-For"} {
		if value := firstHeaderPart(r.Header.Get(key)); value != "" {
			return value
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err == nil && host != "" {
		return host
	}
	return r.RemoteAddr
}

func DecodeJSON(r *http.Request, target any) error {
	defer r.Body.Close()
	return json.NewDecoder(r.Body).Decode(target)
}

func firstHeaderPart(value string) string {
	parts := strings.Split(value, ",")
	if len(parts) == 0 {
		return ""
	}
	return strings.TrimSpace(parts[0])
}
