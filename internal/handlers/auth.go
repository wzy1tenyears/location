package handlers

import (
	"database/sql"
	"net/http"
	"strings"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/models"
	"familylocation/location-v3/internal/repositories"
	"familylocation/location-v3/internal/services"
	"familylocation/location-v3/internal/session"
)

type userScope struct {
	User       *models.User
	Groups     []models.Membership
	Membership *models.Membership
}

type scopedHandler struct {
	users    repositories.UserRepository
	groups   repositories.GroupRepository
	sessions session.Reader
}

func newScopedHandler(db *sql.DB, sessions session.Reader) scopedHandler {
	return scopedHandler{
		users:    repositories.NewUserRepository(db),
		groups:   repositories.NewGroupRepository(db),
		sessions: sessions,
	}
}

func (handler scopedHandler) requireScope(r *http.Request, groupName string) (*userScope, bool, error) {
	scope, ok, err := handler.requireUser(r)
	if err != nil || !ok {
		return nil, ok, err
	}
	membership, err := handler.groups.MembershipForUser(r.Context(), scope.User.ID, truncateGroupName(groupName))
	if err != nil {
		return nil, false, err
	}
	if membership == nil && groupName == "" {
		return nil, false, httpx.APIError{Status: http.StatusConflict, Message: "账号还没有家庭组。"}
	}
	if membership == nil {
		return nil, false, httpx.Forbidden("无权访问该家庭组。")
	}
	scope.Membership = membership
	return scope, true, nil
}

func (handler scopedHandler) requireUser(r *http.Request) (*userScope, bool, error) {
	userID, ok := handler.sessions.UserID(r)
	if !ok {
		return nil, false, httpx.Unauthorized("请先登录。")
	}
	user, err := handler.users.FindActiveByID(r.Context(), userID)
	if err != nil {
		return nil, false, err
	}
	if user == nil {
		return nil, false, httpx.Unauthorized("请先登录。")
	}
	if !services.UserTermsAccepted(*user) {
		return nil, false, httpx.Forbidden("请先同意用户协议、隐私条约和用户数据跨境加密传输协议。")
	}

	groups, err := handler.groups.ForUser(r.Context(), user.ID)
	if err != nil {
		return nil, false, err
	}
	return &userScope{User: user, Groups: groups}, true, nil
}

func selectedGroupName(r *http.Request, bodyGroupName string) string {
	if bodyGroupName != "" {
		return bodyGroupName
	}
	return strings.TrimSpace(r.URL.Query().Get("group_name"))
}

func truncateGroupName(groupName string) string {
	groupName = strings.TrimSpace(groupName)
	runes := []rune(groupName)
	if len(runes) > 100 {
		return string(runes[:100])
	}
	return groupName
}
