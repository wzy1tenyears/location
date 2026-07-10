package handlers

import (
	"context"
	"crypto/rand"
	"database/sql"
	"fmt"
	"net/http"
	"regexp"
	"strings"
	"time"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/repositories"
	"familylocation/location-v3/internal/services"
	"familylocation/location-v3/internal/session"
)

var adminCodePattern = regexp.MustCompile(`^[0-9a-z]{4,64}$`)

type AdminManageHandler struct {
	db       *sql.DB
	sessions session.Reader
	users    repositories.UserRepository
	settings repositories.SettingRepository
}

func NewAdminManageHandler(db *sql.DB, sessions session.Reader) AdminManageHandler {
	return AdminManageHandler{
		db:       db,
		sessions: sessions,
		users:    repositories.NewUserRepository(db),
		settings: repositories.NewSettingRepository(db),
	}
}

func (handler AdminManageHandler) Handle(w http.ResponseWriter, r *http.Request) {
	if !handler.sessions.IsAdmin(r) {
		httpx.Error(w, httpx.Unauthorized("请先登录后台。"))
		return
	}
	var data map[string]any
	if err := httpx.DecodeJSON(r, &data); err != nil {
		httpx.Error(w, httpx.BadRequest("请求格式不正确。"))
		return
	}
	action := adminString(data, "action", 64)
	message, err := handler.execute(r, action, data)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	_ = handler.users.RecordLog(r.Context(), nil, "", "admin_"+action, message, nil, httpx.ClientIP(r), r.UserAgent())
	httpx.OK(w, map[string]any{"ok": true, "message": message})
}

