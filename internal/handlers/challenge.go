package handlers

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"html"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"

	"familylocation/location-v3/internal/config"
	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/models"
	"familylocation/location-v3/internal/repositories"
)

var challengeIDPattern = regexp.MustCompile(`^[a-f0-9]{32}$`)
var challengeSecretPattern = regexp.MustCompile(`^[a-f0-9]{64}$`)
var deviceFingerprintPattern = regexp.MustCompile(`^[a-f0-9]{64}$`)

const appChallengeVerificationLease = 15 * time.Second

type ChallengeHandler struct {
	cfg               config.Config
	rates             hitLimiter
	store             repositories.AppChallengeRepository
	settings          appChallengeSettingReader
	turnstileVerifier func(token string, secret string, remoteIP string) (bool, error)
}

type appChallengeSettingReader interface {
	AppChallengeRequired(ctx context.Context) (bool, error)
}

func NewChallengeHandler(cfg config.Config, db *sql.DB) ChallengeHandler {
	return ChallengeHandler{
		cfg:               cfg,
		rates:             repositories.NewRateLimitRepository(db),
		store:             repositories.NewAppChallengeRepository(db),
		settings:          repositories.NewSettingRepository(db),
		turnstileVerifier: verifyTurnstileSiteToken,
	}
}

func (handler ChallengeHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	_ = handler.store.DeleteExpiredIfDue(r.Context(), time.Now(), time.Minute, 500)
	if r.Method == http.MethodPost && strings.Contains(strings.ToLower(r.Header.Get("Content-Type")), "application/json") {
		handler.start(w, r)
		return
	}
	if r.Method == http.MethodGet && strings.TrimSpace(r.URL.Query().Get("secret")) != "" {
		handler.poll(w, r)
		return
	}
	if r.Method == http.MethodPost {
		handler.verifyBrowser(w, r)
		return
	}
	handler.render(w, r, "")
}

func (handler ChallengeHandler) start(w http.ResponseWriter, r *http.Request) {
	if !strings.Contains(r.UserAgent(), handler.cfg.App.UserAgentToken) {
		httpx.Error(w, httpx.Forbidden("Only loc-app client is allowed."))
		return
	}
	allowed, err := handler.rates.Hit(r.Context(), "app_challenge_start", httpx.ClientIP(r), 20, 5*time.Minute)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if !allowed {
		httpx.Error(w, httpx.APIError{Status: http.StatusTooManyRequests, Message: "请求过于频繁，请稍后再试。"})
		return
	}

	var req struct {
		Purpose string `json:"purpose"`
	}
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.Error(w, err)
		return
	}
	purpose := strings.TrimSpace(req.Purpose)
	if purpose != "login" && purpose != "register" {
		httpx.Error(w, httpx.Unprocessable("Invalid challenge purpose."))
		return
	}
	required, err := appChallengeRequired(r.Context(), handler.settings)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if !required {
		httpx.OK(w, map[string]any{"ok": true, "challenge_required": false, "app_challenge_token": ""})
		return
	}
	if strings.TrimSpace(handler.cfg.External.TurnstileSecretKey) == "" {
		httpx.OK(w, map[string]any{"ok": true, "challenge_required": false, "app_challenge_token": ""})
		return
	}
	if strings.TrimSpace(handler.cfg.External.TurnstileSiteKey) == "" {
		httpx.Error(w, httpx.APIError{Status: http.StatusInternalServerError, Message: "Turnstile Site Key is not configured for App challenge."})
		return
	}

	deviceFingerprint, ok := requestDeviceFingerprint(r, handler.cfg.App.DeviceCookieName)
	if !ok {
		httpx.Error(w, httpx.Forbidden("请使用新版 App 登录。"))
		return
	}
	challengeID, err := randomHex(16)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	secret, err := randomHex(32)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	challenge := models.AppChallenge{
		ID:                challengeID,
		SecretHash:        sha256Hex(secret),
		DeviceFingerprint: deviceFingerprint,
		Purpose:           purpose,
		ExpiresAt:         time.Now().Add(5 * time.Minute),
	}
	if err := handler.store.Insert(r.Context(), challenge); err != nil {
		httpx.Error(w, err)
		return
	}

	httpx.OK(w, map[string]any{
		"ok":                 true,
		"challenge_required": true,
		"challenge_id":       challengeID,
		"challenge_secret":   secret,
		"challenge_url":      handler.publicURL(r, challengeID, deviceFingerprint),
		"expires_in":         300,
	})
}

