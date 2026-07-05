package repositories

import (
	"context"
	"database/sql"

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

func (repo UserRepository) UpdateEnvironmentConsent(ctx context.Context, userID int64, consentAt any) error {
	_, err := repo.db.ExecContext(ctx, "UPDATE users SET environment_data_consent_at = ? WHERE id = ?", consentAt, userID)
	return err
}

func (repo UserRepository) UpdatePasswordHash(ctx context.Context, userID int64, passwordHash string) error {
	_, err := repo.db.ExecContext(ctx, "UPDATE users SET password_hash = ? WHERE id = ?", passwordHash, userID)
	return err
}

