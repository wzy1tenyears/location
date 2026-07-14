package handlers

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/repositories"
	"familylocation/location-v3/internal/services"
	"familylocation/location-v3/internal/session"
)

var wrappedKeyPattern = regexp.MustCompile(`^[A-Za-z0-9+/=_-]+$`)

type P2PHandler struct {
	db    *sql.DB
	scope scopedHandler
	users repositories.UserRepository
	rates repositories.RateLimitRepository
}

func NewP2PHandler(db *sql.DB, sessions session.Reader) P2PHandler {
	return P2PHandler{
		db:    db,
		scope: newScopedHandler(db, sessions),
		users: repositories.NewUserRepository(db),
		rates: repositories.NewRateLimitRepository(db),
	}
}

type p2pRequest struct {
	Action          string         `json:"action"`
	GroupName       string         `json:"group_name"`
	PublicKeyJWK    map[string]any `json:"public_key_jwk"`
	CurrentPassword string         `json:"current_password"`
	Consent         bool           `json:"consent"`
	KeyVersion      int            `json:"key_version"`
	WrappedKeys     map[string]any `json:"wrapped_keys"`
}

func (handler P2PHandler) Handle(w http.ResponseWriter, r *http.Request) {
	var req p2pRequest
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.Error(w, err)
		return
	}
	if strings.TrimSpace(req.Action) == "" {
		req.Action = "status"
	}
	scope, _, err := handler.scope.requireScope(r, selectedGroupName(r, req.GroupName))
	if err != nil {
		httpx.Error(w, err)
		return
	}
	groupName := scope.Membership.GroupName

	switch req.Action {
	case "publish_key":
		publicKey, err := normalizePublicJWK(req.PublicKeyJWK)
		if err != nil {
			httpx.Error(w, err)
			return
		}
		allowed, err := handler.rates.Hit(r.Context(), "p2p_public_key_publish", strconv.FormatInt(scope.User.ID, 10), 10, 15*time.Minute)
		if err != nil {
			httpx.Error(w, err)
			return
		}
		if !allowed {
			httpx.Error(w, httpx.APIError{Status: http.StatusTooManyRequests, Message: "公钥登记尝试过于频繁，请稍后再试。"})
			return
		}
		change, err := handler.publishPublicKey(r, scope, publicKey, req.CurrentPassword)
		if err != nil {
			httpx.Error(w, err)
			return
		}
		id := scope.User.ID
		_ = handler.users.RecordLog(r.Context(), &id, groupName, "p2p_public_key_update", "更新端到端加密公钥", map[string]any{"change": change}, httpx.ClientIP(r), r.UserAgent())
	case "consent":
		var consentAt any
		if req.Consent {
			consentAt = time.Now()
		}
		_, err := handler.db.ExecContext(r.Context(), `
INSERT INTO p2p_group_members (group_name, user_id, consent_at)
VALUES (?, ?, ?)
ON DUPLICATE KEY UPDATE consent_at = VALUES(consent_at), updated_at = NOW()`,
			groupName, scope.User.ID, consentAt)
		if err != nil {
			httpx.Error(w, err)
			return
		}
		id := scope.User.ID
		eventType := "p2p_consent_revoke"
		message := "取消端到端加密同意"
		if req.Consent {
			eventType = "p2p_consent"
			message = "同意端到端加密"
		}
		_ = handler.users.RecordLog(r.Context(), &id, groupName, eventType, message, nil, httpx.ClientIP(r), r.UserAgent())
	case "enable_group":
		if err := handler.enableGroup(w, r, scope, req); err != nil {
			httpx.Error(w, err)
			return
		}
	case "distribute_group_key":
		if err := handler.distributeGroupKey(w, r, scope, req); err != nil {
			httpx.Error(w, err)
			return
		}
	case "status":
	default:
		httpx.Error(w, httpx.BadRequest("Unknown action."))
		return
	}

	payload, err := handler.statusPayload(r, scope.User.ID, groupName)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	httpx.OK(w, payload)
}

