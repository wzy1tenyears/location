package handlers

import (
	"net/http"
	"strconv"
	"strings"
	"time"

	"familylocation/location-v3/internal/httpx"
)

func allowScopedRead(
	w http.ResponseWriter,
	r *http.Request,
	limiter hitLimiter,
	bucket string,
	userID int64,
	userMaxHits int,
	ipMaxHits int,
	window time.Duration,
) bool {
	identities := []struct {
		bucket   string
		identity string
		maxHits  int
	}{
		{bucket: bucket + "_user", identity: strconv.FormatInt(userID, 10), maxHits: userMaxHits},
	}
	if clientIP := strings.TrimSpace(httpx.ClientIP(r)); clientIP != "" {
		identities = append(identities, struct {
			bucket   string
			identity string
			maxHits  int
		}{bucket: bucket + "_ip", identity: clientIP, maxHits: ipMaxHits})
	}

	for _, identity := range identities {
		allowed, err := limiter.Hit(r.Context(), identity.bucket, identity.identity, identity.maxHits, window)
		if err != nil {
			httpx.Error(w, err)
			return false
		}
		if !allowed {
			retryAfterSeconds := maxInt(1, int(window/time.Second))
			w.Header().Set("Retry-After", strconv.Itoa(retryAfterSeconds))
			httpx.Error(w, httpx.APIError{
				Status:  http.StatusTooManyRequests,
				Message: "请求过于频繁，请稍后再试。",
				Code:    "rate_limited",
				Details: map[string]any{"retry_after_seconds": retryAfterSeconds},
			})
			return false
		}
	}
	return true
}
