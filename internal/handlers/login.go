package handlers

import (
	"crypto/subtle"
	"database/sql"
	"net/http"
	"regexp"
	"strings"
	"time"

	"familylocation/location-v3/internal/config"
	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/models"
	"familylocation/location-v3/internal/repositories"
	"familylocation/location-v3/internal/services"
	"familylocation/location-v3/internal/session"
)

type LoginHandler struct {
	cfg        config.Config
	users      repositories.UserRepository
	groups     repositories.GroupRepository
	devices    repositories.DeviceRepository
	limits     repositories.AuthLimitRepository
	rates      repositories.RateLimitRepository
	challenges repositories.AppChallengeRepository
	sessions   session.Store
}

func NewLoginHandler(cfg config.Config, db *sql.DB, sessions session.Reader) LoginHandler {
	return LoginHandler{
		cfg:        cfg,
		users:      repositories.NewUserRepository(db),
		groups:     repositories.NewGroupRepository(db),
		devices:    repositories.NewDeviceRepository(db),
		limits:     repositories.NewAuthLimitRepository(db),
		rates:      repositories.NewRateLimitRepository(db),
		challenges: repositories.NewAppChallengeRepository(db),
		sessions:   session.Store{CookieName: sessions.CookieName, SavePath: sessions.SavePath, Lifetime: cfg.App.SessionLifetime},
	}
}

type loginRequest struct {
	Username                    string `json:"username"`
	Password                    string `json:"password"`
	TermsAccepted               bool   `json:"terms_accepted"`
	CrossBorderTransferAccepted bool   `json:"cross_border_transfer_accepted"`
	TurnstileToken              string `json:"turnstile_token"`
	BrowserFingerprint          string `json:"browser_fingerprint"`
}

func (handler LoginHandler) Login(w http.ResponseWriter, r *http.Request) {
	allowed, err := handler.rates.Hit(r.Context(), "login", httpx.ClientIP(r), 30, 5*time.Minute)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if !allowed {
		httpx.Error(w, httpx.APIError{Status: http.StatusTooManyRequests, Message: "请求过于频繁，请稍后再试。"})
		return
	}

	var req loginRequest
	_ = httpx.DecodeJSON(r, &req)
	req.Username = strings.TrimSpace(req.Username)
	req.TurnstileToken = strings.TrimSpace(req.TurnstileToken)
	req.BrowserFingerprint = sanitizeBrowserFingerprint(req.BrowserFingerprint)

	if req.Username == "" || req.Password == "" {
		httpx.Error(w, httpx.Unprocessable("请输入账号和密码。"))
		return
	}
	if err := handler.verifyTurnstile(r, req.TurnstileToken, "login"); err != nil {
		httpx.Error(w, err)
		return
	}

	if subtle.ConstantTimeCompare([]byte(req.Username), []byte(handler.cfg.Auth.AdminUsername)) == 1 {
		handler.loginAdmin(w, r, req)
		return
	}
	handler.loginUser(w, r, req)
}

func (handler LoginHandler) loginAdmin(w http.ResponseWriter, r *http.Request, req loginRequest) {
	locked, err := handler.limits.AdminLoginLocked(r.Context(), httpx.ClientIP(r), 30*time.Minute)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if locked {
		httpx.Error(w, httpx.APIError{Status: 423, Message: "管理员登录尝试过多，请 30 分钟后再试。"})
		return
	}
	if !handler.adminPasswordMatches(req.Password) {
		lockedNow, err := handler.limits.RecordFailedAdminLogin(r.Context(), httpx.ClientIP(r), 5, 30*time.Minute)
		if err != nil {
			httpx.Error(w, err)
			return
		}
		if lockedNow {
			httpx.Error(w, httpx.APIError{Status: 423, Message: "管理员登录尝试过多，请 30 分钟后再试。"})
			return
		}
		httpx.Error(w, httpx.APIError{Status: http.StatusUnauthorized, Message: "账号或密码错误。"})
		return
	}
	if err := handler.limits.ClearFailedAdminLogin(r.Context(), httpx.ClientIP(r)); err != nil {
		httpx.Error(w, err)
		return
	}
	if _, err := handler.sessions.StartAdminSession(w, r); err != nil {
		httpx.Error(w, err)
		return
	}
	_ = handler.users.RecordLog(r.Context(), nil, "", "admin_login", "管理员登录", nil, httpx.ClientIP(r), r.UserAgent())
	httpx.OK(w, map[string]any{
		"ok":             true,
		"redirect":       "/" + strings.Trim(handler.cfg.Auth.AdminPath, "/") + "/",
		"admin_username": handler.cfg.Auth.AdminUsername,
	})
}