func appChallengeRequired(ctx context.Context, settings appChallengeSettingReader) (bool, error) {
	if settings == nil {
		return true, nil
	}
	return settings.AppChallengeRequired(ctx)
}

func (handler ChallengeHandler) poll(w http.ResponseWriter, r *http.Request) {
	if !strings.Contains(r.UserAgent(), handler.cfg.App.UserAgentToken) {
		httpx.Error(w, httpx.Forbidden("Only loc-app client is allowed."))
		return
	}
	allowed, err := handler.rates.Hit(r.Context(), "app_challenge_poll", httpx.ClientIP(r), 60, 5*time.Minute)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if !allowed {
		httpx.Error(w, httpx.APIError{Status: http.StatusTooManyRequests, Message: "请求过于频繁，请稍后再试。"})
		return
	}
	challengeID := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("id")))
	secret := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("secret")))
	deviceFingerprint, ok := requestDeviceFingerprint(r, handler.cfg.App.DeviceCookieName)
	if !ok || !challengeIDPattern.MatchString(challengeID) || !challengeSecretPattern.MatchString(secret) {
		httpx.Error(w, httpx.Unprocessable("Invalid challenge token."))
		return
	}
	challenge, err := handler.store.FindByIDAndDevice(r.Context(), challengeID, deviceFingerprint)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if challenge == nil || !hmac.Equal([]byte(challenge.SecretHash), []byte(sha256Hex(secret))) {
		httpx.Error(w, httpx.APIError{Status: http.StatusNotFound, Message: "Challenge not found."})
		return
	}
	if repositories.ChallengeExpired(challenge, time.Now()) {
		httpx.Error(w, httpx.APIError{Status: http.StatusGone, Message: "Challenge expired."})
		return
	}
	if challenge.ConsumedAt.Valid {
		httpx.Error(w, httpx.APIError{Status: http.StatusConflict, Message: "Challenge already used."})
		return
	}
	verified := challenge.VerifiedAt.Valid
	httpx.OK(w, map[string]any{
		"ok":                  true,
		"verified":            verified,
		"app_challenge_token": ternary(verified, "app:"+challengeID+":"+secret, ""),
		"purpose":             challenge.Purpose,
	})
}

func (handler ChallengeHandler) verifyBrowser(w http.ResponseWriter, r *http.Request) {
	allowed, err := handler.rates.Hit(r.Context(), "app_challenge_verify", httpx.ClientIP(r), 20, 5*time.Minute)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if !allowed {
		httpx.Error(w, httpx.APIError{Status: http.StatusTooManyRequests, Message: "请求过于频繁，请稍后再试。"})
		return
	}
	if err := r.ParseForm(); err != nil {
		handler.render(w, r, "请求格式不正确。")
		return
	}
	challengeID := strings.TrimSpace(r.FormValue("challenge_id"))
	turnstileToken := strings.TrimSpace(r.FormValue("cf-turnstile-response"))
	message := handler.verifyBrowserChallenge(r, challengeID, turnstileToken)
	handler.render(w, r, message)
}

