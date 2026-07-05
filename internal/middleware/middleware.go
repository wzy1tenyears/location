package middleware

import (
	"log"
	"net/http"
	"strings"
	"time"

	"familylocation/location-v3/internal/config"
	"familylocation/location-v3/internal/httpx"
)

type Middleware func(http.Handler) http.Handler

func Chain(handler http.Handler, middlewares ...Middleware) http.Handler {
	for i := len(middlewares) - 1; i >= 0; i-- {
		handler = middlewares[i](handler)
	}
	return handler
}

func Recover(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if recovered := recover(); recovered != nil {
				log.Printf("panic: %v", recovered)
				httpx.Error(w, nil)
			}
		}()
		next.ServeHTTP(w, r)
	})
}

func AccessLog(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		log.Printf("%s %s %s %s", r.Method, r.URL.Path, httpx.ClientIP(r), time.Since(start).Round(time.Millisecond))
	})
}

func RequireAppUserAgent(cfg config.AppConfig) Middleware {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if !strings.Contains(r.UserAgent(), cfg.UserAgentToken) {
				httpx.Error(w, httpx.Forbidden("Only loc-app client is allowed."))
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