func (handler AdminManageHandler) execute(r *http.Request, action string, data map[string]any) (string, error) {
	switch action {
	case "update_security_settings":
		for _, key := range repositories.SecuritySettingKeys() {
			value := "0"
			if truthyAny(data[key]) {
				value = "1"
			}
			if err := handler.settings.Set(r.Context(), key, value); err != nil {
				return "", err
			}
		}
		return "安全策略已保存。", nil
	case "add_family_group":
		name := adminString(data, "group_name", 100)
		if name == "" {
			return "", httpx.Unprocessable("家庭组名称不能为空。")
		}
		tx, err := handler.db.BeginTx(r.Context(), nil)
		if err != nil {
			return "", err
		}
		defer func() { _ = tx.Rollback() }()
		if _, err := createFamilyGroupRecordTx(r.Context(), tx, name, nil); err != nil {
			return "", err
		}
		return "家庭组已添加。", tx.Commit()
	case "update_family_group":
		id := int64FromMap(data, "group_id", 0)
		name := adminString(data, "group_name", 100)
		if id <= 0 || name == "" {
			return "", httpx.Unprocessable("家庭组不存在或名称为空。")
		}
		result, err := handler.db.ExecContext(r.Context(), "UPDATE family_groups SET display_name = ? WHERE id = ?", name, id)
		if err != nil {
			return "", err
		}
		if rows, _ := result.RowsAffected(); rows == 0 {
			return "", httpx.Unprocessable("家庭组不存在。")
		}
		return "家庭组已更新。", nil
	case "update_group_owner":
		return handler.updateGroupOwner(r, data)
	case "delete_family_group":
		return handler.deleteFamilyGroup(r, int64FromMap(data, "group_id", 0))
	case "save_announcement":
		title := adminString(data, "title", 120)
		body := strings.TrimSpace(fmt.Sprint(data["body"]))
		active := truthyAny(data["is_active"])
		var id int64
		err := handler.db.QueryRowContext(r.Context(), "SELECT id FROM announcements ORDER BY id DESC LIMIT 1").Scan(&id)
		if err == sql.ErrNoRows {
			_, err = handler.db.ExecContext(r.Context(), "INSERT INTO announcements (title, body, is_active) VALUES (?, ?, ?)", title, body, active)
		} else if err == nil {
			_, err = handler.db.ExecContext(r.Context(), "UPDATE announcements SET title = ?, body = ?, is_active = ?, version = version + 1 WHERE id = ?", title, body, active, id)
		}
		return "公告已保存。", err
	case "add_invite_code":
		return handler.addInvite(r, data)
	case "update_invite_note":
		id := int64FromMap(data, "invite_id", 0)
		if id <= 0 {
			return "", httpx.Unprocessable("邀请码不存在。")
		}
		_, err := handler.db.ExecContext(r.Context(), "UPDATE invite_codes SET note = ? WHERE id = ?", adminString(data, "note", 120), id)
		return "邀请码备注已保存。", err
	case "delete_invite_code":
		id := int64FromMap(data, "invite_id", 0)
		if id <= 0 {
			return "", httpx.Unprocessable("邀请码不存在。")
		}
		_, err := handler.db.ExecContext(r.Context(), "DELETE FROM invite_codes WHERE id = ?", id)
		return "邀请码已删除。", err
	case "toggle_invite_code":
		id := int64FromMap(data, "invite_id", 0)
		if id <= 0 {
			return "", httpx.Unprocessable("邀请码不存在。")
		}
		next := truthyAny(data["next"])
		_, err := handler.db.ExecContext(r.Context(), "UPDATE invite_codes SET is_active = ? WHERE id = ?", next, id)
		if next {
			return "邀请码已启用。", err
		}
		return "邀请码已停用。", err
	case "add_user":
		return handler.addUser(r, data)
	case "add_membership":
		return handler.addMembership(r, data)
	case "update_membership":
		return handler.updateMembership(r, data)
	case "delete_membership":
		return handler.deleteMembership(r, int64FromMap(data, "membership_id", 0))
	case "update_user":
		return handler.updateUser(r, data)
	case "toggle_user":
		return handler.toggleUser(r, data)
	case "reset_password":
		return handler.resetPassword(r, data)
	case "delete_user":
		id := int64FromMap(data, "user_id", 0)
		if id <= 0 {
			return "", httpx.Unprocessable("账号不存在。")
		}
		_, err := handler.db.ExecContext(r.Context(), "DELETE FROM users WHERE id = ?", id)
		return "账号已删除。", err
	case "delete_user_device":
		id := int64FromMap(data, "device_id", 0)
		if id <= 0 {
			return "", httpx.Unprocessable("设备绑定不存在。")
		}
		_, err := handler.db.ExecContext(r.Context(), "DELETE FROM user_devices WHERE id = ?", id)
		return "设备绑定已删除。", err
	case "delete_location":
		return handler.deleteLocation(r, int64FromMap(data, "location_id", 0))
	case "reply_ticket":
		return handler.replyTicket(r, data)
	case "update_ticket_status":
		return handler.updateTicketStatus(r, data)
	default:
		return "", httpx.Unprocessable("暂不支持的后台操作。")
	}
}

func (handler AdminManageHandler) updateGroupOwner(r *http.Request, data map[string]any) (string, error) {
	groupID := int64FromMap(data, "group_id", 0)
	ownerID := int64FromMap(data, "owner_user_id", 0)
	var groupName string
	if err := handler.db.QueryRowContext(r.Context(), "SELECT group_name FROM family_groups WHERE id = ? LIMIT 1", groupID).Scan(&groupName); err != nil {
		if err == sql.ErrNoRows {
			return "", httpx.Unprocessable("家庭组不存在。")
		}
		return "", err
	}
	if ownerID > 0 {
		var membershipID int64
		if err := handler.db.QueryRowContext(r.Context(), "SELECT id FROM user_groups WHERE user_id = ? AND group_name = ? LIMIT 1", ownerID, groupName).Scan(&membershipID); err != nil {
			if err == sql.ErrNoRows {
				return "", httpx.Unprocessable("只能设置组内成员为管理员。")
			}
			return "", err
		}
	}
	var owner any
	var logUserID *int64
	if ownerID > 0 {
		owner = ownerID
		logUserID = &ownerID
	}
	if _, err := handler.db.ExecContext(r.Context(), "UPDATE family_groups SET owner_user_id = ? WHERE id = ?", owner, groupID); err != nil {
		return "", err
	}
	_ = handler.users.RecordLog(r.Context(), logUserID, groupName, "group_owner_update", "后台更改家庭组管理员", nil, httpx.ClientIP(r), r.UserAgent())
	return "家庭组管理员已更新。", nil
}