func (handler ChallengeHandler) verifyBrowserChallenge(r *http.Request, challengeID string, turnstileToken string) string {
	if !challengeIDPattern.MatchString(strings.ToLower(challengeID)) {
		return "质询编号无效，请回到 App 重新发起。"
	}
	if turnstileToken == "" {
		return "请先完成 Cloudflare 质询。"
	}
	deviceFingerprint := handler.browserChallengeDeviceFingerprint(r)
	if deviceFingerprint == "" {
		return "设备 Cookie 不一致，请回到 App 重新发起。"
	}
	challenge, err := handler.store.FindByIDAndDevice(r.Context(), strings.ToLower(challengeID), deviceFingerprint)
	if err != nil || challenge == nil {
		return "质询不存在，请回到 App 重新发起。"
	}
	if challenge.ConsumedAt.Valid {
		return "质询已使用，请回到 App 重新发起。"
	}
	if repositories.ChallengeExpired(challenge, time.Now()) {
		return "质询已过期，请回到 App 重新发起。"
	}
	if challenge.VerifiedAt.Valid {
		return "验证已完成，请回到 App 继续登录。"
	}
	allowed, err := handler.rates.Hit(r.Context(), "app_challenge_verify_id", challenge.ID, 1, appChallengeVerificationLease)
	if err != nil {
		return "验证请求保存失败，请重试。"
	}
	if !allowed {
		return "验证正在处理中，请稍后重试。"
	}
	ok, err := handler.verifyTurnstileToken(turnstileToken, handler.cfg.External.TurnstileSecretKey, httpx.ClientIP(r))
	if err != nil || !ok {
		return "Cloudflare 验证失败，请重试。"
	}
	if err := handler.store.MarkVerified(r.Context(), challenge.ID, deviceFingerprint); err != nil {
		return "验证状态保存失败，请重试。"
	}
	return "验证已完成，请回到 App 继续登录。"
}

func (handler ChallengeHandler) verifyTurnstileToken(token string, secret string, remoteIP string) (bool, error) {
	if handler.turnstileVerifier != nil {
		return handler.turnstileVerifier(token, secret, remoteIP)
	}
	return verifyTurnstileSiteToken(token, secret, remoteIP)
}

func (handler ChallengeHandler) render(w http.ResponseWriter, r *http.Request, message string) {
	challengeID := strings.TrimSpace(r.URL.Query().Get("id"))
	if r.Method == http.MethodPost {
		challengeID = strings.TrimSpace(r.FormValue("challenge_id"))
	}
	if !challengeIDPattern.MatchString(strings.ToLower(challengeID)) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte("Invalid challenge."))
		return
	}
	verified := false
	if challenge, err := handler.store.FindByIDAndDevice(r.Context(), strings.ToLower(challengeID), handler.browserChallengeDeviceFingerprint(r)); err == nil && challenge != nil {
		verified = challenge.VerifiedAt.Valid
	}
	siteKey := strings.TrimSpace(handler.cfg.External.TurnstileSiteKey)
	deviceToken := strings.TrimSpace(r.URL.Query().Get("device_token"))
	if r.Method == http.MethodPost && deviceToken == "" {
		deviceToken = strings.TrimSpace(r.FormValue("device_token"))
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = fmt.Fprintf(w, "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>CF Challenge</title><script src=\"https://challenges.cloudflare.com/turnstile/v0/api.js\" async defer></script><style>html,body{margin:0;width:100%%;height:92px;min-height:0;background:transparent;overflow:hidden;}body{display:grid;place-items:center;font-family:system-ui,-apple-system,BlinkMacSystemFont,\"Segoe UI\",sans-serif;}form{width:304px;height:78px;margin:0;padding:0;display:grid;place-items:center;overflow:hidden;}.cf-turnstile{width:300px;min-height:65px;}.message{color:#1f2d2a;font-size:13px;line-height:1.4;text-align:center;}</style><script>function notifyNativeChallengeComplete(){if(window.LocChallenge&&window.LocChallenge.complete){window.LocChallenge.complete();}}function notifyNativeChallengeToken(token){if(window.LocChallenge&&window.LocChallenge.completeToken){window.LocChallenge.completeToken(String(token||''));}}</script></head><body data-message=\"%s\">", html.EscapeString(message))
	if verified {
		_, _ = w.Write([]byte("<script>notifyNativeChallengeComplete();</script></body></html>"))
		return
	}
	if siteKey == "" {
		_, _ = w.Write([]byte("<div class=\"message\">Turnstile Site Key is not configured.</div></body></html>"))
		return
	}
	_, _ = fmt.Fprintf(w, "<form method=\"post\"><input type=\"hidden\" name=\"challenge_id\" value=\"%s\"><input type=\"hidden\" name=\"device_token\" value=\"%s\"><div class=\"cf-turnstile\" data-sitekey=\"%s\" data-callback=\"onTurnstileSuccess\"></div></form><script>function onTurnstileSuccess(token){notifyNativeChallengeToken(token);notifyNativeChallengeComplete();if(!(window.LocChallenge&&window.LocChallenge.completeToken)){document.forms[0].submit();}}</script></body></html>", html.EscapeString(strings.ToLower(challengeID)), html.EscapeString(deviceToken), html.EscapeString(siteKey))
}

