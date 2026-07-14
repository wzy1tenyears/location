package session

import (
	"errors"
	"net/http/httptest"
	"regexp"
	"testing"
	"time"

	"familylocation/location-v3/internal/repositories"

	"github.com/DATA-DOG/go-sqlmock"
)

func TestStartUserSessionRechecksPasswordHashBeforeSettingCookie(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	const userID int64 = 81
	const passwordHash = "verified-password-hash"
	mock.ExpectBegin()
	mock.ExpectQuery(`(?s)SELECT password_hash.*FROM users.*FOR UPDATE`).
		WithArgs(userID).
		WillReturnRows(sqlmock.NewRows([]string{"password_hash"}).AddRow(passwordHash))
	mock.ExpectExec(regexp.QuoteMeta("INSERT INTO app_sessions (session_id, user_id, admin_logged_in, expires_at)")).
		WithArgs(sqlmock.AnyArg(), userID, sqlmock.AnyArg()).
		WillReturnResult(sqlmock.NewResult(0, 1))
	mock.ExpectCommit()

	store := Store{CookieName: "session_test", Repo: repositories.NewSessionRepository(db), Lifetime: time.Hour}
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest("POST", "/api/login", nil)
	sessionID, err := store.StartUserSession(recorder, request, userID, passwordHash)
	if err != nil {
		t.Fatalf("StartUserSession() error = %v", err)
	}
	if sessionID == "" {
		t.Fatal("StartUserSession() returned an empty session ID")
	}
	cookies := recorder.Result().Cookies()
	if len(cookies) != 1 || cookies[0].Name != "session_test" || cookies[0].Value != sessionID {
		t.Fatalf("response cookies = %#v, want the committed replacement session", cookies)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestStartUserSessionRejectsChangedPasswordBeforeSettingCookie(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	const userID int64 = 82
	mock.ExpectBegin()
	mock.ExpectQuery(`(?s)SELECT password_hash.*FROM users.*FOR UPDATE`).
		WithArgs(userID).
		WillReturnRows(sqlmock.NewRows([]string{"password_hash"}).AddRow("new-password-hash"))
	mock.ExpectRollback()

	store := Store{CookieName: "session_test", Repo: repositories.NewSessionRepository(db), Lifetime: time.Hour}
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest("POST", "/api/login", nil)
	_, err = store.StartUserSession(recorder, request, userID, "old-password-hash")
	if !errors.Is(err, ErrUserCredentialChanged) {
		t.Fatalf("StartUserSession() error = %v, want ErrUserCredentialChanged", err)
	}
	if len(recorder.Result().Cookies()) != 0 {
		t.Fatal("credential mismatch set a session cookie")
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestStartAdminSessionKeepsIndependentAdminSessionPath(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	mock.ExpectExec(regexp.QuoteMeta("INSERT INTO app_sessions (session_id, user_id, admin_logged_in, expires_at)")).
		WithArgs(sqlmock.AnyArg(), sqlmock.AnyArg()).
		WillReturnResult(sqlmock.NewResult(0, 1))

	store := Store{CookieName: "admin_session_test", Repo: repositories.NewSessionRepository(db), Lifetime: time.Hour}
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest("POST", "/api/login", nil)
	sessionID, err := store.StartAdminSession(recorder, request)
	if err != nil {
		t.Fatalf("StartAdminSession() error = %v", err)
	}
	if sessionID == "" {
		t.Fatal("StartAdminSession() returned an empty session ID")
	}
	cookies := recorder.Result().Cookies()
	if len(cookies) != 1 || cookies[0].Name != "admin_session_test" || cookies[0].Value != sessionID {
		t.Fatalf("response cookies = %#v, want the admin session", cookies)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}
