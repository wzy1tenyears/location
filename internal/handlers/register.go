package handlers

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"fmt"
	"io"
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

var registerUsernamePattern = regexp.MustCompile(`^[A-Za-z0-9_]{6,64}$`)
var registerInviteCodePattern = regexp.MustCompile(`^[0-9a-zA-Z]{1,255}$`)
var groupCodePattern = regexp.MustCompile(`^[0-9a-f]{32}$`)

const groupCodeBytes = 16

type RegisterHandler struct {
	cfg        config.Config
	db         *sql.DB
	users      repositories.UserRepository
	groups     repositories.GroupRepository
	devices    repositories.DeviceRepository
	rates      repositories.RateLimitRepository
	challenges repositories.AppChallengeRepository
	sessions   session.Store
}

func NewRegisterHandler(cfg config.Config, db *sql.DB, sessions session.Reader) RegisterHandler {
	return RegisterHandler{
		cfg:        cfg,
		db:         db,
		users:      repositories.NewUserRepository(db),
		groups:     repositories.NewGroupRepository(db),
		devices:    repositories.NewDeviceRepository(db),
		rates:      repositories.NewRateLimitRepository(db),
		challenges: repositories.NewAppChallengeRepository(db),
		sessions:   session.Store{CookieName: sessions.CookieName, Repo: sessions.Repo, Lifetime: cfg.App.SessionLifetime},
	}
}

type registerRequest struct {
	Username                    string `json:"username"`
	Password                    string `json:"password"`
	PasswordConfirm             string `json:"password_confirm"`
	DisplayName                 string `json:"display_name"`
	InviteCode                  string `json:"invite_code"`
	GroupName                   string `json:"group_name"`
	GroupCode                   string `json:"group_code"`
	TermsAccepted               bool   `json:"terms_accepted"`
	CrossBorderTransferAccepted bool   `json:"cross_border_transfer_accepted"`
	TurnstileToken              string `json:"turnstile_token"`
	BrowserFingerprint          string `json:"browser_fingerprint"`
}