func (handler ChallengeHandler) browserChallengeDeviceFingerprint(r *http.Request) string {
	if fingerprint := appChallengeDeviceFingerprintFromToken(strings.TrimSpace(firstString(r.FormValue("device_token"), r.URL.Query().Get("device_token"))), tokenSigningSecret(handler.cfg)); fingerprint != "" {
		return fingerprint
	}
	deviceCookie := strings.ToLower(strings.TrimSpace(cookieValue(r, handler.cfg.App.DeviceCookieName)))
	if deviceFingerprintPattern.MatchString(deviceCookie) {
		return deviceCookie
	}
	return ""
}

func (handler ChallengeHandler) publicURL(r *http.Request, challengeID string, deviceFingerprint string) string {
	path := "/api/app-challenge?id=" + url.QueryEscape(challengeID)
	if token := appChallengeDeviceToken(deviceFingerprint, tokenSigningSecret(handler.cfg)); token != "" {
		path += "&device_token=" + url.QueryEscape(token)
	}
	return httpx.PublicURL(r, path)
}

func consumeAppChallengeToken(ctx context.Context, token string, purpose string, cfg config.Config, repo repositories.AppChallengeRepository, deviceFingerprint string) (bool, error) {
	if !strings.HasPrefix(token, "app:") {
		return false, nil
	}
	parts := strings.SplitN(token, ":", 3)
	if len(parts) != 3 {
		return false, nil
	}
	challengeID := strings.ToLower(strings.TrimSpace(parts[1]))
	secret := strings.ToLower(strings.TrimSpace(parts[2]))
	if !challengeIDPattern.MatchString(challengeID) || !challengeSecretPattern.MatchString(secret) || !deviceFingerprintPattern.MatchString(deviceFingerprint) {
		return false, nil
	}
	challenge, err := repo.FindVerifiedConsumable(ctx, challengeID, purpose, deviceFingerprint)
	if err != nil || challenge == nil {
		return false, err
	}
	if !hmac.Equal([]byte(challenge.SecretHash), []byte(sha256Hex(secret))) {
		return false, nil
	}
	return repo.Consume(ctx, challengeID)
}

func verifyTurnstileSiteToken(token string, secret string, remoteIP string) (bool, error) {
	if strings.TrimSpace(secret) == "" {
		return true, nil
	}
	form := url.Values{}
	form.Set("secret", secret)
	form.Set("response", token)
	form.Set("remoteip", remoteIP)
	client := &http.Client{Timeout: 8 * time.Second}
	resp, err := client.PostForm("https://challenges.cloudflare.com/turnstile/v0/siteverify", form)
	if err != nil {
		return false, err
	}
	defer resp.Body.Close()
	var payload map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		return false, err
	}
	success, _ := payload["success"].(bool)
	return success, nil
}

func appChallengeDeviceToken(deviceFingerprint string, secret string) string {
	deviceFingerprint = strings.ToLower(strings.TrimSpace(deviceFingerprint))
	if !deviceFingerprintPattern.MatchString(deviceFingerprint) {
		return ""
	}
	return deviceFingerprint + "." + hmacHex(deviceFingerprint, secret)
}

func appChallengeDeviceFingerprintFromToken(token string, secret string) string {
	matches := regexp.MustCompile(`^([a-f0-9]{64})\.([a-f0-9]{64})$`).FindStringSubmatch(strings.ToLower(strings.TrimSpace(token)))
	if len(matches) != 3 {
		return ""
	}
	if hmac.Equal([]byte(matches[2]), []byte(hmacHex(matches[1], secret))) {
		return matches[1]
	}
	return ""
}

func sha256Hex(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:])
}

func ternary(condition bool, yes string, no string) string {
	if condition {
		return yes
	}
	return no
}

func firstString(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return value
		}
	}
	return ""
}

func randomHex(bytes int) (string, error) {
	buffer := make([]byte, bytes)
	if _, err := rand.Read(buffer); err != nil {
		return "", err
	}
	return hex.EncodeToString(buffer), nil
}