func (handler AdminManageHandler) deleteFamilyGroup(r *http.Request, groupID int64) (string, error) {
	var groupName string
	if err := handler.db.QueryRowContext(r.Context(), "SELECT group_name FROM family_groups WHERE id = ? LIMIT 1", groupID).Scan(&groupName); err != nil {
		if err == sql.ErrNoRows {
			return "", httpx.Unprocessable("家庭组不存在。")
		}
		return "", err
	}
	rows, err := handler.db.QueryContext(r.Context(), "SELECT DISTINCT user_id FROM user_groups WHERE group_name = ?", groupName)
	if err != nil {
		return "", err
	}
	affected := make([]int64, 0)
	for rows.Next() {
		var id int64
		_ = rows.Scan(&id)
		affected = append(affected, id)
	}
	rows.Close()
	tx, err := handler.db.BeginTx(r.Context(), nil)
	if err != nil {
		return "", err
	}
	defer func() { _ = tx.Rollback() }()
	for _, statement := range []string{
		"DELETE FROM latest_group_locations WHERE group_name = ?",
		"DELETE FROM locations WHERE group_name = ?",
		"DELETE FROM user_groups WHERE group_name = ?",
	} {
		if _, err := tx.ExecContext(r.Context(), statement, groupName); err != nil {
			return "", err
		}
	}
	if _, err := tx.ExecContext(r.Context(), "DELETE FROM family_groups WHERE id = ?", groupID); err != nil {
		return "", err
	}
	for _, userID := range affected {
		var nextGroup string
		var nextRole string
		err := tx.QueryRowContext(r.Context(), "SELECT group_name, role FROM user_groups WHERE user_id = ? ORDER BY group_name ASC, id ASC LIMIT 1", userID).Scan(&nextGroup, &nextRole)
		if err == sql.ErrNoRows {
			_, err = tx.ExecContext(r.Context(), "UPDATE users SET group_name = '', role = 'guardian' WHERE id = ? AND group_name = ?", userID, groupName)
		} else if err == nil {
			_, err = tx.ExecContext(r.Context(), "UPDATE users SET group_name = ?, role = ? WHERE id = ? AND group_name = ?", nextGroup, nextRole, userID, groupName)
		}
		if err != nil {
			return "", err
		}
	}
	return "家庭组已删除，组内定位记录已清除。", tx.Commit()
}

func (handler AdminManageHandler) addInvite(r *http.Request, data map[string]any) (string, error) {
	code := strings.ToLower(adminString(data, "code", 64))
	if code == "" {
		code = randomCode(6)
	}
	if !adminCodePattern.MatchString(code) {
		return "", httpx.Unprocessable("邀请码只能包含小写字母和数字。")
	}
	inviteType := adminString(data, "invite_type", 32)
	if inviteType != "invite" && inviteType != "group_create" {
		return "", httpx.Unprocessable("邀请码类型不正确。")
	}
	maxUses := intFromMap(data, "max_uses", 1)
	if maxUses < 1 {
		maxUses = 1
	}
	if maxUses > 9999 {
		maxUses = 9999
	}
	_, err := handler.db.ExecContext(r.Context(), "INSERT INTO invite_codes (code, note, invite_type, allow_group_owner, max_uses) VALUES (?, ?, ?, ?, ?)",
		code, adminString(data, "note", 120), inviteType, truthyAny(data["allow_group_owner"]), maxUses)
	return "邀请码已添加：" + code, err
}

