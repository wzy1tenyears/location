package services

import (
	"encoding/json"
	"math"
	"strings"
	"time"

	"familylocation/location-v3/internal/models"
)

func UserTermsAccepted(user models.User) bool {
	return user.TermsAcceptedAt.Valid ||
		(user.UserAgreementAcceptedAt.Valid &&
			user.PrivacyPolicyAcceptedAt.Valid &&
			user.CrossBorderTransferAcceptedAt.Valid)
}

func PublicUserPayload(user models.User) map[string]any {
	return map[string]any{
		"id":                             user.ID,
		"username":                       user.Username,
		"display_name":                   firstNonEmpty(user.DisplayName, user.Username),
		"group_name":                     user.GroupName,
		"role":                           user.Role,
		"role_label":                     RoleLabel(user.Role),
		"terms_accepted":                 UserTermsAccepted(user),
		"user_agreement_accepted":        user.UserAgreementAcceptedAt.Valid,
		"privacy_policy_accepted":        user.PrivacyPolicyAcceptedAt.Valid,
		"cross_border_transfer_accepted": user.CrossBorderTransferAcceptedAt.Valid,
		"environment_data_consent":       user.EnvironmentDataConsentAt.Valid,
		"report_interval_seconds":        NormalizeReportIntervalSeconds(user.ReportIntervalSeconds),
		"created_at":                     FormatDatetime(user.CreatedAt),
		"updated_at":                     FormatDatetime(user.UpdatedAt),
	}
}

func PublicUserPayloadForGroups(user models.User, groups []models.Membership, selected *models.Membership) map[string]any {
	payload := PublicUserPayload(user)
	if selected != nil {
		payload["group_name"] = selected.GroupName
		payload["role"] = NormalizeRole(selected.Role)
		payload["role_label"] = RoleLabel(selected.Role)
	} else if len(groups) > 0 {
		payload["group_name"] = groups[0].GroupName
		payload["role"] = NormalizeRole(groups[0].Role)
		payload["role_label"] = RoleLabel(groups[0].Role)
	}

	groupPayloads := make([]map[string]any, 0, len(groups))
	for _, group := range groups {
		groupPayloads = append(groupPayloads, GroupPayload(group))
	}
	payload["groups"] = groupPayloads
	return payload
}

func GroupPayload(group models.Membership) map[string]any {
	return map[string]any{
		"id":              group.ID,
		"group_name":      group.GroupName,
		"display_name":    firstNonEmpty(group.GroupDisplayName, group.GroupName),
		"group_code":      group.GroupCode,
		"owner_user_id":   group.OwnerUserID,
		"role":            NormalizeRole(group.Role),
		"role_label":      RoleLabel(group.Role),
		"p2p_enabled":     group.P2PEnabledAt.Valid,
		"p2p_key_version": group.P2PKeyVersion,
	}
}

func MemberPayload(member models.GroupMember) map[string]any {
	return map[string]any{
		"user_id":      member.UserID,
		"username":     member.Username,
		"display_name": member.DisplayName,
		"role":         NormalizeRole(member.Role),
		"role_label":   RoleLabel(member.Role),
	}
}

func LocationPayload(location models.Location, staleSeconds int) map[string]any {
	payload := map[string]any{
		"user_id":             location.UserID,
		"username":            location.Username,
		"display_name":        location.DisplayName,
		"role":                NormalizeRole(location.Role),
		"role_label":          RoleLabel(location.Role),
		"group_name":          location.GroupName,
		"latitude":            location.Latitude,
		"longitude":           location.Longitude,
		"altitude":            nullableFloat(location.Altitude.Valid, location.Altitude.Float64),
		"accuracy":            nullableFloat(location.Accuracy.Valid, location.Accuracy.Float64),
		"heading":             nullableFloat(location.Heading.Valid, location.Heading.Float64),
		"speed":               nullableFloat(location.Speed.Valid, location.Speed.Float64),
		"location_meta":       jsonObject(location.LocationMeta.String, location.LocationMeta.Valid),
		"address_mismatch":    location.AddressMismatch,
		"address_diagnostics": jsonObject(location.AddressDiagnostics.String, location.AddressDiagnostics.Valid),
		"encryption_mode":     location.EncryptionMode,
		"encrypted_payload":   location.EncryptedPayload,
		"p2p_key_version":     location.P2PKeyVersion,
		"updated_at":          FormatDatetime(location.UpdatedAt),
		"is_stale":            location.UpdatedAt.Before(time.Now().Add(-time.Duration(staleSeconds) * time.Second)),
	}
	if location.ID > 0 {
		payload["id"] = location.ID
		payload["created_at"] = FormatDatetime(location.CreatedAt)
	}
	return payload
}

func NormalizeRole(role string) string {
	switch role {
	case "monitor", "guardian":
		return role
	case "parent":
		return "monitor"
	default:
		return role
	}
}

func RoleLabel(role string) string {
	switch NormalizeRole(role) {
	case "monitor":
		return "监测端"
	case "guardian":
		return "监护端"
	default:
		return role
	}
}

func NormalizeReportIntervalSeconds(seconds int) int {
	if seconds < 60 {
		return 60
	}
	if seconds > 86400 {
		return 86400
	}
	return seconds
}

func FormatDatetime(value time.Time) string {
	if value.IsZero() {
		return ""
	}
	return value.In(time.Local).Format("2006-01-02 15:04:05")
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if value != "" {
			return value
		}
	}
	return ""
}

func nullableFloat(valid bool, value float64) any {
	if !valid || math.IsNaN(value) || math.IsInf(value, 0) {
		return nil
	}
	return value
}

func jsonObject(raw string, valid bool) any {
	if !valid || strings.TrimSpace(raw) == "" {
		return nil
	}
	var decoded any
	if err := json.Unmarshal([]byte(raw), &decoded); err != nil {
		return nil
	}
	return decoded
}

