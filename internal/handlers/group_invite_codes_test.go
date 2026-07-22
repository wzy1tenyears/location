package handlers

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"testing"

	"familylocation/location-v3/internal/httpx"

	"github.com/DATA-DOG/go-sqlmock"
	"github.com/go-sql-driver/mysql"
)

func expectInviteGroupExists(mock sqlmock.Sqlmock, groupName string) {
	mock.ExpectQuery(`SELECT 1 FROM family_groups WHERE group_name = \? LIMIT 1`).
		WithArgs(groupName).
		WillReturnRows(sqlmock.NewRows([]string{"exists"}).AddRow(1))
}

func TestFindGroupNameByCodeReadsCurrentAndLegacyAlias(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	legacy := strings.Repeat("a", 32)
	mock.ExpectBegin()
	mock.ExpectQuery(`SELECT group_name FROM family_groups WHERE group_code = \? OR legacy_group_code = \? LIMIT 1`).
		WithArgs(legacy, legacy).
		WillReturnRows(sqlmock.NewRows([]string{"group_name"}).AddRow("family"))
	mock.ExpectRollback()
	tx, err := db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	groupName, err := findGroupNameByCodeTx(context.Background(), tx, legacy)
	if err != nil {
		t.Fatal(err)
	}
	if groupName != "family" {
		t.Fatalf("group name = %q, want family", groupName)
	}
	if err := tx.Rollback(); err != nil {
		t.Fatal(err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestCreateFamilyGroupRetriesOnlyGroupCodeDuplicate(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	mock.ExpectBegin()
	for attempt := 0; attempt < 2; attempt++ {
		mock.ExpectQuery(`SELECT COUNT\(\*\) FROM family_groups WHERE group_name = \? LIMIT 1`).
			WithArgs("Family").
			WillReturnRows(sqlmock.NewRows([]string{"count"}).AddRow(0))
		insert := mock.ExpectExec(`INSERT INTO family_groups`).
			WithArgs("Family", "Family", sqlmock.AnyArg(), nil)
		if attempt == 0 {
			insert.WillReturnError(&mysql.MySQLError{Number: 1062, Message: "Duplicate entry 'abcdefgh' for key 'family_groups.group_code'"})
		} else {
			insert.WillReturnResult(sqlmock.NewResult(1, 1))
		}
	}
	mock.ExpectRollback()
	tx, err := db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	groupName, err := createFamilyGroupRecordTx(context.Background(), tx, "Family", nil, currentGroupCodeWriteMode)
	if err != nil {
		t.Fatal(err)
	}
	if groupName != "Family" {
		t.Fatalf("group name = %q", groupName)
	}
	if err := tx.Rollback(); err != nil {
		t.Fatal(err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestStageOneCreatedGroupRemainsVisibleToLegacy32CharacterReader(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	const legacyCode = "00000000000000000000000000000000"
	mock.ExpectBegin()
	mock.ExpectQuery(`SELECT COUNT\(\*\) FROM family_groups WHERE group_name = \? LIMIT 1`).
		WithArgs("Family").
		WillReturnRows(sqlmock.NewRows([]string{"count"}).AddRow(0))
	mock.ExpectExec(`INSERT INTO family_groups`).
		WithArgs("Family", "Family", legacyCode, nil).
		WillReturnResult(sqlmock.NewResult(1, 1))
	mock.ExpectQuery(`SELECT group_name FROM family_groups WHERE group_code = \? OR legacy_group_code = \? LIMIT 1`).
		WithArgs(legacyCode, legacyCode).
		WillReturnRows(sqlmock.NewRows([]string{"group_name"}).AddRow("Family"))
	mock.ExpectRollback()

	tx, err := db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	groupName, err := createFamilyGroupRecordTxWithReader(
		context.Background(), tx, "Family", nil, legacyGroupCodeWriteMode,
		bytes.NewReader(make([]byte, legacyGroupCodeByteLength)),
	)
	if err != nil || groupName != "Family" {
		t.Fatalf("stage-one create = %q, error %v", groupName, err)
	}
	legacyVisibleName, err := findGroupNameByCodeTx(context.Background(), tx, legacyCode)
	if err != nil || legacyVisibleName != "Family" {
		t.Fatalf("legacy reader lookup = %q, error %v", legacyVisibleName, err)
	}
	if err := tx.Rollback(); err != nil {
		t.Fatal(err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestCreateFamilyGroupRetriesConcurrentGroupNameCollisionPrecisely(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	mock.ExpectBegin()
	mock.ExpectQuery(`SELECT COUNT\(\*\) FROM family_groups WHERE group_name = \? LIMIT 1`).
		WithArgs("Family").
		WillReturnRows(sqlmock.NewRows([]string{"count"}).AddRow(0))
	mock.ExpectExec(`INSERT INTO family_groups`).
		WithArgs("Family", "Family", sqlmock.AnyArg(), nil).
		WillReturnError(&mysql.MySQLError{Number: 1062, Message: "Duplicate entry 'Family' for key 'family_groups.group_name'"})
	mock.ExpectQuery(`SELECT COUNT\(\*\) FROM family_groups WHERE group_name = \? LIMIT 1`).
		WithArgs("Family").
		WillReturnRows(sqlmock.NewRows([]string{"count"}).AddRow(1))
	mock.ExpectExec(`INSERT INTO family_groups`).
		WithArgs(sqlmock.AnyArg(), "Family", sqlmock.AnyArg(), nil).
		WillReturnResult(sqlmock.NewResult(2, 1))
	mock.ExpectRollback()
	tx, err := db.Begin()
	if err != nil {
		t.Fatal(err)
	}

	groupName, err := createFamilyGroupRecordTx(context.Background(), tx, "Family", nil, currentGroupCodeWriteMode)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(groupName, "Family#") || len(groupName) != len("Family#")+groupCodeLength {
		t.Fatalf("concurrent group internal name = %q", groupName)
	}
	if err := tx.Rollback(); err != nil {
		t.Fatal(err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestDuplicateKeyMatchingDoesNotConfuseLegacyAliasIndex(t *testing.T) {
	err := &mysql.MySQLError{Number: 1062, Message: "Duplicate entry 'legacy' for key 'family_groups.uniq_family_groups_legacy_group_code'"}
	if isDuplicateKeyFor(err, "group_code") {
		t.Fatal("legacy alias collision was mistaken for a current group-code collision")
	}
}

func TestHandlerDuplicateKeyMatchingIsPreciseAndSupportsWrappedErrors(t *testing.T) {
	wrapped := fmt.Errorf("insert group: %w", &mysql.MySQLError{Number: 1062, Message: "Duplicate entry 'abcdefgh' for key 'family_groups.group_code'"})
	if !isDuplicateKeyFor(wrapped, "group_code") {
		t.Fatal("wrapped current group-code collision was not recognized")
	}
	for _, err := range []error{
		&mysql.MySQLError{Number: 1062, Message: "Duplicate entry 'family' for key 'family_groups.group_name'"},
		&mysql.MySQLError{Number: 1062, Message: "Duplicate entry 'legacy' for key 'family_groups.uniq_family_groups_legacy_group_code'"},
		&mysql.MySQLError{Number: 1048, Message: "Column cannot be null"},
		errors.New("Duplicate entry for key group_code"),
	} {
		if isDuplicateKeyFor(err, "group_code") {
			t.Fatalf("unrelated error was treated as group-code collision: %v", err)
		}
	}
}

func TestAddInviteDefaultsToEightCharactersAndRetriesCollision(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	handler := AdminManageHandler{db: db}
	data := map[string]any{"code": "", "note": "", "invite_type": "invite", "allow_group_owner": false, "max_uses": 1, "assigned_group_name": "family"}
	expectInviteGroupExists(mock, "family")
	mock.ExpectExec(`INSERT INTO invite_codes`).
		WithArgs("00000000", "", "invite", false, 1, "family").
		WillReturnError(&mysql.MySQLError{Number: 1062, Message: "Duplicate entry '00000000' for key 'invite_codes.code'"})
	mock.ExpectExec(`INSERT INTO invite_codes`).
		WithArgs("11111111", "", "invite", false, 1, "family").
		WillReturnResult(sqlmock.NewResult(2, 1))
	message, err := handler.addInviteWithReader(context.Background(), data, bytes.NewReader(append(make([]byte, 8), bytes.Repeat([]byte{1}, 8)...)))
	if err != nil {
		t.Fatal(err)
	}
	if message != "邀请码已添加：11111111" {
		t.Fatalf("message = %q", message)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestAddInviteNormalizesCustomCodeAndReturnsConflictForDuplicate(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	handler := AdminManageHandler{db: db}
	data := map[string]any{"code": " AbC123 ", "note": "", "invite_type": "invite", "allow_group_owner": false, "max_uses": 1, "assigned_group_name": "family"}
	expectInviteGroupExists(mock, "family")
	mock.ExpectExec(`INSERT INTO invite_codes`).
		WithArgs("abc123", "", "invite", false, 1, "family").
		WillReturnError(&mysql.MySQLError{Number: 1062, Message: "Duplicate entry 'abc123' for key 'invite_codes.code'"})
	_, err = handler.addInviteWithReader(context.Background(), data, bytes.NewReader(nil))
	var apiErr httpx.APIError
	if !errors.As(err, &apiErr) || apiErr.Status != http.StatusConflict {
		t.Fatalf("duplicate custom invite error = %#v, want HTTP 409", err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestAddInviteRejectsInvalidCustomLengthAndPropagatesEntropyFailure(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	handler := AdminManageHandler{db: db}
	base := map[string]any{"note": "", "invite_type": "invite", "allow_group_owner": false, "max_uses": 1, "assigned_group_name": "family"}
	base["code"] = "abc"
	if _, err := handler.addInviteWithReader(context.Background(), base, bytes.NewReader(nil)); err == nil {
		t.Fatal("three-character custom invite code was accepted")
	}
	base["code"] = ""
	expectInviteGroupExists(mock, "family")
	if _, err := handler.addInviteWithReader(context.Background(), base, bytes.NewReader(nil)); err == nil || !strings.Contains(err.Error(), "entropy") {
		t.Fatalf("entropy failure = %v", err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestAddInviteAcceptsCustomBoundaryLengths(t *testing.T) {
	for _, length := range []int{4, 64} {
		t.Run(fmt.Sprint(length), func(t *testing.T) {
			db, mock, err := sqlmock.New()
			if err != nil {
				t.Fatal(err)
			}
			defer db.Close()
			code := strings.Repeat("a", length)
			expectInviteGroupExists(mock, "family")
			mock.ExpectExec(`INSERT INTO invite_codes`).
				WithArgs(code, "", "invite", false, 1, "family").
				WillReturnResult(sqlmock.NewResult(1, 1))
			handler := AdminManageHandler{db: db}
			_, err = handler.addInviteWithReader(context.Background(), map[string]any{
				"code": code, "note": "", "invite_type": "invite", "allow_group_owner": false, "max_uses": 1, "assigned_group_name": "family",
			}, bytes.NewReader(nil))
			if err != nil {
				t.Fatal(err)
			}
			if err := mock.ExpectationsWereMet(); err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestAddInviteRejectsOutOfRangeAndNonAlphaNumericCustomCodes(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	handler := AdminManageHandler{db: db}
	for _, code := range []string{strings.Repeat("a", 3), strings.Repeat("a", 65), "abcd-123"} {
		_, err := handler.addInviteWithReader(context.Background(), map[string]any{
			"code": code, "note": "", "invite_type": "invite", "allow_group_owner": false, "max_uses": 1,
		}, bytes.NewReader(nil))
		if err == nil {
			t.Fatalf("invalid custom invite code %q was accepted", code)
		}
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestAddInviteDoesNotRetryUnrelatedUniqueCollision(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	handler := AdminManageHandler{db: db}
	expectInviteGroupExists(mock, "family")
	mock.ExpectExec(`INSERT INTO invite_codes`).
		WithArgs("00000000", "", "invite", false, 1, "family").
		WillReturnError(&mysql.MySQLError{Number: 1062, Message: "Duplicate entry '1' for key 'invite_codes.PRIMARY'"})
	_, err = handler.addInviteWithReader(context.Background(), map[string]any{
		"code": "", "note": "", "invite_type": "invite", "allow_group_owner": false, "max_uses": 1, "assigned_group_name": "family",
	}, bytes.NewReader(make([]byte, 64)))
	if err == nil {
		t.Fatal("unrelated invite unique collision was retried or ignored")
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestRegistrationInviteValidationKeepsLegacyAlphaNumericCodesUsable(t *testing.T) {
	for _, code := range []string{"a", strings.Repeat("Z", 255)} {
		if !registerInviteCodePattern.MatchString(code) {
			t.Fatalf("legacy invite code of length %d was rejected", len(code))
		}
	}
	for _, code := range []string{"", strings.Repeat("a", 256), "abcd-123"} {
		if registerInviteCodePattern.MatchString(code) {
			t.Fatalf("invalid registration invite code %q was accepted", code)
		}
	}
}

func TestRegisterUsernamePolicyAllowsSixLetters(t *testing.T) {
	for _, username := range []string{"abcdef", "ABCDEF", "abc_12", strings.Repeat("a", 64)} {
		if !validRegisterUsername(username) {
			t.Fatalf("valid username %q was rejected", username)
		}
	}
	for _, username := range []string{"abcde", "abc-12", strings.Repeat("a", 65), "中文账号"} {
		if validRegisterUsername(username) {
			t.Fatalf("invalid username %q was accepted", username)
		}
	}
}

func TestAddInviteRequiresExistingAssignedGroup(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	handler := AdminManageHandler{db: db}
	base := map[string]any{"code": "abcdefgh", "note": "", "invite_type": "invite", "allow_group_owner": false, "max_uses": 1}
	if _, err := handler.addInviteWithReader(context.Background(), base, bytes.NewReader(nil)); err == nil {
		t.Fatal("invite without assigned group was accepted")
	}
	base["assigned_group_name"] = "missing"
	mock.ExpectQuery(`SELECT 1 FROM family_groups WHERE group_name = \? LIMIT 1`).
		WithArgs("missing").
		WillReturnRows(sqlmock.NewRows([]string{"exists"}))
	if _, err := handler.addInviteWithReader(context.Background(), base, bytes.NewReader(nil)); err == nil {
		t.Fatal("invite assigned to missing group was accepted")
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}
