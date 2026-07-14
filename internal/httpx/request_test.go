package httpx

import (
	"crypto/tls"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestClientIPTrustsOnlyLoopbackReverseProxy(t *testing.T) {
	proxied := httptest.NewRequest(http.MethodGet, "/", nil)
	proxied.RemoteAddr = "127.0.0.1:41000"
	proxied.Header.Set("X-Real-IP", "203.0.113.8")
	if got := ClientIP(proxied); got != "203.0.113.8" {
		t.Fatalf("ClientIP(loopback proxy) = %q", got)
	}

	direct := httptest.NewRequest(http.MethodGet, "/", nil)
	direct.RemoteAddr = "198.51.100.25:41000"
	direct.Header.Set("X-Real-IP", "203.0.113.9")
	direct.Header.Set("X-Forwarded-For", "203.0.113.10")
	if got := ClientIP(direct); got != "198.51.100.25" {
		t.Fatalf("ClientIP(direct spoof) = %q", got)
	}
}

func TestForwardedSchemeAndHostRequireLoopbackProxy(t *testing.T) {
	direct := httptest.NewRequest(http.MethodGet, "http://origin.example/", nil)
	direct.RemoteAddr = "198.51.100.25:41000"
	direct.Header.Set("X-Forwarded-Proto", "https")
	direct.Header.Set("X-Forwarded-Host", "attacker.example")
	if RequestIsHTTPS(direct) {
		t.Fatal("direct request spoofed the HTTPS boundary")
	}
	if got := PublicURL(direct, "/share?id=1"); got != "http://origin.example/share?id=1" {
		t.Fatalf("PublicURL(direct spoof) = %q", got)
	}

	proxied := direct.Clone(direct.Context())
	proxied.RemoteAddr = "[::1]:41000"
	if !RequestIsHTTPS(proxied) {
		t.Fatal("loopback proxy HTTPS marker was ignored")
	}
	if got := PublicURL(proxied, "/share?id=1"); got != "https://attacker.example/share?id=1" {
		t.Fatalf("PublicURL(loopback proxy) = %q", got)
	}

	tlsRequest := httptest.NewRequest(http.MethodGet, "https://origin.example/", nil)
	tlsRequest.RemoteAddr = "198.51.100.25:41000"
	tlsRequest.TLS = &tls.ConnectionState{}
	if !RequestIsHTTPS(tlsRequest) {
		t.Fatal("direct TLS request was not recognized")
	}
}

func TestDecodeJSONRejectsMalformedBody(t *testing.T) {
	request := httptest.NewRequest("POST", "/", strings.NewReader(`{"value":`))
	var payload map[string]any
	err := DecodeJSON(request, &payload)
	var apiErr APIError
	if !errors.As(err, &apiErr) || apiErr.Status != 400 {
		t.Fatalf("DecodeJSON() error = %#v, want APIError 400", err)
	}
}

func TestDecodeJSONRejectsTrailingObject(t *testing.T) {
	request := httptest.NewRequest("POST", "/", strings.NewReader(`{"value":1} {"value":2}`))
	var payload map[string]any
	if err := DecodeJSON(request, &payload); err == nil {
		t.Fatal("DecodeJSON() accepted two JSON objects")
	}
}
