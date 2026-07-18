package handlers

import (
	"database/sql"
	"net/http"
	"strings"
	"time"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/models"
	"familylocation/location-v3/internal/repositories"
	"familylocation/location-v3/internal/services"
	"familylocation/location-v3/internal/session"
)

type GroupsHandler struct {
	db     *sql.DB
	scope  scopedHandler
	users  repositories.UserRepository
	groups repositories.GroupRepository
	limits repositories.AuthLimitRepository
}

func NewGroupsHandler(db *sql.DB, sessions session.Reader) GroupsHandler {
	return GroupsHandler{
		db:     db,
		scope:  newScopedHandler(db, sessions),
		users:  repositories.NewUserRepository(db),
		groups: repositories.NewGroupRepository(db),
		limits: repositories.NewAuthLimitRepository(db),
	}
}

type groupsRequest struct {
	GroupName          string `json:"group_name"`
	Action             string `json:"action"`
	GroupCode          string `json:"group_code"`
	TargetUserID       int64  `json:"target_user_id"`
	NewPassword        string `json:"new_password"`
	NewPasswordConfirm string `json:"new_password_confirm"`
	Confirm            bool   `json:"confirm"`
	GroupID            int64  `json:"group_id"`
	NewGroupName       string `json:"group_name_new"`
}

func (handler GroupsHandler) Handle(w http.ResponseWriter, r *http.Request) {
	var req groupsRequest
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.Error(w, err)
		return
	}
	scope, _, err := handler.scope.requireScope(r, selectedGroupName(r, req.GroupName))
	if err != nil && req.Action != "join_by_code" {
		httpx.Error(w, err)
		return
	}
	if req.Action == "" {
		httpx.Error(w, httpx.BadRequest("Unknown action."))
		return
	}
	switch req.Action {
	case "join_by_code":
		handler.joinByCode(w, r, strings.ToLower(strings.TrimSpace(req.GroupCode)))
	case "leave_group":
		handler.leaveGroup(w, r, scope)
	case "remove_member":
		handler.removeMember(w, r, scope, req.TargetUserID)
	case "reset_member_password":
		handler.resetMemberPassword(w, r, scope, req.TargetUserID, req.NewPassword, req.NewPasswordConfirm, req.Confirm)
	case "rename_group":
		handler.renameGroup(w, r, scope, req.GroupID, strings.TrimSpace(req.NewGroupName))
	default:
		httpx.Error(w, httpx.BadRequest("Unknown action."))
	}
}

func (handler GroupsHandler) joinByCode(w http.ResponseWriter, r *http.Request, groupCode string) {
	scope, _, err := handler.scope.requireUser(r)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	ip := httpx.ClientIP(r)
	admitted, exhausted, err := handler.limits.ReserveGroupJoinAttempt(r.Context(), scope.User.ID, ip, 10, 30*time.Minute)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if !admitted {
		httpx.Error(w, httpx.APIError{Status: 423, Message: "组号尝试过多，请 30 分钟后再试。"})
		return
	}
	if !groupCodePattern.MatchString(groupCode) {
		if exhausted {
			httpx.Error(w, httpx.APIError{Status: 423, Message: "组号尝试过多，请 30 分钟后再试。"})
			return
		}
		httpx.Error(w, httpx.Unprocessable("组号格式不正确。"))
		return
	}
	var groupName string
	err = handler.db.QueryRowContext(r.Context(), "SELECT group_name FROM family_groups WHERE group_code = ? OR legacy_group_code = ? LIMIT 1", groupCode, groupCode).Scan(&groupName)
	if err == sql.ErrNoRows {
		if exhausted {
			httpx.Error(w, httpx.APIError{Status: 423, Message: "组号尝试过多，请 30 分钟后再试。"})
			return
		}
		httpx.Error(w, httpx.APIError{Status: http.StatusNotFound, Message: "家庭组不存在。"})
		return
	}
	if err != nil {
		httpx.Error(w, err)
		return
	}
	tx, err := handler.db.BeginTx(r.Context(), nil)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	defer func() { _ = tx.Rollback() }()
	if _, err := tx.ExecContext(r.Context(), "INSERT IGNORE INTO user_groups (user_id, group_name, role) VALUES (?, ?, 'guardian')", scope.User.ID, groupName); err != nil {
		httpx.Error(w, err)
		return
	}
	if strings.TrimSpace(scope.User.GroupName) == "" {
		if _, err := tx.ExecContext(r.Context(), "UPDATE users SET group_name = ?, role = 'guardian' WHERE id = ?", groupName, scope.User.ID); err != nil {
			httpx.Error(w, err)
			return
		}
	}
	if err := tx.Commit(); err != nil {
		httpx.Error(w, err)
		return
	}
	_ = handler.limits.ClearFailedGroupJoin(r.Context(), scope.User.ID, ip)
	handler.respondFreshUser(w, r, scope.User.ID)
}

