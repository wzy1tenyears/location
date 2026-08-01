package handlers

import (
	"net/http"
	"net/http/httptest"
	"regexp"
	"testing"
	"time"

	"familylocation/location-v3/internal/repositories"
	"familylocation/location-v3/internal/session"

	"github.com/DATA-DOG/go-sqlmock"
)

func TestAdminHeartbeatRequiresAdminSession(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	reader := session.Reader{CookieName: "loc_session", Repo: repositories.NewSessionRepository(db)}
	handler := NewAdminHeartbeatHandler(reader)

	unauthorized := httptest.NewRecorder()
	handler.Touch(unauthorized, httptest.NewRequest(http.MethodPost, "/api/admin/heartbeat", nil))
	if unauthorized.Code != http.StatusUnauthorized {
		t.Fatalf("unauthorized status = %d, want %d", unauthorized.Code, http.StatusUnauthorized)
	}

	mock.ExpectQuery(regexp.QuoteMeta(`
SELECT session_id, user_id, admin_logged_in, expires_at
FROM app_sessions
WHERE session_id = ? AND expires_at > ?
LIMIT 1`)).
		WithArgs("0123456789abcdef0123456789abcdef", sqlmock.AnyArg()).
		WillReturnRows(sqlmock.NewRows([]string{"session_id", "user_id", "admin_logged_in", "expires_at"}).
			AddRow("0123456789abcdef0123456789abcdef", nil, true, time.Now().Add(time.Hour)))

	request := httptest.NewRequest(http.MethodPost, "/api/admin/heartbeat", nil)
	request.AddCookie(&http.Cookie{Name: "loc_session", Value: "0123456789abcdef0123456789abcdef"})
	response := httptest.NewRecorder()
	handler.Touch(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("authorized status = %d, body = %s", response.Code, response.Body.String())
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}