func (handler RegisterHandler) Register(w http.ResponseWriter, r *http.Request) {
	allowed, err := handler.rates.Hit(r.Context(), "register", httpx.ClientIP(r), 20, time.Hour)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if !allowed {
		httpx.Error(w, httpx.APIError{Status: http.StatusTooManyRequests, Message: "请求过于频繁，请稍后再试。"})
		return
	}

	var req registerRequest
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.Error(w, err)
		return
	}
	req.Username = strings.TrimSpace(req.Username)
	req.DisplayName = strings.TrimSpace(req.DisplayName)
	req.InviteCode = strings.TrimSpace(req.InviteCode)
	req.GroupName = strings.TrimSpace(req.GroupName)
	req.GroupCode = strings.ToLower(strings.TrimSpace(req.GroupCode))
	req.TurnstileToken = strings.TrimSpace(req.TurnstileToken)
	req.BrowserFingerprint = sanitizeBrowserFingerprint(req.BrowserFingerprint)

	if err := handler.verifyTurnstile(r, req.TurnstileToken); err != nil {
		httpx.Error(w, err)
		return
	}
	if !validRegisterUsername(req.Username) {
		httpx.Error(w, httpx.Unprocessable("用户名需至少 6 位，并同时包含英文和数字。"))
		return
	}
	if len(req.Password) < 6 {
		httpx.Error(w, httpx.Unprocessable("密码至少 6 位。"))
		return
	}
	if req.Password != req.PasswordConfirm {
		httpx.Error(w, httpx.Unprocessable("两次输入的密码不一致。"))
		return
	}
	if req.InviteCode == "" {
		httpx.Error(w, httpx.Unprocessable("请输入邀请码。"))
		return
	}
	if !registerInviteCodePattern.MatchString(req.InviteCode) {
		httpx.Error(w, httpx.Unprocessable("邀请码格式不正确。"))
		return
	}
	if !req.TermsAccepted || !req.CrossBorderTransferAccepted {
		httpx.Error(w, httpx.Forbidden("请先同意全部协议。"))
		return
	}
	if strings.EqualFold(req.Username, handler.cfg.Auth.AdminUsername) {
		httpx.Error(w, httpx.APIError{Status: http.StatusConflict, Message: "用户名不可用。"})
		return
	}

	deviceFingerprint, ok := requestDeviceFingerprint(r, handler.cfg.App.DeviceCookieName)
	if !ok {
		httpx.Error(w, httpx.Forbidden("请使用新版 App 登录。"))
		return
	}
	existingDevice, err := handler.devices.FindByFingerprint(r.Context(), deviceFingerprint)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if existingDevice != nil {
		httpx.Error(w, httpx.Forbidden("该设备已绑定其他账号。"))
		return
	}
	if existingUser, err := handler.users.FindByUsername(r.Context(), req.Username); err != nil {
		httpx.Error(w, err)
		return
	} else if existingUser != nil {
		httpx.Error(w, httpx.APIError{Status: http.StatusConflict, Message: "用户名已存在。"})
		return
	}

	tx, err := handler.db.BeginTx(r.Context(), nil)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	defer func() { _ = tx.Rollback() }()

	invite, err := registerInviteForUpdate(r.Context(), tx, req.InviteCode)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if invite == nil || invite.UsedCount >= invite.MaxUses || !invite.IsActive {
		httpx.Error(w, httpx.Forbidden("邀请码无效或次数已用完。"))
		return
	}

	assignedGroupName := ""
	role := "guardian"
	assignedGroupNameOriginallyEmpty := strings.TrimSpace(invite.AssignedGroupName) == ""
	if invite.InviteType == "group_create" {
		assignedGroupName = strings.TrimSpace(invite.AssignedGroupName)
		if assignedGroupName == "" {
			if req.GroupName == "" {
				httpx.Error(w, httpx.Unprocessable("请填写要创建的家庭组名称。"))
				return
			}
			groupName, err := createFamilyGroupRecordTx(r.Context(), tx, req.GroupName, nil)
			if err != nil {
				httpx.Error(w, err)
				return
			}
			assignedGroupName = groupName
		}
		if err := ensureFamilyGroupRecordTx(r.Context(), tx, assignedGroupName, nil); err != nil {
			httpx.Error(w, err)
			return
		}
	} else {
		if !groupCodePattern.MatchString(req.GroupCode) {
			httpx.Error(w, httpx.Unprocessable("请填写 32 位家庭组号。"))
			return
		}
		groupName, err := findGroupNameByCodeTx(r.Context(), tx, req.GroupCode)
		if err != nil {
			httpx.Error(w, err)
			return
		}
		if groupName == "" {
			httpx.Error(w, httpx.APIError{Status: http.StatusNotFound, Message: "家庭组号不存在。"})
			return
		}
		assignedGroupName = groupName
	}

	passwordHash, err := services.HashPassword(req.Password)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	displayName := req.DisplayName
	if displayName == "" {
		displayName = req.Username
	}
	userID, err := insertUserTx(r.Context(), tx, req.Username, passwordHash, displayName, assignedGroupName, role)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if invite.InviteType == "group_create" && invite.AllowGroupOwner && assignedGroupNameOriginallyEmpty {
		_, err = tx.ExecContext(r.Context(), "UPDATE family_groups SET owner_user_id = ? WHERE group_name = ? AND owner_user_id IS NULL", userID, assignedGroupName)
		if err != nil {
			httpx.Error(w, err)
			return
		}
	}
	if invite.InviteType == "group_create" && assignedGroupNameOriginallyEmpty {
		_, err = tx.ExecContext(r.Context(), "UPDATE invite_codes SET assigned_group_name = ? WHERE id = ?", assignedGroupName, invite.ID)
		if err != nil {
			httpx.Error(w, err)
			return
		}
	}
	if _, err = tx.ExecContext(r.Context(), "INSERT INTO user_groups (user_id, group_name, role) VALUES (?, ?, ?)", userID, assignedGroupName, role); err != nil {
		httpx.Error(w, err)
		return
	}
	result, err := tx.ExecContext(r.Context(), "UPDATE invite_codes SET used_count = used_count + 1 WHERE id = ? AND is_active = 1 AND used_count < max_uses", invite.ID)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if rows, _ := result.RowsAffected(); rows != 1 {
		httpx.Error(w, httpx.Forbidden("邀请码无效或次数已用完。"))
		return
	}
	if err := tx.Commit(); err != nil {
		httpx.Error(w, err)
		return
	}

	user, err := handler.users.FindActiveByID(r.Context(), userID)
	if err != nil || user == nil {
		httpx.Error(w, err)
		return
	}
	if err := handler.bindDeviceAfterRegister(r, *user, deviceFingerprint, req.BrowserFingerprint); err != nil {
		httpx.Error(w, err)
		return
	}
	if _, err := handler.sessions.StartUserSession(w, r, userID, user.PasswordHash); err != nil {
		httpx.Error(w, err)
		return
	}
	_ = handler.users.TouchPresence(r.Context(), userID, assignedGroupName, r.UserAgent(), httpx.ClientIP(r))
	id := userID
	_ = handler.users.RecordLog(r.Context(), &id, assignedGroupName, "register", "用户注册", nil, httpx.ClientIP(r), r.UserAgent())
	groups, _ := handler.groups.ForUser(r.Context(), userID)
	httpx.OK(w, map[string]any{
		"ok":   true,
		"user": services.PublicUserPayloadForGroups(*user, groups, firstMembership(groups)),
	})
}

