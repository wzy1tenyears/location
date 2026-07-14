package handlers

import (
	"database/sql"
	"net/http"
	"time"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/repositories"
)

type InviteHandler struct {
	rates hitLimiter
}

func NewInviteHandler(db *sql.DB) InviteHandler {
	return InviteHandler{
		rates: repositories.NewRateLimitRepository(db),
	}
}

func (handler InviteHandler) Check(w http.ResponseWriter, r *http.Request) {
	allowed, err := handler.rates.Hit(r.Context(), "invite_check", httpx.ClientIP(r), 20, 10*time.Minute)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if !allowed {
		httpx.Error(w, httpx.APIError{Status: http.StatusTooManyRequests, Message: "邀请码检查过于频繁，请稍后再试。"})
		return
	}

	httpx.OK(w, map[string]any{
		"ok":      true,
		"message": "邀请码将在注册时验证。",
	})
}