func (handler P2PHandler) publishPublicKey(r *http.Request, scope *userScope, publicKey string, currentPassword string) (string, error) {
	tx, err := handler.db.BeginTx(r.Context(), nil)
	if err != nil {
		return "", err
	}
	defer func() { _ = tx.Rollback() }()

	var lockedUserID int64
	var passwordHash string
	if err := tx.QueryRowContext(r.Context(), `
SELECT id, password_hash
FROM users
WHERE id = ? AND is_active = 1
LIMIT 1
FOR UPDATE`, scope.User.ID).Scan(&lockedUserID, &passwordHash); err != nil {
		return "", err
	}

	var currentKey string
	err = tx.QueryRowContext(r.Context(), `
SELECT public_key_jwk
FROM p2p_user_keys
WHERE user_id = ?
LIMIT 1`, scope.User.ID).Scan(&currentKey)
	change := "unchanged"
	switch {
	case err == sql.ErrNoRows:
		if strings.TrimSpace(currentPassword) == "" || !services.CheckPassword(currentPassword, passwordHash) {
			return "", httpx.Forbidden("登记或更换端到端加密公钥需要验证当前密码。")
		}
		if _, err := tx.ExecContext(r.Context(), `
INSERT INTO p2p_user_keys (user_id, public_key_jwk)
VALUES (?, ?)`, scope.User.ID, publicKey); err != nil {
			return "", err
		}
		change = "created"
	case err != nil:
		return "", err
	case currentKey == publicKey:
	case strings.TrimSpace(currentPassword) == "" || !services.CheckPassword(currentPassword, passwordHash):
		return "", httpx.Forbidden("登记或更换端到端加密公钥需要验证当前密码。")
	default:
		if _, err := tx.ExecContext(r.Context(), `
UPDATE p2p_user_keys
SET public_key_jwk = ?, updated_at = NOW()
WHERE user_id = ?`, publicKey, scope.User.ID); err != nil {
			return "", err
		}
		if _, err := tx.ExecContext(r.Context(), `
UPDATE p2p_group_members
SET wrapped_group_key = NULL, key_version = 0, updated_at = NOW()
WHERE user_id = ?`, scope.User.ID); err != nil {
			return "", err
		}
		change = "replaced"
	}
	if err := tx.Commit(); err != nil {
		return "", err
	}
	return change, nil
}