func validRegisterUsername(username string) bool {
	if !registerUsernamePattern.MatchString(username) {
		return false
	}
	hasLetter := false
	hasDigit := false
	for _, char := range username {
		if (char >= 'a' && char <= 'z') || (char >= 'A' && char <= 'Z') {
			hasLetter = true
		}
		if char >= '0' && char <= '9' {
			hasDigit = true
		}
	}
	return hasLetter && hasDigit
}

func (handler RegisterHandler) verifyTurnstile(r *http.Request, token string) error {
	deviceFingerprint, _ := requestDeviceFingerprint(r, handler.cfg.App.DeviceCookieName)
	if strings.HasPrefix(token, "app:") {
		ok, err := consumeAppChallengeToken(r.Context(), token, "register", handler.cfg, handler.challenges, deviceFingerprint)
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

func (handler RegisterHandler) bindDeviceAfterRegister(r *http.Request, user models.User, deviceFingerprint string, browserFingerprint string) error {
	return NewLoginHandler(handler.cfg, handler.db, session.Reader{CookieName: handler.sessions.CookieName, Repo: handler.sessions.Repo}).bindUserDevice(r, user, deviceFingerprint, browserFingerprint)
}

type inviteRow struct {
	ID                int64
	InviteType        string
	AllowGroupOwner   bool
	MaxUses           int
	UsedCount         int
	AssignedGroupName string
	IsActive          bool
}

func registerInviteForUpdate(ctx context.Context, tx *sql.Tx, code string) (*inviteRow, error) {
	var invite inviteRow
	err := tx.QueryRowContext(ctx, `
SELECT id, invite_type, allow_group_owner, max_uses, used_count, COALESCE(assigned_group_name, ''), is_active
FROM invite_codes
WHERE code = ? AND is_active = 1
LIMIT 1 FOR UPDATE`, code).Scan(
		&invite.ID,
		&invite.InviteType,
		&invite.AllowGroupOwner,
		&invite.MaxUses,
		&invite.UsedCount,
		&invite.AssignedGroupName,
		&invite.IsActive,
	)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &invite, nil
}

func insertUserTx(ctx context.Context, tx *sql.Tx, username string, passwordHash string, displayName string, groupName string, role string) (int64, error) {
	result, err := tx.ExecContext(ctx, `
INSERT INTO users
	(username, password_hash, display_name, group_name, role, terms_accepted_at, user_agreement_accepted_at, privacy_policy_accepted_at, cross_border_transfer_accepted_at)
VALUES
	(?, ?, ?, ?, ?, NOW(), NOW(), NOW(), NOW())`,
		username, passwordHash, displayName, groupName, role,
	)
	if err != nil {
		return 0, err
	}
	return result.LastInsertId()
}

func findGroupNameByCodeTx(ctx context.Context, tx *sql.Tx, groupCode string) (string, error) {
	var groupName string
	err := tx.QueryRowContext(ctx, "SELECT group_name FROM family_groups WHERE group_code = ? LIMIT 1", groupCode).Scan(&groupName)
	if err == sql.ErrNoRows {
		return "", nil
	}
	return groupName, err
}

func createFamilyGroupRecordTx(ctx context.Context, tx *sql.Tx, displayName string, ownerUserID *int64) (string, error) {
	displayName = strings.TrimSpace(displayName)
	if displayName == "" {
		return "", httpx.Unprocessable("请填写要创建的家庭组名称。")
	}
	code, err := generateGroupCodeTx(ctx, tx)
	if err != nil {
		return "", err
	}
	groupName, err := familyGroupInternalNameTx(ctx, tx, displayName, code)
	if err != nil {
		return "", err
	}
	_, err = tx.ExecContext(ctx, "INSERT INTO family_groups (group_name, display_name, group_code, owner_user_id) VALUES (?, ?, ?, ?)", groupName, displayName, code, ownerUserID)
	if err != nil {
		return "", err
	}
	return groupName, nil
}

func ensureFamilyGroupRecordTx(ctx context.Context, tx *sql.Tx, groupName string, ownerUserID *int64) error {
	groupName = strings.TrimSpace(groupName)
	if groupName == "" {
		return httpx.Unprocessable("家庭组名称不能为空。")
	}
	code, err := generateGroupCodeTx(ctx, tx)
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, "INSERT IGNORE INTO family_groups (group_name, display_name, group_code, owner_user_id) VALUES (?, ?, ?, ?)", groupName, groupName, code, ownerUserID)
	if err != nil {
		return err
	}
	var id int64
	var displayName string
	var groupCode string
	var owner sql.NullInt64
	err = tx.QueryRowContext(ctx, "SELECT id, display_name, group_code, owner_user_id FROM family_groups WHERE group_name = ? LIMIT 1", groupName).Scan(&id, &displayName, &groupCode, &owner)
	if err != nil {
		return err
	}
	if strings.TrimSpace(groupCode) == "" {
		code, err = generateGroupCodeTx(ctx, tx)
		if err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, "UPDATE family_groups SET group_code = ? WHERE id = ?", code, id)
		if err != nil {
			return err
		}
	}
	if strings.TrimSpace(displayName) == "" {
		_, err = tx.ExecContext(ctx, "UPDATE family_groups SET display_name = ? WHERE id = ?", groupName, id)
		if err != nil {
			return err
		}
	}
	if ownerUserID != nil && !owner.Valid {
		_, err = tx.ExecContext(ctx, "UPDATE family_groups SET owner_user_id = ? WHERE id = ?", *ownerUserID, id)
		if err != nil {
			return err
		}
	}
	return nil
}