func (handler LoginHandler) loginUser(w http.ResponseWriter, r *http.Request, req loginRequest) {
	user, err := handler.users.FindByUsername(r.Context(), req.Username)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if user == nil {
		httpx.Error(w, httpx.APIError{Status: http.StatusUnauthorized, Message: "账号或密码错误。"})
		return
	}
	if user.LoginLockedAt.Valid && user.LoginLockedAt.Time.Before(time.Now().Add(-30*time.Minute)) {
		_ = handler.users.ClearFailedLogin(r.Context(), user.ID)
		user.LoginLockedAt.Valid = false
		user.FailedLoginCount = 0
	}
	if user.LoginLockedAt.Valid && user.LoginLockedAt.Time.After(time.Now().Add(-30*time.Minute)) {
		httpx.Error(w, httpx.APIError{Status: 423, Message: "账号已锁定 30 分钟，请稍后再试。"})
		return
	}
	if !services.CheckPassword(req.Password, user.PasswordHash) {
		lockedNow, err := handler.users.RecordFailedLogin(r.Context(), *user, 3, time.Now())
		if err != nil {
			httpx.Error(w, err)
			return
		}
		if lockedNow {
			httpx.Error(w, httpx.APIError{Status: 423, Message: "账号已锁定 30 分钟，请稍后再试。"})
			return
		}
		httpx.Error(w, httpx.APIError{Status: http.StatusUnauthorized, Message: "账号或密码错误。"})
		return
	}
	if !user.IsActive {
		message := "账号已停用，请联系管理员。"
		if strings.TrimSpace(user.DisabledReason) != "" {
			message = "账号已停用：" + strings.TrimSpace(user.DisabledReason)
		}
		httpx.Error(w, httpx.Forbidden(message))
		return
	}
	if !req.TermsAccepted {
		httpx.Error(w, httpx.Forbidden("请先同意用户协议和隐私条约。"))
		return
	}
	if !req.CrossBorderTransferAccepted {
		httpx.Error(w, httpx.Forbidden("请先同意用户数据跨境加密传输协议。"))
		return
	}
	deviceFingerprint, ok := requestDeviceFingerprint(r, handler.cfg.App.DeviceCookieName)
	if !ok {
		httpx.Error(w, httpx.Forbidden("请使用新版 App 登录。"))
		return
	}
	if err := handler.bindUserDevice(r, *user, deviceFingerprint, req.BrowserFingerprint); err != nil {
		httpx.Error(w, err)
		return
	}
	if _, err := handler.sessions.StartUserSession(w, r, user.ID); err != nil {
		httpx.Error(w, err)
		return
	}
	acceptedAt := time.Now()
	_ = handler.users.ClearFailedLogin(r.Context(), user.ID)
	_ = handler.users.SetTermsAccepted(r.Context(), user.ID, acceptedAt)
	user.TermsAcceptedAt.Valid = true
	user.TermsAcceptedAt.Time = acceptedAt
	user.UserAgreementAcceptedAt.Valid = true
	user.UserAgreementAcceptedAt.Time = acceptedAt
	user.PrivacyPolicyAcceptedAt.Valid = true
	user.PrivacyPolicyAcceptedAt.Time = acceptedAt
	user.CrossBorderTransferAcceptedAt.Valid = true
	user.CrossBorderTransferAcceptedAt.Time = acceptedAt
	_ = handler.users.TouchPresence(r.Context(), user.ID, user.GroupName, r.UserAgent(), httpx.ClientIP(r))
	id := user.ID
	_ = handler.users.RecordLog(r.Context(), &id, user.GroupName, "online", "用户登录", nil, httpx.ClientIP(r), r.UserAgent())
	groups, _ := handler.groups.ForUser(r.Context(), user.ID)
	httpx.OK(w, map[string]any{
		"ok":   true,
		"user": services.PublicUserPayloadForGroups(*user, groups, firstMembership(groups)),
	})
}