func (handler GroupsHandler) leaveGroup(w http.ResponseWriter, r *http.Request, scope *userScope) {
	if scope == nil || scope.Membership == nil {
		httpx.Error(w, httpx.Forbidden("无权访问该家庭组。"))
		return
	}
	tx, err := handler.db.BeginTx(r.Context(), nil)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	defer func() { _ = tx.Rollback() }()
	groupName := scope.Membership.GroupName
	if _, err := tx.ExecContext(r.Context(), "DELETE FROM user_groups WHERE user_id = ? AND group_name = ?", scope.User.ID, groupName); err != nil {
		httpx.Error(w, err)
		return
	}
	if _, err := tx.ExecContext(r.Context(), "DELETE FROM latest_group_locations WHERE user_id = ? AND group_name = ?", scope.User.ID, groupName); err != nil {
		httpx.Error(w, err)
		return
	}
	if _, err := tx.ExecContext(r.Context(), "UPDATE family_groups SET owner_user_id = NULL WHERE group_name = ? AND owner_user_id = ?", groupName, scope.User.ID); err != nil {
		httpx.Error(w, err)
		return
	}
	var nextGroup string
	var nextRole string
	err = tx.QueryRowContext(r.Context(), "SELECT group_name, role FROM user_groups WHERE user_id = ? ORDER BY group_name ASC, id ASC LIMIT 1", scope.User.ID).Scan(&nextGroup, &nextRole)
	if err == sql.ErrNoRows {
		_, err = tx.ExecContext(r.Context(), "UPDATE users SET group_name = '', role = 'guardian' WHERE id = ?", scope.User.ID)
	} else if err == nil {
		_, err = tx.ExecContext(r.Context(), "UPDATE users SET group_name = ?, role = ? WHERE id = ?", nextGroup, nextRole, scope.User.ID)
	}
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if err := tx.Commit(); err != nil {
		httpx.Error(w, err)
		return
	}
	id := scope.User.ID
	_ = handler.users.RecordLog(r.Context(), &id, groupName, "group_leave", "用户退出家庭组", nil, httpx.ClientIP(r), r.UserAgent())
	handler.respondFreshUser(w, r, scope.User.ID)
}

