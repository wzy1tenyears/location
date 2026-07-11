package handlers

import (
	"database/sql"
	"net/http"
	"time"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/repositories"
	"familylocation/location-v3/internal/services"
	"familylocation/location-v3/internal/session"
)

type SettingsHandler struct {
	scope scopedHandler
	users repositories.UserRepository
}

func NewSettingsHandler(db *sql.DB, sessions session.Reader) SettingsHandler {
	return SettingsHandler{
		scope: newScopedHandler(db, sessions),
		users: repositories.NewUserRepository(db),
	}
}

type settingsRequest struct {
	GroupName              string `json:"group_name"`
	Action                 string `json:"action"`
	EnvironmentDataConsent bool   `json:"environment_data_consent"`
	CurrentPassword        string `json:"current_password"`
	NewPassword            string `json:"new_password"`
	NewPasswordConfirm     string `json:"new_password_confirm"`
}

func (handler SettingsHandler) ShowOrUpdate(w http.ResponseWriter, r *http.Request) {
	var req settingsRequest
	if r.Method == http.MethodPost {
		if err := httpx.DecodeJSON(r, &req); err != nil {
			httpx.Error(w, err)
			return
		}
	}

	scope, _, err := handler.scope.requireScope(r, selectedGroupName(r, req.GroupName))
	if err != nil {
		httpx.Error(w, err)
		return
	}

	if r.Method == http.MethodPost {
		if req.Action == "change_password" {
			if err := handler.changePassword(r, scope.User.ID, scope.User.PasswordHash, req); err != nil {
				httpx.Error(w, err)
				return
			}
		} else {
			var consentAt any
			if req.EnvironmentDataConsent {
				consentAt = time.Now()
				scope.User.EnvironmentDataConsentAt.Valid = true
				scope.User.EnvironmentDataConsentAt.Time = consentAt.(time.Time)
			} else {
				consentAt = nil
				scope.User.EnvironmentDataConsentAt.Valid = false
			}
			if err := handler.users.UpdateEnvironmentConsent(r.Context(), scope.User.ID, consentAt); err != nil {
				httpx.Error(w, err)
				return
			}
		}
	}

	httpx.OK(w, map[string]any{
		"ok":                      true,
		"user":                    services.PublicUserPayloadForGroups(*scope.User, scope.Groups, scope.Membership),
		"selected_group":          services.GroupPayload(*scope.Membership),
		"report_interval_seconds": services.NormalizeReportIntervalSeconds(scope.User.ReportIntervalSeconds),
		"server_time":             time.Now().Format("2006-01-02 15:04:05"),
	})
}

func (handler SettingsHandler) changePassword(r *http.Request, userID int64, currentHash string, req settingsRequest) error {
	if req.CurrentPassword == "" || req.NewPassword == "" || req.NewPasswordConfirm == "" {
		return httpx.Unprocessable("请填写完整密码信息。")
	}
	if !services.CheckPassword(req.CurrentPassword, currentHash) {
		return httpx.Forbidden("当前密码不正确。")
	}
	if len(req.NewPassword) < 6 {
		return httpx.Unprocessable("新密码至少 6 位。")
	}
	if req.NewPassword != req.NewPasswordConfirm {
		return httpx.Unprocessable("两次输入的新密码不一致。")
	}
	if services.CheckPassword(req.NewPassword, currentHash) {
		return httpx.Unprocessable("新密码不能与当前密码相同。")
	}
	hash, err := services.HashPassword(req.NewPassword)
	if err != nil {
		return err
	}
	return handler.users.UpdatePasswordHash(r.Context(), userID, hash)
}