func (handler AdminManageHandler) addUser(r *http.Request, data map[string]any) (string, error) {
	username := adminString(data, "username", 64)
	password := fmt.Sprint(data["password"])
	displayName := adminString(data, "display_name", 100)
	groupName := adminString(data, "group_name", 100)
	role, err := adminRole(adminString(data, "role", 16))
	if err != nil {
		return "", err
	}
	if username == "" || password == "" || groupName == "" {
		return "", httpx.Unprocessable("账号、密码和初始家庭组不能为空。")
	}
	if existing, err := handler.users.FindByUsername(r.Context(), username); err != nil {
		return "", err
	} else if existing != nil {
		return "", httpx.Unprocessable("账号名称已存在。")
	}
	hash, err := services.HashPassword(password)
	if err != nil {
		return "", err
	}
	tx, err := handler.db.BeginTx(r.Context(), nil)
	if err != nil {
		return "", err
	}
	defer func() { _ = tx.Rollback() }()
	if err := ensureFamilyGroupRecordTx(r.Context(), tx, groupName, nil); err != nil {
		return "", err
	}
	interval := services.NormalizeReportIntervalSeconds(intFromMap(data, "report_interval_seconds", 300))
	result, err := tx.ExecContext(r.Context(), "INSERT INTO users (username, password_hash, display_name, group_name, role, report_interval_seconds, debug_mode) VALUES (?, ?, ?, ?, ?, ?, ?)",
		username, hash, displayName, groupName, role, interval, truthyAny(data["debug_mode"]))
	if err != nil {
		return "", err
	}
	userID, _ := result.LastInsertId()
	if err := ensureFamilyGroupRecordTx(r.Context(), tx, groupName, &userID); err != nil {
		return "", err
	}
	if _, err := tx.ExecContext(r.Context(), "INSERT INTO user_groups (user_id, group_name, role) VALUES (?, ?, ?)", userID, groupName, role); err != nil {
		return "", err
	}
	return "账号已添加。", tx.Commit()
}

func (handler AdminManageHandler) addMembership(r *http.Request, data map[string]any) (string, error) {
	userID := int64FromMap(data, "user_id", 0)
	groupName := adminString(data, "group_name", 100)
	role, err := adminRole(adminString(data, "role", 16))
	if err != nil {
		return "", err
	}
	if userID <= 0 || groupName == "" {
		return "", httpx.Unprocessable("账号或家庭组不能为空。")
	}
	tx, err := handler.db.BeginTx(r.Context(), nil)
	if err != nil {
		return "", err
	}
	defer func() { _ = tx.Rollback() }()
	if err := ensureFamilyGroupRecordTx(r.Context(), tx, groupName, &userID); err != nil {
		return "", err
	}
	var existing int64
	if err := tx.QueryRowContext(r.Context(), "SELECT id FROM user_groups WHERE user_id = ? AND group_name = ? LIMIT 1", userID, groupName).Scan(&existing); err == nil {
		return "", httpx.Unprocessable("这个账号已经在该家庭组内。")
	} else if err != sql.ErrNoRows {
		return "", err
	}
	if _, err := tx.ExecContext(r.Context(), "INSERT INTO user_groups (user_id, group_name, role) VALUES (?, ?, ?)", userID, groupName, role); err != nil {
		return "", err
	}
	return "家庭组身份已添加。", tx.Commit()
}