func (handler GroupsHandler) removeMember(w http.ResponseWriter, r *http.Request, scope *userScope, targetUserID int64) {
	group, err := handler.requireGroupOwner(r, scope)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if targetUserID <= 0 {
		httpx.Error(w, httpx.Unprocessable("成员不存在。"))
		return
	}
	if targetUserID == scope.User.ID {
		httpx.Error(w, httpx.Unprocessable("管理员不能踢出自己，请先移交管理员或主动退组。"))
		return
	}
	tx, err := handler.db.BeginTx(r.Context(), nil)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	defer func() { _ = tx.Rollback() }()
	var membershipID int64
	err = tx.QueryRowContext(r.Context(), "SELECT id FROM user_groups WHERE user_id = ? AND group_name = ? LIMIT 1", targetUserID, group.GroupName).Scan(&membershipID)
	if err == sql.ErrNoRows {
		httpx.Error(w, httpx.APIError{Status: http.StatusNotFound, Message: "成员不在当前家庭组。"})
		return
	}
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if _, err := tx.ExecContext(r.Context(), "DELETE FROM user_groups WHERE id = ?", membershipID); err != nil {
		httpx.Error(w, err)
		return
	}
	if _, err := tx.ExecContext(r.Context(), "DELETE FROM latest_group_locations WHERE user_id = ? AND group_name = ?", targetUserID, group.GroupName); err != nil {
		httpx.Error(w, err)
		return
	}
	var nextGroup string
	var nextRole string
	err = tx.QueryRowContext(r.Context(), "SELECT group_name, role FROM user_groups WHERE user_id = ? ORDER BY group_name ASC, id ASC LIMIT 1", targetUserID).Scan(&nextGroup, &nextRole)
	if err == sql.ErrNoRows {
		_, err = tx.ExecContext(r.Context(), "UPDATE users SET group_name = '', role = 'guardian' WHERE id = ? AND group_name = ?", targetUserID, group.GroupName)
	} else if err == nil {
		_, err = tx.ExecContext(r.Context(), "UPDATE users SET group_name = ?, role = ? WHERE id = ? AND group_name = ?", nextGroup, nextRole, targetUserID, group.GroupName)
	}
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if err := tx.Commit(); err != nil {
		httpx.Error(w, err)
		return
	}
	id := scope.User.ID
	_ = handler.users.RecordLog(r.Context(), &id, group.GroupName, "group_member_remove", "家庭组管理员移除成员", map[string]any{"target_user_id": targetUserID}, httpx.ClientIP(r), r.UserAgent())
	targetID := targetUserID
	_ = handler.users.RecordLog(r.Context(), &targetID, group.GroupName, "group_removed", "被家庭组管理员移出家庭组", nil, httpx.ClientIP(r), r.UserAgent())
	handler.respondFreshUser(w, r, scope.User.ID)
}

func (handler GroupsHandler) resetMemberPassword(w http.ResponseWriter, r *http.Request, scope *userScope, targetUserID int64, newPassword string, newPasswordConfirm string, confirmed bool) {
	group, err := handler.requireGroupOwner(r, scope)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if targetUserID <= 0 {
		httpx.Error(w, httpx.Unprocessable("成员不存在。"))
		return
	}
	if targetUserID == scope.User.ID {
		httpx.Error(w, httpx.Unprocessable("请在账号安全里修改自己的密码。"))
		return
	}
	if !confirmed {
		httpx.Error(w, httpx.Unprocessable("请先二次确认重置操作。"))
		return
	}
	if strings.TrimSpace(newPassword) == "" || strings.TrimSpace(newPasswordConfirm) == "" {
		httpx.Error(w, httpx.Unprocessable("请填写两遍新密码。"))
		return
	}
	if len(newPassword) < 6 {
		httpx.Error(w, httpx.Unprocessable("新密码至少 6 位。"))
		return
	}
	if newPassword != newPasswordConfirm {
		httpx.Error(w, httpx.Unprocessable("两次输入的新密码不一致。"))
		return
	}
	var found int64
	err = handler.db.QueryRowContext(r.Context(), `
SELECT u.id
FROM user_groups ug
INNER JOIN users u ON u.id = ug.user_id
WHERE ug.user_id = ? AND ug.group_name = ?
LIMIT 1`, targetUserID, group.GroupName).Scan(&found)
	if err == sql.ErrNoRows {
		httpx.Error(w, httpx.APIError{Status: http.StatusNotFound, Message: "成员不在当前家庭组。"})
		return
	}
	if err != nil {
		httpx.Error(w, err)
		return
	}
	var memberships int
	if err := handler.db.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM user_groups WHERE user_id = ?", targetUserID).Scan(&memberships); err != nil {
		httpx.Error(w, err)
		return
	}
	if memberships != 1 {
		httpx.Error(w, httpx.APIError{Status: http.StatusConflict, Message: "该成员属于多个家庭组，请前往工单系统申请重置密码。"})
		return
	}
	if err := handler.updateMemberPasswordAndRevokeSessions(r, targetUserID, newPassword); err != nil {
		httpx.Error(w, err)
		return
	}
	id := scope.User.ID
	_ = handler.users.RecordLog(r.Context(), &id, group.GroupName, "member_password_reset", "家庭组管理员重置成员密码", map[string]any{"target_user_id": targetUserID}, httpx.ClientIP(r), r.UserAgent())
	targetID := targetUserID
	_ = handler.users.RecordLog(r.Context(), &targetID, group.GroupName, "password_reset_by_group_owner", "家庭组管理员重置了账号密码", nil, httpx.ClientIP(r), r.UserAgent())
	handler.respondFreshUser(w, r, scope.User.ID)
}

