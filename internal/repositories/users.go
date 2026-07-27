package repositories

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"

	"familylocation/location-v3/internal/models"
)

type UserRepository struct {
	db *sql.DB
}

func NewUserRepository(db *sql.DB) UserRepository {
	return UserRepository{db: db}
}

func (repo UserRepository) FindActiveByID(ctx context.Context, id int64) (*models.User, error) {
	const query = `
	SELECT
	id,
	username,
	display_name,
	password_hash,
	group_name,
	role,
	is_active,
	disabled_reason,
	failed_login_count,
	login_locked_at,
	debug_mode,
	report_interval_seconds,
	terms_accepted_at,
	user_agreement_accepted_at,
	privacy_policy_accepted_at,
	cross_border_transfer_accepted_at,
	environment_data_consent_at,
	created_at,
	updated_at
FROM users
WHERE id = ? AND is_active = 1
LIMIT 1`

	var user models.User
	err := repo.db.QueryRowContext(ctx, query, id).Scan(
		&user.ID,
		&user.Username,
		&user.DisplayName,
		&user.PasswordHash,
		&user.GroupName,
		&user.Role,
		&user.IsActive,
		&user.DisabledReason,
		&user.FailedLoginCount,
		&user.LoginLockedAt,
		&user.DebugMode,
		&user.ReportIntervalSeconds,
		&user.TermsAcceptedAt,
		&user.UserAgreementAcceptedAt,
		&user.PrivacyPolicyAcceptedAt,
		&user.CrossBorderTransferAcceptedAt,
		&user.EnvironmentDataConsentAt,
		&user.CreatedAt,
		&user.UpdatedAt,
	)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &user, nil
}

func (repo UserRepository) FindByUsername(ctx context.Context, username string) (*models.User, error) {
	const query = `
	SELECT
	id,
	username,
	display_name,
	password_hash,
	group_name,
	role,
	is_active,
	disabled_reason,
	failed_login_count,
	login_locked_at,
	debug_mode,
	report_interval_seconds,
	terms_accepted_at,
	user_agreement_accepted_at,
	privacy_policy_accepted_at,
	cross_border_transfer_accepted_at,
	environment_data_consent_at,
	created_at,
	updated_at
FROM users
WHERE username = ?
LIMIT 1`
	var user models.User
	err := repo.db.QueryRowContext(ctx, query, username).Scan(
		&user.ID,
		&user.Username,
		&user.DisplayName,
		&user.PasswordHash,
		&user.GroupName,
		&user.Role,
		&user.IsActive,
		&user.DisabledReason,
		&user.FailedLoginCount,
		&user.LoginLockedAt,
		&user.DebugMode,
		&user.ReportIntervalSeconds,
		&user.TermsAcceptedAt,
		&user.UserAgreementAcceptedAt,
		&user.PrivacyPolicyAcceptedAt,
		&user.CrossBorderTransferAcceptedAt,
		&user.EnvironmentDataConsentAt,
		&user.CreatedAt,
		&user.UpdatedAt,
	)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &user, nil
}

func (repo UserRepository) SetTermsAccepted(ctx context.Context, userID int64, acceptedAt any) error {
	_, err := repo.db.ExecContext(ctx, `
UPDATE users
SET
	terms_accepted_at = ?,
	user_agreement_accepted_at = ?,
	privacy_policy_accepted_at = ?,
	cross_border_transfer_accepted_at = ?
WHERE id = ?`, acceptedAt, acceptedAt, acceptedAt, acceptedAt, userID)
	return err
}

func (repo UserRepository) ClearFailedLogin(ctx context.Context, userID int64) error {
	_, err := repo.db.ExecContext(ctx, "UPDATE users SET failed_login_count = 0, login_locked_at = NULL WHERE id = ?", userID)
	return err
}

func (repo UserRepository) RecordLog(ctx context.Context, userID *int64, groupName string, eventType string, message string, metaJSON any, ip string, userAgent string) error {
	serializedMeta, err := serializeLogMeta(metaJSON)
	if err != nil {
		return err
	}
	_, err = repo.db.ExecContext(ctx, `
INSERT INTO user_logs (user_id, group_name, event_type, message, meta_json, ip, user_agent)
VALUES (?, ?, ?, ?, ?, ?, ?)`,
		userID,
		textLimit(groupName, 100),
		textLimit(eventType, 40),
		textLimit(message, 255),
		serializedMeta,
		textLimit(ip, 45),
		textLimit(userAgent, 255),
	)
	return err
}

func serializeLogMeta(value any) (any, error) {
	switch typed := value.(type) {
	case nil, string, []byte:
		return typed, nil
	case json.RawMessage:
		if !json.Valid(typed) {
			return nil, errors.New("log meta JSON is invalid")
		}
		return string(typed), nil
	default:
		payload, err := json.Marshal(value)
		if err != nil {
			return nil, err
		}
		return string(payload), nil
	}
}

func (repo UserRepository) TouchPresence(ctx context.Context, userID int64, groupName string, userAgent string, ip string) error {
	_, err := repo.db.ExecContext(ctx, `
INSERT INTO user_presence (user_id, last_seen_at, last_group_name, last_user_agent, last_ip)
VALUES (?, NOW(), ?, ?, ?)
ON DUPLICATE KEY UPDATE
	last_seen_at = NOW(),
	last_group_name = VALUES(last_group_name),
	last_user_agent = VALUES(last_user_agent),
	last_ip = VALUES(last_ip)`,
		userID,
		textLimit(groupName, 100),
		textLimit(userAgent, 255),
		textLimit(ip, 45),
	)
	return err
}

func textLimit(value string, maxLength int) string {
	runes := []rune(value)
	if len(runes) <= maxLength {
		return value
	}
	return string(runes[:maxLength])
}

func (repo UserRepository) UpdateEnvironmentConsent(ctx context.Context, userID int64, consentAt any) error {
	_, err := repo.db.ExecContext(ctx, "UPDATE users SET environment_data_consent_at = ? WHERE id = ?", consentAt, userID)
	return err
}

func (repo UserRepository) UpdatePasswordHash(ctx context.Context, userID int64, passwordHash string) error {
	_, err := repo.db.ExecContext(ctx, "UPDATE users SET password_hash = ? WHERE id = ?", passwordHash, userID)
	return err
}