func (handler AdminManageHandler) updateMembership(r *http.Request, data map[string]any) (string, error) {
	membershipID := int64FromMap(data, "membership_id", 0)
	groupName := adminString(data, "group_name", 100)
	role, err := adminRole(adminString(data, "role", 16))
	if err != nil {
		return "", err
	}
	if membershipID <= 0 || groupName == "" {
		return "", httpx.Unprocessable("家庭组身份不存在或名称为空。")
	}
	var userID int64
	var oldGroup string
	if err := handler.db.QueryRowContext(r.Context(), "SELECT user_id, group_name FROM user_groups WHERE id = ? LIMIT 1", membershipID).Scan(&userID, &oldGroup); err != nil {
		if err == sql.ErrNoRows {
			return "", httpx.Unprocessable("家庭组身份不存在。")
		}
		return "", err
	}
	var duplicate int64
	err = handler.db.QueryRowContext(r.Context(), "SELECT id FROM user_groups WHERE user_id = ? AND group_name = ? AND id <> ? LIMIT 1", userID, groupName, membershipID).Scan(&duplicate)
	if err == nil {
		return "", httpx.Unprocessable("这个账号已经在该家庭组内。")
	}
	if err != sql.ErrNoRows {
		return "", err
	}
	tx, err := handler.db.BeginTx(r.Context(), nil)
	if err != nil {
		return "", err
	}
	defer func() { _ = tx.Rollback() }()
	if err := ensureFamilyGroupRecordTx(r.Context(), tx, groupName, &userID); err != nil {
		return "", err
	}
	if oldGroup != groupName {
		_, _ = tx.ExecContext(r.Context(), "DELETE FROM latest_group_locations WHERE user_id = ? AND group_name = ?", userID, groupName)
	}
	for _, query := range []string{
		"UPDATE user_groups SET group_name = ?, role = ? WHERE id = ?",
		"UPDATE latest_group_locations SET group_name = ?, role = ? WHERE user_id = ? AND group_name = ?",
		"UPDATE locations SET group_name = ?, role = ? WHERE user_id = ? AND group_name = ?",
		"UPDATE users SET group_name = ?, role = ? WHERE id = ? AND group_name = ?",
	} {
		if strings.Contains(query, "WHERE id = ?") && !strings.Contains(query, "users") {
			_, err = tx.ExecContext(r.Context(), query, groupName, role, membershipID)
		} else {
			_, err = tx.ExecContext(r.Context(), query, groupName, role, userID, oldGroup)
		}
		if err != nil {
			return "", err
		}
	}
	return "家庭组身份已更新。", tx.Commit()
}

func (handler AdminManageHandler) deleteMembership(r *http.Request, membershipID int64) (string, error) {
	var userID int64
	var groupName string
	if err := handler.db.QueryRowContext(r.Context(), "SELECT user_id, group_name FROM user_groups WHERE id = ? LIMIT 1", membershipID).Scan(&userID, &groupName); err != nil {
		if err == sql.ErrNoRows {
			return "", httpx.Unprocessable("家庭组身份不存在。")
		}
		return "", err
	}
	var count int
	if err := handler.db.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM user_groups WHERE user_id = ?", userID).Scan(&count); err != nil {
		return "", err
	}
	if count <= 1 {
		return "", httpx.Unprocessable("每个账号至少保留一个家庭组。")
	}
	tx, err := handler.db.BeginTx(r.Context(), nil)
	if err != nil {
		return "", err
	}
	defer func() { _ = tx.Rollback() }()
	if _, err := tx.ExecContext(r.Context(), "DELETE FROM user_groups WHERE id = ?", membershipID); err != nil {
		return "", err
	}
	if _, err := tx.ExecContext(r.Context(), "DELETE FROM latest_group_locations WHERE user_id = ? AND group_name = ?", userID, groupName); err != nil {
		return "", err
	}
	var nextGroup, nextRole string
	if err := tx.QueryRowContext(r.Context(), "SELECT group_name, role FROM user_groups WHERE user_id = ? ORDER BY group_name ASC, id ASC LIMIT 1", userID).Scan(&nextGroup, &nextRole); err == nil {
		_, err = tx.ExecContext(r.Context(), "UPDATE users SET group_name = ?, role = ? WHERE id = ? AND group_name = ?", nextGroup, nextRole, userID, groupName)
		if err != nil {
			return "", err
		}
	}
	return "家庭组身份已删除。", tx.Commit()
}