func (handler GroupsHandler) updateMemberPasswordAndRevokeSessions(r *http.Request, userID int64, newPassword string) error {
	hash, err := services.HashPassword(newPassword)
	if err != nil {
		return err
	}
	if err := handler.scope.sessions.Repo.UpdatePasswordAndRevokeUserSessions(r.Context(), userID, hash); err != nil {
		return err
	}
	_ = handler.users.ClearFailedLogin(r.Context(), userID)
	return nil
}

func (handler GroupsHandler) renameGroup(w http.ResponseWriter, r *http.Request, scope *userScope, groupID int64, newGroupName string) {
	if groupID <= 0 || strings.TrimSpace(newGroupName) == "" {
		httpx.Error(w, httpx.Unprocessable("家庭组信息不完整。"))
		return
	}
	var ownerUserID int64
	err := handler.db.QueryRowContext(r.Context(), "SELECT owner_user_id FROM family_groups WHERE id = ? LIMIT 1", groupID).Scan(&ownerUserID)
	if err == sql.ErrNoRows || ownerUserID != scope.User.ID {
		httpx.Error(w, httpx.Forbidden("只有家庭组首个用户可以管理。"))
		return
	}
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if _, err := handler.db.ExecContext(r.Context(), "UPDATE family_groups SET display_name = ? WHERE id = ?", newGroupName, groupID); err != nil {
		httpx.Error(w, err)
		return
	}
	handler.respondFreshUser(w, r, scope.User.ID)
}

func (handler GroupsHandler) requireGroupOwner(r *http.Request, scope *userScope) (*models.Membership, error) {
	if scope == nil || scope.Membership == nil {
		return nil, httpx.Forbidden("无权访问该家庭组。")
	}
	var ownerUserID int64
	err := handler.db.QueryRowContext(r.Context(), "SELECT owner_user_id FROM family_groups WHERE group_name = ? LIMIT 1", scope.Membership.GroupName).Scan(&ownerUserID)
	if err == sql.ErrNoRows || ownerUserID != scope.User.ID {
		return nil, httpx.Forbidden("只有家庭组首个用户可以管理。")
	}
	if err != nil {
		return nil, err
	}
	return scope.Membership, nil
}

func (handler GroupsHandler) respondFreshUser(w http.ResponseWriter, r *http.Request, userID int64) {
	user, err := handler.users.FindActiveByID(r.Context(), userID)
	if err != nil || user == nil {
		httpx.Error(w, err)
		return
	}
	groups, _ := handler.groups.ForUser(r.Context(), userID)
	httpx.OK(w, map[string]any{
		"ok":   true,
		"user": services.PublicUserPayloadForGroups(*user, groups, firstMembership(groups)),
	})
}
