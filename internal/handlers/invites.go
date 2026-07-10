package handlers

import (
	"database/sql"
	"net/http"
	"regexp"
	"strings"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/repositories"
)

var inviteCodePattern = regexp.MustCompile(`^[0-9a-zA-Z]{1,255}$`)

type InviteHandler struct {
	invites repositories.InviteRepository
}

func NewInviteHandler(db *sql.DB) InviteHandler {
	return InviteHandler{invites: repositories.NewInviteRepository(db)}
}

func (handler InviteHandler) Check(w http.ResponseWriter, r *http.Request) {
	code := strings.TrimSpace(r.URL.Query().Get("code"))
	if code == "" && r.Method != http.MethodGet {
		var body struct {
			Code string `json:"code"`
		}
		_ = httpx.DecodeJSON(r, &body)
		code = strings.TrimSpace(body.Code)
	}

	if !inviteCodePattern.MatchString(code) {
		httpx.Error(w, httpx.Unprocessable("邀请码格式不正确。"))
		return
	}

	invite, err := handler.invites.FindUsableByCode(r.Context(), code)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if invite == nil {
		httpx.Error(w, httpx.Forbidden("邀请码无效或次数已用完。"))
		return
	}

	requiresGroupName := invite.InviteType == "group_create" && strings.TrimSpace(invite.AssignedGroupName) == ""
	requiresGroupCode := invite.InviteType != "group_create"
	httpx.OK(w, map[string]any{
		"ok":                  true,
		"requires_group_name": requiresGroupName,
		"requires_group_code": requiresGroupCode,
		"message":             "邀请码可用。",
	})
}