func (handler AdminManageHandler) updateUser(r *http.Request, data map[string]any) (string, error) {
	userID := int64FromMap(data, "user_id", 0)
	username := adminString(data, "username", 64)
	if userID <= 0 || username == "" {
		return "", httpx.Unprocessable("账号不存在或名称为空。")
	}
	var duplicate int64
	err := handler.db.QueryRowContext(r.Context(), "SELECT id FROM users WHERE username = ? AND id <> ? LIMIT 1", username, userID).Scan(&duplicate)
	if err == nil {
		return "", httpx.Unprocessable("账号名称已存在。")
	}
	if err != sql.ErrNoRows {
		return "", err
	}
	interval := services.NormalizeReportIntervalSeconds(intFromMap(data, "report_interval_seconds", 300))
	_, err = handler.db.ExecContext(r.Context(), "UPDATE users SET username = ?, display_name = ?, report_interval_seconds = ?, debug_mode = ? WHERE id = ?",
		username, adminString(data, "display_name", 100), interval, truthyAny(data["debug_mode"]), userID)
	return "账号信息已更新。", err
}

func (handler AdminManageHandler) toggleUser(r *http.Request, data map[string]any) (string, error) {
	userID := int64FromMap(data, "user_id", 0)
	if userID <= 0 {
		return "", httpx.Unprocessable("账号不存在。")
	}
	next := truthyAny(data["next"])
	reason := ""
	if !next {
		reason = adminString(data, "disabled_reason", 255)
	}
	if _, err := handler.db.ExecContext(r.Context(), "UPDATE users SET is_active = ?, disabled_reason = ? WHERE id = ?", next, reason, userID); err != nil {
		return "", err
	}
	event := "user_disable"
	message := "账号已停用。"
	if next {
		event = "user_enable"
		message = "账号已启用。"
	}
	id := userID
	_ = handler.users.RecordLog(r.Context(), &id, "", event, reason, nil, httpx.ClientIP(r), r.UserAgent())
	return message, nil
}

func (handler AdminManageHandler) resetPassword(r *http.Request, data map[string]any) (string, error) {
	userID := int64FromMap(data, "user_id", 0)
	password := fmt.Sprint(data["new_password"])
	if userID <= 0 || password == "" {
		return "", httpx.Unprocessable("账号或新密码不能为空。")
	}
	hash, err := services.HashPassword(password)
	if err != nil {
		return "", err
	}
	if err := handler.users.UpdatePasswordHash(r.Context(), userID, hash); err != nil {
		return "", err
	}
	_ = handler.users.ClearFailedLogin(r.Context(), userID)
	return "密码已重置。", nil
}

func (handler AdminManageHandler) deleteLocation(r *http.Request, locationID int64) (string, error) {
	if locationID <= 0 {
		return "", httpx.Unprocessable("定位记录不存在。")
	}
	var userID int64
	var groupName string
	err := handler.db.QueryRowContext(r.Context(), "SELECT user_id, group_name FROM locations WHERE id = ? LIMIT 1", locationID).Scan(&userID, &groupName)
	if err == sql.ErrNoRows {
		return "", httpx.Unprocessable("定位记录不存在。")
	}
	if err != nil {
		return "", err
	}
	tx, err := handler.db.BeginTx(r.Context(), nil)
	if err != nil {
		return "", err
	}
	defer func() { _ = tx.Rollback() }()
	if _, err := tx.ExecContext(r.Context(), "DELETE FROM locations WHERE id = ?", locationID); err != nil {
		return "", err
	}
	if err := refreshLatestLocationTx(r.Context(), tx, userID, groupName); err != nil {
		return "", err
	}
	return "定位记录已删除。", tx.Commit()
}

