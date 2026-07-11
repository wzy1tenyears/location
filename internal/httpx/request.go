package httpx

import (
	"encoding/json"
	"errors"
	"io"
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
	if RequestIsHTTPS(r) {
		scheme = "https"
	}

	return scheme + "://" + host + target
}

func ClientIP(r *http.Request) string {
	for _, key := range []string{"X-Real-IP", "X-Forwarded-For", "CF-Connecting-IP"} {
		if value := validHeaderIP(r.Header.Get(key)); value != "" {
			return value
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err == nil {
		if ip := net.ParseIP(strings.TrimSpace(host)); ip != nil {
			return ip.String()
		}
	}
	if ip := net.ParseIP(strings.TrimSpace(r.RemoteAddr)); ip != nil {
		return ip.String()
	}
	return r.RemoteAddr
}

func DecodeJSON(r *http.Request, target any) error {
	defer r.Body.Close()
	limited := &io.LimitedReader{R: r.Body, N: (1 << 20) + 1}
	decoder := json.NewDecoder(limited)
	if err := decoder.Decode(target); err != nil {
		return BadRequest("请求 JSON 格式不正确。")
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return BadRequest("请求只能包含一个 JSON 对象。")
	}
	if limited.N <= 0 {
		return BadRequest("请求内容过大。")
	}
	return nil
}

func firstHeaderPart(value string) string {
	parts := strings.Split(value, ",")
	if len(parts) == 0 {
		return ""
	}
	return strings.TrimSpace(parts[0])
}

func RequestIsHTTPS(r *http.Request) bool {
	if r.TLS != nil {
		return true
	}
	if proto := firstHeaderPart(r.Header.Get("X-Forwarded-Proto")); strings.EqualFold(proto, "https") {
		return true
	}
	if strings.EqualFold(strings.TrimSpace(r.Header.Get("X-Forwarded-Ssl")), "on") {
		return true
	}
	if strings.Contains(strings.ToLower(r.Header.Get("Cf-Visitor")), `"scheme":"https"`) {
		return true
	}
	return false
}

func validHeaderIP(value string) string {
	value = firstHeaderPart(value)
	if value == "" {
		return ""
	}
	ip := net.ParseIP(strings.TrimSpace(value))
	if ip == nil {
		return ""
	}
	return ip.String()
}