func generateGroupCodeTx(ctx context.Context, tx *sql.Tx) (string, error) {
	for attempt := 0; attempt < 80; attempt++ {
		code, err := randomGroupCode(rand.Reader)
		if err != nil {
			return "", err
		}
		var exists int
		err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM family_groups WHERE group_code = ? LIMIT 1", code).Scan(&exists)
		if err != nil {
			return "", err
		}
		if exists == 0 {
			return code, nil
		}
	}
	return "", fmt.Errorf("failed to generate group code")
}

func randomGroupCode(reader io.Reader) (string, error) {
	raw := make([]byte, groupCodeBytes)
	if _, err := io.ReadFull(reader, raw); err != nil {
		return "", err
	}
	return hex.EncodeToString(raw), nil
}

func familyGroupInternalNameTx(ctx context.Context, tx *sql.Tx, displayName string, code string) (string, error) {
	base := []rune(strings.TrimSpace(displayName))
	if len(base) > 93 {
		base = base[:93]
	}
	candidate := string(base)
	var exists int
	err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM family_groups WHERE group_name = ? LIMIT 1", candidate).Scan(&exists)
	if err != nil {
		return "", err
	}
	if exists == 0 {
		return candidate, nil
	}
	suffixBase := base
	maxSuffixBase := 100 - 1 - len(code)
	if len(suffixBase) > maxSuffixBase {
		suffixBase = suffixBase[:maxSuffixBase]
	}
	return string(suffixBase) + "#" + code, nil
}