func (handler P2PHandler) enableGroup(w http.ResponseWriter, r *http.Request, scope *userScope, req p2pRequest) error {
	group, err := handler.requireInitiator(r, scope)
	if err != nil {
		return err
	}
	if group.EnabledAt.Valid {
		return httpx.APIError{Status: http.StatusConflict, Message: "该家庭组已经开启端到端加密。"}
	}
	keyVersion := req.KeyVersion
	if keyVersion < 1 {
		keyVersion = int(time.Now().Unix())
	}
	members, err := handler.memberRows(r, scope.Membership.GroupName)
	if err != nil {
		return err
	}
	if len(members) == 0 {
		return httpx.Unprocessable("家庭组没有可用成员。")
	}
	for _, member := range members {
		if !member.PublicKey.Valid || !member.ConsentAt.Valid {
			return httpx.APIError{Status: http.StatusConflict, Message: "需要组内所有成员先在各自 App 内同意并生成密钥。"}
		}
		if !validWrappedKey(req.WrappedKeys[strconv.FormatInt(member.UserID, 10)]) {
			return httpx.Unprocessable("组密钥分发数据不完整。")
		}
	}

	tx, err := handler.db.BeginTx(r.Context(), nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	if _, err := tx.ExecContext(r.Context(), `
UPDATE family_groups
SET p2p_enabled_at = NOW(), p2p_enabled_by = ?, p2p_key_version = ?
WHERE group_name = ?`, scope.User.ID, keyVersion, scope.Membership.GroupName); err != nil {
		return err
	}
	for _, member := range members {
		wrapped := strings.TrimSpace(req.WrappedKeys[strconv.FormatInt(member.UserID, 10)].(string))
		if _, err := tx.ExecContext(r.Context(), `
INSERT INTO p2p_group_members (group_name, user_id, wrapped_group_key, key_version)
VALUES (?, ?, ?, ?)
ON DUPLICATE KEY UPDATE wrapped_group_key = VALUES(wrapped_group_key), key_version = VALUES(key_version), updated_at = NOW()`,
			scope.Membership.GroupName, member.UserID, wrapped, keyVersion); err != nil {
			return err
		}
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	id := scope.User.ID
	_ = handler.users.RecordLog(r.Context(), &id, scope.Membership.GroupName, "p2p_enable", "开启家庭组端到端加密", map[string]any{"key_version": keyVersion}, httpx.ClientIP(r), r.UserAgent())
	return nil
}

func (handler P2PHandler) distributeGroupKey(w http.ResponseWriter, r *http.Request, scope *userScope, req p2pRequest) error {
	group, err := handler.requireInitiator(r, scope)
	if err != nil {
		return err
	}
	if !group.EnabledAt.Valid {
		return httpx.APIError{Status: http.StatusConflict, Message: "当前家庭组尚未开启端到端加密。"}
	}
	if group.KeyVersion <= 0 || req.KeyVersion != group.KeyVersion {
		return httpx.APIError{Status: http.StatusConflict, Message: "密钥版本不一致，请刷新后重试。"}
	}
	members, err := handler.memberRows(r, scope.Membership.GroupName)
	if err != nil {
		return err
	}
	targets := make([]int64, 0)
	for _, member := range members {
		hasCurrent := member.WrappedKey.Valid && member.KeyVersion == group.KeyVersion
		if hasCurrent || !member.PublicKey.Valid || !member.ConsentAt.Valid {
			continue
		}
		if !validWrappedKey(req.WrappedKeys[strconv.FormatInt(member.UserID, 10)]) {
			return httpx.Unprocessable("补发密钥数据不完整。")
		}
		targets = append(targets, member.UserID)
	}
	if len(targets) == 0 {
		return nil
	}
	tx, err := handler.db.BeginTx(r.Context(), nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	for _, memberID := range targets {
		wrapped := strings.TrimSpace(req.WrappedKeys[strconv.FormatInt(memberID, 10)].(string))
		if _, err := tx.ExecContext(r.Context(), `
INSERT INTO p2p_group_members (group_name, user_id, wrapped_group_key, key_version)
VALUES (?, ?, ?, ?)
ON DUPLICATE KEY UPDATE wrapped_group_key = VALUES(wrapped_group_key), key_version = VALUES(key_version), updated_at = NOW()`,
			scope.Membership.GroupName, memberID, wrapped, group.KeyVersion); err != nil {
			return err
		}
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	id := scope.User.ID
	_ = handler.users.RecordLog(r.Context(), &id, scope.Membership.GroupName, "p2p_key_distribute", "补发端到端加密组密钥", map[string]any{"key_version": group.KeyVersion, "target_user_ids": targets}, httpx.ClientIP(r), r.UserAgent())
	return nil
}

type p2pGroup struct {
	OwnerUserID int64
	EnabledAt   sql.NullTime
	KeyVersion  int
}

func (handler P2PHandler) requireInitiator(r *http.Request, scope *userScope) (*p2pGroup, error) {
	var group p2pGroup
	err := handler.db.QueryRowContext(r.Context(), `
SELECT COALESCE(owner_user_id, 0), p2p_enabled_at, p2p_key_version
FROM family_groups
WHERE group_name = ?
LIMIT 1`, scope.Membership.GroupName).Scan(&group.OwnerUserID, &group.EnabledAt, &group.KeyVersion)
	if err == sql.ErrNoRows {
		return nil, httpx.APIError{Status: http.StatusNotFound, Message: "家庭组不存在。"}
	}
	if err != nil {
		return nil, err
	}
	if group.OwnerUserID <= 0 || group.OwnerUserID != scope.User.ID {
		return nil, httpx.Forbidden("只有家庭组管理员可以开启端到端加密。")
	}
	return &group, nil
}

type p2pMember struct {
	UserID      int64
	Username    string
	DisplayName string
	Role        string
	PublicKey   sql.NullString
	ConsentAt   sql.NullTime
	WrappedKey  sql.NullString
	KeyVersion  int
}

func (handler P2PHandler) memberRows(r *http.Request, groupName string) ([]p2pMember, error) {
	rows, err := handler.db.QueryContext(r.Context(), `
SELECT
	u.id, u.username, u.display_name, ug.role,
	puk.public_key_jwk, pgm.consent_at, pgm.wrapped_group_key, COALESCE(pgm.key_version, 0)
FROM user_groups ug
INNER JOIN users u ON u.id = ug.user_id
LEFT JOIN p2p_user_keys puk ON puk.user_id = u.id
LEFT JOIN p2p_group_members pgm ON pgm.group_name = ug.group_name AND pgm.user_id = u.id
WHERE ug.group_name = ? AND u.is_active = 1
ORDER BY ug.role ASC, u.username ASC`, groupName)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	members := make([]p2pMember, 0)
	for rows.Next() {
		var member p2pMember
		if err := rows.Scan(&member.UserID, &member.Username, &member.DisplayName, &member.Role, &member.PublicKey, &member.ConsentAt, &member.WrappedKey, &member.KeyVersion); err != nil {
			return nil, err
		}
		members = append(members, member)
	}
	return members, rows.Err()
}

func (handler P2PHandler) statusPayload(r *http.Request, userID int64, groupName string) (map[string]any, error) {
	var ownerUserID int64
	var enabledAt sql.NullTime
	var keyVersion int
	err := handler.db.QueryRowContext(r.Context(), `
SELECT COALESCE(owner_user_id, 0), p2p_enabled_at, p2p_key_version
FROM family_groups
WHERE group_name = ?
LIMIT 1`, groupName).Scan(&ownerUserID, &enabledAt, &keyVersion)
	if err == sql.ErrNoRows {
		ownerUserID = 0
		keyVersion = 0
		err = nil
	}
	if err != nil {
		return nil, err
	}
	members, err := handler.memberRows(r, groupName)
	if err != nil {
		return nil, err
	}
	var userWrappedKey any
	needsDistribution := false
	payloadMembers := make([]map[string]any, 0, len(members))
	for _, member := range members {
		var publicKey any
		if member.PublicKey.Valid {
			_ = json.Unmarshal([]byte(member.PublicKey.String), &publicKey)
		}
		hasWrappedKey := member.WrappedKey.Valid && member.KeyVersion == keyVersion
		memberNeedsKey := enabledAt.Valid && publicKey != nil && member.ConsentAt.Valid && !hasWrappedKey
		if memberNeedsKey {
			needsDistribution = true
		}
		if member.UserID == userID && hasWrappedKey {
			userWrappedKey = member.WrappedKey.String
		}
		payloadMembers = append(payloadMembers, map[string]any{
			"user_id":                member.UserID,
			"username":               member.Username,
			"display_name":           member.DisplayName,
			"role":                   services.NormalizeRole(member.Role),
			"role_label":             services.RoleLabel(member.Role),
			"has_public_key":         publicKey != nil,
			"has_wrapped_key":        hasWrappedKey,
			"needs_key_distribution": memberNeedsKey,
			"consented":              member.ConsentAt.Valid,
			"public_key_jwk":         publicKey,
		})
	}
	return map[string]any{
		"ok":                     true,
		"group_name":             groupName,
		"enabled":                enabledAt.Valid,
		"key_version":            keyVersion,
		"is_owner":               ownerUserID > 0 && ownerUserID == userID,
		"wrapped_group_key":      userWrappedKey,
		"needs_key_distribution": needsDistribution,
		"members":                payloadMembers,
	}, nil
}

func normalizePublicJWK(value map[string]any) (string, error) {
	if len(value) == 0 {
		return "", httpx.Unprocessable("公钥格式不正确。")
	}
	for _, field := range []string{"kty", "key_ops", "ext", "n", "e"} {
		if _, ok := value[field]; !ok {
			return "", httpx.Unprocessable("公钥字段不完整。")
		}
	}
	if value["kty"] != "RSA" {
		return "", httpx.Unprocessable("仅支持 RSA-OAEP 公钥。")
	}
	raw, err := json.Marshal(value)
	if err != nil || len(raw) > 12000 {
		return "", httpx.Unprocessable("公钥数据过大。")
	}
	return string(raw), nil
}

func validWrappedKey(value any) bool {
	text, ok := value.(string)
	if !ok {
		return false
	}
	length := len(text)
	return length >= 80 && length <= 20000 && wrappedKeyPattern.MatchString(text)
}