func (handler LoginHandler) bindUserDevice(r *http.Request, user models.User, deviceFingerprint string, browserFingerprint string) error {
	existing, err := handler.devices.FindByFingerprint(r.Context(), deviceFingerprint)
	if err != nil {
		return err
	}
	if existing != nil && existing.UserID != user.ID {
		if user.DebugMode {
			id := user.ID
			_ = handler.users.RecordLog(r.Context(), &id, "", "device_bind_skipped_debug", "调试模式跳过设备指纹冲突", map[string]any{"device_fingerprint": deviceFingerprint, "bound_user_id": existing.UserID}, httpx.ClientIP(r), r.UserAgent())
			return nil
		}
		return httpx.Forbidden("该设备已绑定其他账号。")
	}
	if existing == nil {
		count, err := handler.devices.CountByUser(r.Context(), user.ID)
		if err != nil {
			return err
		}
		if !user.DebugMode && count >= 3 {
			return httpx.Forbidden("该账号绑定设备已达 3 台，请联系后台删除旧设备。")
		}
		if err := handler.devices.Insert(r.Context(), user.ID, deviceFingerprint, browserFingerprint, r.UserAgent()); err != nil {
			return err
		}
		id := user.ID
		_ = handler.users.RecordLog(r.Context(), &id, "", "device_bind", "绑定新设备指纹", nil, httpx.ClientIP(r), r.UserAgent())
		return nil
	}
	return handler.devices.UpdateSeen(r.Context(), existing.ID, browserFingerprint, r.UserAgent())
}

func (handler LoginHandler) verifyTurnstile(r *http.Request, token string, purpose string) error {
	deviceFingerprint, _ := requestDeviceFingerprint(r, handler.cfg.App.DeviceCookieName)
	if strings.HasPrefix(token, "app:") {
		ok, err := consumeAppChallengeToken(r.Context(), token, purpose, handler.cfg, handler.challenges, deviceFingerprint)
		if err != nil {
			return err
		}
		if ok {
			return nil
		}
	}
	if strings.TrimSpace(handler.cfg.External.TurnstileSecretKey) == "" {
		return nil
	}
	if strings.TrimSpace(token) == "" {
		return httpx.Forbidden("请先完成人机验证。")
	}
	ok, err := verifyTurnstileSiteToken(token, handler.cfg.External.TurnstileSecretKey, httpx.ClientIP(r))
	if err != nil || !ok {
		return httpx.Forbidden("人机验证失败，请重试。")
	}
	return nil
}

func (handler LoginHandler) adminPasswordMatches(password string) bool {
	if strings.TrimSpace(handler.cfg.Auth.AdminPasswordHash) != "" {
		return services.CheckPassword(password, handler.cfg.Auth.AdminPasswordHash)
	}
	return subtle.ConstantTimeCompare([]byte(password), []byte(handler.cfg.Auth.AdminPassword)) == 1
}

func firstMembership(groups []models.Membership) *models.Membership {
	if len(groups) == 0 {
		return nil
	}
	return &groups[0]
}

func sanitizeBrowserFingerprint(value string) string {
	value = strings.TrimSpace(value)
	if matched, _ := regexp.MatchString(`^[a-zA-Z0-9:_-]{1,128}$`, value); matched {
		return value
	}
	return ""
}

func requestDeviceFingerprint(r *http.Request, cookieName string) (string, bool) {
	if strings.TrimSpace(cookieName) == "" {
		cookieName = "loc_device"
	}
	value := strings.ToLower(strings.TrimSpace(cookieValue(r, cookieName)))
	return value, deviceFingerprintPattern.MatchString(value)
}

func cookieValue(r *http.Request, name string) string {
	cookie, err := r.Cookie(name)
	if err != nil {
		return ""
	}
	return cookie.Value
}
