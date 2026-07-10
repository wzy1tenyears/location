package models

import (
	"database/sql"
	"time"
)

type User struct {
	ID                            int64
	Username                      string
	DisplayName                   string
	PasswordHash                  string
	GroupName                     string
	Role                          string
	IsActive                      bool
	DisabledReason                string
	FailedLoginCount              int
	LoginLockedAt                 sql.NullTime
	DebugMode                     bool
	ReportIntervalSeconds         int
	TermsAcceptedAt               sql.NullTime
	UserAgreementAcceptedAt       sql.NullTime
	PrivacyPolicyAcceptedAt       sql.NullTime
	CrossBorderTransferAcceptedAt sql.NullTime
	EnvironmentDataConsentAt      sql.NullTime
	CreatedAt                     time.Time
	UpdatedAt                     time.Time
}

type Announcement struct {
	ID        int64
	Title     string
	Body      string
	Version   int
	UpdatedAt time.Time
}

type InviteCode struct {
	ID                int64
	Code              string
	InviteType        string
	AssignedGroupName string
	UsedCount         int
	MaxUses           int
	IsActive          bool
}

type AppChallenge struct {
	ID                string
	SecretHash        string
	DeviceFingerprint string
	Purpose           string
	VerifiedAt        sql.NullTime
	ConsumedAt        sql.NullTime
	ExpiresAt         time.Time
}

type Membership struct {
	ID               int64
	UserID           int64
	GroupName        string
	Role             string
	GroupCode        string
	GroupDisplayName string
	OwnerUserID      int64
	P2PEnabledAt     sql.NullTime
	P2PKeyVersion    int
}

type GroupMember struct {
	UserID      int64
	Username    string
	DisplayName string
	Role        string
}

type Location struct {
	ID                 int64
	UserID             int64
	Username           string
	DisplayName        string
	GroupName          string
	Role               string
	Latitude           float64
	Longitude          float64
	Altitude           sql.NullFloat64
	Accuracy           sql.NullFloat64
	Heading            sql.NullFloat64
	Speed              sql.NullFloat64
	LocationMeta       sql.NullString
	AddressDiagnostics sql.NullString
	AddressMismatch    bool
	EncryptionMode     string
	EncryptedPayload   string
	P2PKeyVersion      int
	CreatedAt          time.Time
	UpdatedAt          time.Time
}
