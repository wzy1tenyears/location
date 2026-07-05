package handlers

import (
	"log"
	"net/http"
	"net/http/httputil"
	"net/url"

	"familylocation/location-v3/internal/config"
)

type LegacyHandler struct {
	baseURL *url.URL
	proxy   *httputil.ReverseProxy
}

func NewLegacyHandler(cfg config.LegacyConfig) (*LegacyHandler, error) {
	base := cfg.BaseURL
	if base == "" {
		return &LegacyHandler{}, nil
	}

	target, err := url.Parse(base)
	if err != nil {
		return nil, err
	}

	proxy := httputil.NewSingleHostReverseProxy(target)
	originalDirector := proxy.Director
	proxy.Director = func(r *http.Request) {
		originalDirector(r)
		r.Host = target.Host
		r.Header.Set("X-Loc-V3-Legacy", "1")
	}
	proxy.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
		log.Printf("legacy proxy failed for %s: %v", r.URL.Path, err)
		http.Error(w, "legacy upstream unavailable", http.StatusBadGateway)
	}

	return &LegacyHandler{
		baseURL: target,
		proxy:   proxy,
	}, nil
}

func (handler *LegacyHandler) Enabled() bool {
	return handler != nil && handler.baseURL != nil && handler.proxy != nil
}

func (handler *LegacyHandler) Proxy(w http.ResponseWriter, r *http.Request) {
	if !handler.Enabled() {
		http.NotFound(w, r)
		return
	}
	handler.proxy.ServeHTTP(w, r)
}