func (handler AdminManageHandler) replyTicket(r *http.Request, data map[string]any) (string, error) {
	ticketID := int64FromMap(data, "ticket_id", 0)
	reply := adminString(data, "reply", 2000)
	if ticketID <= 0 || reply == "" {
		return "", httpx.Unprocessable("工单回复不能为空。")
	}
	tx, err := handler.db.BeginTx(r.Context(), nil)
	if err != nil {
		return "", err
	}
	defer func() { _ = tx.Rollback() }()
	if _, err := tx.ExecContext(r.Context(), "INSERT INTO support_ticket_messages (ticket_id, sender_type, message) VALUES (?, 'admin', ?)", ticketID, reply); err != nil {
		return "", err
	}
	if _, err := tx.ExecContext(r.Context(), "UPDATE support_tickets SET status = 'open', updated_at = NOW() WHERE id = ?", ticketID); err != nil {
		return "", err
	}
	return "工单已回复。", tx.Commit()
}

func (handler AdminManageHandler) updateTicketStatus(r *http.Request, data map[string]any) (string, error) {
	ticketID := int64FromMap(data, "ticket_id", 0)
	status := adminString(data, "status", 16)
	if ticketID <= 0 || (status != "open" && status != "closed") {
		return "", httpx.Unprocessable("工单状态不正确。")
	}
	_, err := handler.db.ExecContext(r.Context(), "UPDATE support_tickets SET status = ?, updated_at = NOW() WHERE id = ?", status, ticketID)
	if status == "closed" {
		return "工单已关闭。", err
	}
	return "工单已重新打开。", err
}

func refreshLatestLocationTx(ctx context.Context, tx *sql.Tx, userID int64, groupName string) error {
	row := tx.QueryRowContext(ctx, `
SELECT id, role, latitude, longitude, altitude, accuracy, heading, speed, location_meta, address_diagnostics, address_mismatch, encryption_mode, encrypted_payload, p2p_key_version, created_at
FROM locations
WHERE user_id = ? AND group_name = ?
ORDER BY created_at DESC, id DESC
LIMIT 1`, userID, groupName)
	var id int64
	var role string
	var latitude, longitude float64
	var altitude, accuracy, heading, speed sql.NullFloat64
	var locationMeta, diagnostics, encryptedPayload sql.NullString
	var mismatch bool
	var encryptionMode string
	var keyVersion int
	var createdAt time.Time
	err := row.Scan(&id, &role, &latitude, &longitude, &altitude, &accuracy, &heading, &speed, &locationMeta, &diagnostics, &mismatch, &encryptionMode, &encryptedPayload, &keyVersion, &createdAt)
	if err == sql.ErrNoRows {
		_, err = tx.ExecContext(ctx, "DELETE FROM latest_group_locations WHERE user_id = ? AND group_name = ?", userID, groupName)
		return err
	}
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, `
REPLACE INTO latest_group_locations (
	user_id, group_name, role, latitude, longitude, altitude, accuracy, heading, speed,
	location_meta, latest_location_id, address_diagnostics, address_mismatch,
	encryption_mode, encrypted_payload, p2p_key_version, updated_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		userID, groupName, services.NormalizeRole(role), latitude, longitude, nullFloat(altitude), nullFloat(accuracy), nullFloat(heading), nullFloat(speed),
		nullText(locationMeta), id, nullText(diagnostics), mismatch, encryptionMode, nullText(encryptedPayload), keyVersion, createdAt)
	return err
}

func adminString(data map[string]any, key string, max int) string {
	return truncateString(fmt.Sprint(data[key]), max)
}

func adminRole(role string) (string, error) {
	role = services.NormalizeRole(role)
	if role != "monitor" && role != "guardian" {
		return "", httpx.Unprocessable("家庭组身份不正确。")
	}
	return role, nil
}

func randomCode(length int) string {
	const alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
	buffer := make([]byte, length)
	if _, err := rand.Read(buffer); err != nil {
		return strings.Repeat("0", length)
	}
	for i := range buffer {
		buffer[i] = alphabet[int(buffer[i])%len(alphabet)]
	}
	return string(buffer)
}

func nullFloat(value sql.NullFloat64) any {
	if !value.Valid {
		return nil
	}
	return value.Float64
}

func nullText(value sql.NullString) any {
	if !value.Valid {
		return nil
	}
	return value.String
}
