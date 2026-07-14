package handlers

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/repositories"
	"familylocation/location-v3/internal/services"
	"familylocation/location-v3/internal/session"
)

const passwordSessionCookieName = "password_session_test"

func TestSelfServicePasswordChangeRotatesCurrentSessionAndRevokesAllOldSessions(t *testing.T) {
	const userID int64 = 41
	currentSessionID := strings.Repeat("a", 40)
	siblingSessionID := strings.Repeat("b", 40)
	otherUserSessionID := strings.Repeat("c", 40)
	expiresAt := time.Now().Add(10 * time.Minute)
	oldHash, err := services.HashPassword("old-password")
	if err != nil {
		t.Fatalf("HashPassword() error = %v", err)
	}
	state := newPasswordSessionState(map[int64]string{userID: oldHash})
	state.addUserSession(currentSessionID, userID, expiresAt)
	state.addUserSession(siblingSessionID, userID, expiresAt)
	state.addUserSession(otherUserSessionID, 99, expiresAt)

	handler, reader, closeDB := newPasswordSessionSettingsHandler(t, state)
	defer closeDB()
	recorder := httptest.NewRecorder()
	request := passwordChangeRequest(currentSessionID)
	err = handler.changePassword(recorder, request, userID, oldHash, settingsRequest{
		CurrentPassword:    "old-password",
		NewPassword:        "new-password",
		NewPasswordConfirm: "new-password",
	})
	if err != nil {
		t.Fatalf("changePassword() error = %v", err)
	}

	rotatedCookie := responseCookie(t, recorder, passwordSessionCookieName)
	rotatedSessionID := rotatedCookie.Value
	if rotatedSessionID == currentSessionID || rotatedSessionID == siblingSessionID {
		t.Fatalf("password change reused a pre-change session ID: %q", rotatedSessionID)
	}
	if !rotatedCookie.Expires.After(expiresAt.Add(40 * time.Minute)) {
		t.Fatalf("rotated session expiry = %v, want a fresh configured lifetime after old expiry %v", rotatedCookie.Expires, expiresAt)
	}
	assertSessionActive(t, reader.Repo, currentSessionID, false, 0)
	assertSessionActive(t, reader.Repo, siblingSessionID, false, 0)
	assertSessionActive(t, reader.Repo, rotatedSessionID, true, userID)
	assertSessionActive(t, reader.Repo, otherUserSessionID, true, 99)
	if !services.CheckPassword("new-password", state.passwordHash(userID)) {
		t.Fatal("password hash was not committed with session revocation")
	}
	if got := state.userSessionCount(userID); got != 1 {
		t.Fatalf("target user session count = %d, want only the rotated session", got)
	}
}

func TestSelfServicePasswordChangeRejectsWrongCurrentPasswordWithoutRevocation(t *testing.T) {
	const userID int64 = 46
	currentSessionID := strings.Repeat("8", 40)
	siblingSessionID := strings.Repeat("9", 40)
	expiresAt := time.Now().Add(time.Hour)
	oldHash, err := services.HashPassword("old-password")
	if err != nil {
		t.Fatalf("HashPassword() error = %v", err)
	}
	state := newPasswordSessionState(map[int64]string{userID: oldHash})
	state.addUserSession(currentSessionID, userID, expiresAt)
	state.addUserSession(siblingSessionID, userID, expiresAt)

	handler, reader, closeDB := newPasswordSessionSettingsHandler(t, state)
	defer closeDB()
	recorder := httptest.NewRecorder()
	err = handler.changePassword(recorder, passwordChangeRequest(currentSessionID), userID, oldHash, settingsRequest{
		CurrentPassword:    "wrong-password",
		NewPassword:        "new-password",
		NewPasswordConfirm: "new-password",
	})
	if err == nil {
		t.Fatal("changePassword() accepted the wrong current password")
	}

	assertSessionActive(t, reader.Repo, currentSessionID, true, userID)
	assertSessionActive(t, reader.Repo, siblingSessionID, true, userID)
	if !services.CheckPassword("old-password", state.passwordHash(userID)) {
		t.Fatal("wrong-current-password request changed the password hash")
	}
	if len(recorder.Result().Cookies()) != 0 {
		t.Fatal("wrong-current-password request unexpectedly changed the session cookie")
	}
}

func TestSelfServicePasswordChangeDoesNotOverwriteConcurrentReset(t *testing.T) {
	const userID int64 = 47
	currentSessionID := strings.Repeat("0", 40)
	expiresAt := time.Now().Add(time.Hour)
	oldHash, err := services.HashPassword("old-password")
	if err != nil {
		t.Fatalf("HashPassword() error = %v", err)
	}
	resetHash, err := services.HashPassword("administrator-reset-password")
	if err != nil {
		t.Fatalf("HashPassword() error = %v", err)
	}
	state := newPasswordSessionState(map[int64]string{userID: resetHash})
	state.addUserSession(currentSessionID, userID, expiresAt)

	handler, _, closeDB := newPasswordSessionSettingsHandler(t, state)
	defer closeDB()
	recorder := httptest.NewRecorder()
	err = handler.changePassword(recorder, passwordChangeRequest(currentSessionID), userID, oldHash, settingsRequest{
		CurrentPassword:    "old-password",
		NewPassword:        "stale-self-service-password",
		NewPasswordConfirm: "stale-self-service-password",
	})
	var apiErr httpx.APIError
	if !errors.As(err, &apiErr) || apiErr.Status != http.StatusUnauthorized {
		t.Fatalf("changePassword() error = %#v, want unauthorized", err)
	}
	if !services.CheckPassword("administrator-reset-password", state.passwordHash(userID)) {
		t.Fatal("stale self-service request overwrote the administrator reset password")
	}
	cleared := false
	for _, cookie := range recorder.Result().Cookies() {
		if cookie.Name == passwordSessionCookieName && cookie.MaxAge < 0 {
			cleared = true
		}
	}
	if !cleared {
		t.Fatal("stale self-service request did not clear the superseded session cookie")
	}
}

func TestSelfServicePasswordChangeRollsBackWhenRevocationFails(t *testing.T) {
	const userID int64 = 42
	currentSessionID := strings.Repeat("d", 40)
	siblingSessionID := strings.Repeat("e", 40)
	expiresAt := time.Now().Add(time.Hour)
	oldHash, err := services.HashPassword("old-password")
	if err != nil {
		t.Fatalf("HashPassword() error = %v", err)
	}
	state := newPasswordSessionState(map[int64]string{userID: oldHash})
	state.addUserSession(currentSessionID, userID, expiresAt)
	state.addUserSession(siblingSessionID, userID, expiresAt)
	state.failUserSessionDelete = true

	handler, reader, closeDB := newPasswordSessionSettingsHandler(t, state)
	defer closeDB()
	recorder := httptest.NewRecorder()
	err = handler.changePassword(recorder, passwordChangeRequest(currentSessionID), userID, oldHash, settingsRequest{
		CurrentPassword:    "old-password",
		NewPassword:        "new-password",
		NewPasswordConfirm: "new-password",
	})
	if err == nil {
		t.Fatal("changePassword() succeeded when session revocation failed")
	}

	assertSessionActive(t, reader.Repo, currentSessionID, true, userID)
	assertSessionActive(t, reader.Repo, siblingSessionID, true, userID)
	if !services.CheckPassword("old-password", state.passwordHash(userID)) {
		t.Fatal("password update was not rolled back with failed revocation")
	}
	commits, rollbacks := state.transactionCounts()
	if commits != 0 || rollbacks != 1 {
		t.Fatalf("transaction counts = commits:%d rollbacks:%d, want 0/1", commits, rollbacks)
	}
	if len(recorder.Result().Cookies()) != 0 {
		t.Fatal("failed credential transaction unexpectedly changed the session cookie")
	}
}

func TestSelfServicePasswordChangeFailsClosedWhenRotationFails(t *testing.T) {
	const userID int64 = 43
	currentSessionID := strings.Repeat("f", 40)
	siblingSessionID := strings.Repeat("1", 40)
	expiresAt := time.Now().Add(time.Hour)
	oldHash, err := services.HashPassword("old-password")
	if err != nil {
		t.Fatalf("HashPassword() error = %v", err)
	}
	state := newPasswordSessionState(map[int64]string{userID: oldHash})
	state.addUserSession(currentSessionID, userID, expiresAt)
	state.addUserSession(siblingSessionID, userID, expiresAt)
	state.failSessionUpsert = true

	handler, reader, closeDB := newPasswordSessionSettingsHandler(t, state)
	defer closeDB()
	recorder := httptest.NewRecorder()
	err = handler.changePassword(recorder, passwordChangeRequest(currentSessionID), userID, oldHash, settingsRequest{
		CurrentPassword:    "old-password",
		NewPassword:        "new-password",
		NewPasswordConfirm: "new-password",
	})
	if err == nil {
		t.Fatal("changePassword() succeeded when replacement session issuance failed")
	}

	assertSessionActive(t, reader.Repo, currentSessionID, false, 0)
	assertSessionActive(t, reader.Repo, siblingSessionID, false, 0)
	if !services.CheckPassword("new-password", state.passwordHash(userID)) {
		t.Fatal("committed password change was unexpectedly rolled back after rotation failure")
	}
	cleared := false
	for _, cookie := range recorder.Result().Cookies() {
		if cookie.Name == passwordSessionCookieName && cookie.MaxAge < 0 {
			cleared = true
		}
	}
	if !cleared {
		t.Fatal("rotation failure did not clear the now-revoked client cookie")
	}
}

func TestGroupOwnerPasswordResetRevokesAllMemberSessions(t *testing.T) {
	const userID int64 = 44
	firstSessionID := strings.Repeat("2", 40)
	secondSessionID := strings.Repeat("3", 40)
	otherUserSessionID := strings.Repeat("4", 40)
	expiresAt := time.Now().Add(time.Hour)
	state := newPasswordSessionState(nil)
	state.addUserSession(firstSessionID, userID, expiresAt)
	state.addUserSession(secondSessionID, userID, expiresAt)
	state.addUserSession(otherUserSessionID, 100, expiresAt)

	db, reader, closeDB := openPasswordSessionDB(t, state)
	defer closeDB()
	handler := NewGroupsHandler(db, reader)
	request := httptest.NewRequest(http.MethodPost, "/api/groups", nil)
	if err := handler.updateMemberPasswordAndRevokeSessions(request, userID, "owner-reset-password"); err != nil {
		t.Fatalf("updateMemberPasswordAndRevokeSessions() error = %v", err)
	}

	assertSessionActive(t, reader.Repo, firstSessionID, false, 0)
	assertSessionActive(t, reader.Repo, secondSessionID, false, 0)
	assertSessionActive(t, reader.Repo, otherUserSessionID, true, 100)
	if !services.CheckPassword("owner-reset-password", state.passwordHash(userID)) {
		t.Fatal("group-owner reset did not update the target password")
	}
}

func TestAdministratorPasswordResetRevokesAllTargetSessions(t *testing.T) {
	const userID int64 = 45
	firstSessionID := strings.Repeat("5", 40)
	secondSessionID := strings.Repeat("6", 40)
	otherUserSessionID := strings.Repeat("7", 40)
	expiresAt := time.Now().Add(time.Hour)
	state := newPasswordSessionState(nil)
	state.addUserSession(firstSessionID, userID, expiresAt)
	state.addUserSession(secondSessionID, userID, expiresAt)
	state.addUserSession(otherUserSessionID, 101, expiresAt)

	db, reader, closeDB := openPasswordSessionDB(t, state)
	defer closeDB()
	handler := NewAdminManageHandler(db, reader)
	request := httptest.NewRequest(http.MethodPost, "/api/admin/manage", nil)
	message, err := handler.resetPassword(request, map[string]any{
		"user_id":      userID,
		"new_password": "administrator-reset-password",
	})
	if err != nil {
		t.Fatalf("resetPassword() error = %v", err)
	}
	if message != "密码已重置。" {
		t.Fatalf("resetPassword() message = %q", message)
	}

	assertSessionActive(t, reader.Repo, firstSessionID, false, 0)
	assertSessionActive(t, reader.Repo, secondSessionID, false, 0)
	assertSessionActive(t, reader.Repo, otherUserSessionID, true, 101)
	if !services.CheckPassword("administrator-reset-password", state.passwordHash(userID)) {
		t.Fatal("administrator reset did not update the target password")
	}
}

func newPasswordSessionSettingsHandler(t *testing.T, state *passwordSessionState) (SettingsHandler, session.Reader, func()) {
	t.Helper()
	db, reader, closeDB := openPasswordSessionDB(t, state)
	return NewSettingsHandler(db, reader, time.Hour), reader, closeDB
}

func openPasswordSessionDB(t *testing.T, state *passwordSessionState) (*sql.DB, session.Reader, func()) {
	t.Helper()
	driverName := fmt.Sprintf("password-session-test-%d", atomic.AddUint64(&passwordSessionDriverID, 1))
	sql.Register(driverName, passwordSessionDriver{state: state})
	db, err := sql.Open(driverName, "")
	if err != nil {
		t.Fatalf("sql.Open() error = %v", err)
	}
	db.SetMaxOpenConns(1)
	repo := repositories.NewSessionRepository(db)
	reader := session.Reader{CookieName: passwordSessionCookieName, Repo: repo}
	return db, reader, func() { _ = db.Close() }
}

func passwordChangeRequest(sessionID string) *http.Request {
	request := httptest.NewRequest(http.MethodPost, "/api/settings", nil)
	request.AddCookie(&http.Cookie{Name: passwordSessionCookieName, Value: sessionID})
	return request
}

func responseCookie(t *testing.T, recorder *httptest.ResponseRecorder, cookieName string) *http.Cookie {
	t.Helper()
	for _, cookie := range recorder.Result().Cookies() {
		if cookie.Name == cookieName && cookie.Value != "" && cookie.MaxAge >= 0 {
			return cookie
		}
	}
	t.Fatalf("response did not set a live %q cookie", cookieName)
	return nil
}

func assertSessionActive(t *testing.T, repo *repositories.SessionRepository, sessionID string, wantActive bool, wantUserID int64) {
	t.Helper()
	record, err := repo.FindActive(context.Background(), sessionID, time.Now())
	if err != nil {
		t.Fatalf("FindActive(%q) error = %v", sessionID, err)
	}
	if (record != nil) != wantActive {
		t.Fatalf("FindActive(%q) active = %v, want %v", sessionID, record != nil, wantActive)
	}
	if wantActive && (!record.UserID.Valid || record.UserID.Int64 != wantUserID) {
		t.Fatalf("FindActive(%q) user = %#v, want %d", sessionID, record.UserID, wantUserID)
	}
}

var passwordSessionDriverID uint64

type passwordSessionRecord struct {
	userID        sql.NullInt64
	adminLoggedIn bool
	expiresAt     time.Time
}

type passwordSessionData struct {
	passwordHashes map[int64]string
	sessions       map[string]passwordSessionRecord
}

func (data passwordSessionData) clone() passwordSessionData {
	cloned := passwordSessionData{
		passwordHashes: make(map[int64]string, len(data.passwordHashes)),
		sessions:       make(map[string]passwordSessionRecord, len(data.sessions)),
	}
	for userID, hash := range data.passwordHashes {
		cloned.passwordHashes[userID] = hash
	}
	for sessionID, record := range data.sessions {
		cloned.sessions[sessionID] = record
	}
	return cloned
}

type passwordSessionState struct {
	mu                    sync.Mutex
	data                  passwordSessionData
	failUserSessionDelete bool
	failSessionUpsert     bool
	commits               int
	rollbacks             int
}

func newPasswordSessionState(passwordHashes map[int64]string) *passwordSessionState {
	state := &passwordSessionState{data: passwordSessionData{
		passwordHashes: make(map[int64]string),
		sessions:       make(map[string]passwordSessionRecord),
	}}
	for userID, hash := range passwordHashes {
		state.data.passwordHashes[userID] = hash
	}
	return state
}

func (state *passwordSessionState) addUserSession(sessionID string, userID int64, expiresAt time.Time) {
	state.mu.Lock()
	defer state.mu.Unlock()
	state.data.sessions[sessionID] = passwordSessionRecord{
		userID:    sql.NullInt64{Int64: userID, Valid: true},
		expiresAt: expiresAt,
	}
}

func (state *passwordSessionState) passwordHash(userID int64) string {
	state.mu.Lock()
	defer state.mu.Unlock()
	return state.data.passwordHashes[userID]
}

func (state *passwordSessionState) userSessionCount(userID int64) int {
	state.mu.Lock()
	defer state.mu.Unlock()
	count := 0
	for _, record := range state.data.sessions {
		if record.userID.Valid && record.userID.Int64 == userID {
			count++
		}
	}
	return count
}

func (state *passwordSessionState) transactionCounts() (int, int) {
	state.mu.Lock()
	defer state.mu.Unlock()
	return state.commits, state.rollbacks
}

type passwordSessionDriver struct {
	state *passwordSessionState
}

func (driverInstance passwordSessionDriver) Open(string) (driver.Conn, error) {
	return &passwordSessionConn{state: driverInstance.state}, nil
}

type passwordSessionConn struct {
	state   *passwordSessionState
	pending *passwordSessionData
}

func (connection *passwordSessionConn) Prepare(string) (driver.Stmt, error) {
	return nil, errors.New("prepared statements are not supported by the password-session test driver")
}

func (connection *passwordSessionConn) Close() error { return nil }

func (connection *passwordSessionConn) Begin() (driver.Tx, error) {
	return connection.BeginTx(context.Background(), driver.TxOptions{})
}

func (connection *passwordSessionConn) BeginTx(context.Context, driver.TxOptions) (driver.Tx, error) {
	connection.state.mu.Lock()
	defer connection.state.mu.Unlock()
	if connection.pending != nil {
		return nil, errors.New("transaction already active")
	}
	pending := connection.state.data.clone()
	connection.pending = &pending
	return &passwordSessionTx{connection: connection}, nil
}

func (connection *passwordSessionConn) ExecContext(_ context.Context, query string, args []driver.NamedValue) (driver.Result, error) {
	connection.state.mu.Lock()
	defer connection.state.mu.Unlock()
	data := &connection.state.data
	if connection.pending != nil {
		data = connection.pending
	}

	switch {
	case strings.Contains(query, "UPDATE users SET password_hash = ?"):
		data.passwordHashes[int64Argument(args[1])] = stringArgument(args[0])
	case strings.Contains(query, "DELETE FROM app_sessions WHERE user_id = ?"):
		if connection.state.failUserSessionDelete {
			return nil, errors.New("forced user-session revocation failure")
		}
		userID := int64Argument(args[0])
		for sessionID, record := range data.sessions {
			if record.userID.Valid && record.userID.Int64 == userID {
				delete(data.sessions, sessionID)
			}
		}
	case strings.Contains(query, "DELETE FROM app_sessions WHERE session_id = ?"):
		delete(data.sessions, stringArgument(args[0]))
	case strings.Contains(query, "DELETE FROM app_sessions WHERE expires_at <= ?"):
		cutoff := args[0].Value.(time.Time)
		for sessionID, record := range data.sessions {
			if !record.expiresAt.After(cutoff) {
				delete(data.sessions, sessionID)
			}
		}
	case strings.Contains(query, "INSERT INTO app_sessions"):
		if connection.state.failSessionUpsert {
			return nil, errors.New("forced replacement-session issuance failure")
		}
		record := passwordSessionRecord{}
		if len(args) == 3 {
			record.userID = sql.NullInt64{Int64: int64Argument(args[1]), Valid: true}
			record.expiresAt = args[2].Value.(time.Time)
		} else {
			record.adminLoggedIn = boolArgument(args[2])
			record.expiresAt = args[3].Value.(time.Time)
			if args[1].Value != nil {
				record.userID = sql.NullInt64{Int64: int64Argument(args[1]), Valid: true}
			}
		}
		data.sessions[stringArgument(args[0])] = record
	}
	return passwordSessionResult(1), nil
}

func (connection *passwordSessionConn) QueryContext(_ context.Context, query string, args []driver.NamedValue) (driver.Rows, error) {
	connection.state.mu.Lock()
	defer connection.state.mu.Unlock()
	data := &connection.state.data
	if connection.pending != nil {
		data = connection.pending
	}
	if strings.Contains(query, "FROM app_sessions") && strings.Contains(query, "WHERE session_id = ?") {
		sessionID := stringArgument(args[0])
		now := args[1].Value.(time.Time)
		record, ok := data.sessions[sessionID]
		if !ok || !record.expiresAt.After(now) {
			return &passwordSessionRows{columns: []string{"session_id", "user_id", "admin_logged_in", "expires_at"}}, nil
		}
		var userID driver.Value
		if record.userID.Valid {
			userID = record.userID.Int64
		}
		return &passwordSessionRows{
			columns: []string{"session_id", "user_id", "admin_logged_in", "expires_at"},
			values:  [][]driver.Value{{sessionID, userID, record.adminLoggedIn, record.expiresAt}},
		}, nil
	}
	if strings.Contains(query, "SELECT password_hash") && strings.Contains(query, "FROM users") {
		passwordHash, ok := data.passwordHashes[int64Argument(args[0])]
		if !ok {
			return &passwordSessionRows{columns: []string{"password_hash"}}, nil
		}
		return &passwordSessionRows{
			columns: []string{"password_hash"},
			values:  [][]driver.Value{{passwordHash}},
		}, nil
	}
	return &passwordSessionRows{columns: []string{"value"}}, nil
}

type passwordSessionTx struct {
	connection *passwordSessionConn
}

func (tx *passwordSessionTx) Commit() error {
	tx.connection.state.mu.Lock()
	defer tx.connection.state.mu.Unlock()
	if tx.connection.pending == nil {
		return errors.New("no active transaction")
	}
	tx.connection.state.data = tx.connection.pending.clone()
	tx.connection.pending = nil
	tx.connection.state.commits++
	return nil
}

func (tx *passwordSessionTx) Rollback() error {
	tx.connection.state.mu.Lock()
	defer tx.connection.state.mu.Unlock()
	if tx.connection.pending == nil {
		return nil
	}
	tx.connection.pending = nil
	tx.connection.state.rollbacks++
	return nil
}

type passwordSessionResult int64

func (result passwordSessionResult) LastInsertId() (int64, error) { return int64(result), nil }
func (result passwordSessionResult) RowsAffected() (int64, error) { return int64(result), nil }

type passwordSessionRows struct {
	columns []string
	values  [][]driver.Value
	index   int
}

func (rows *passwordSessionRows) Columns() []string { return rows.columns }
func (rows *passwordSessionRows) Close() error      { return nil }

func (rows *passwordSessionRows) Next(destination []driver.Value) error {
	if rows.index >= len(rows.values) {
		return io.EOF
	}
	copy(destination, rows.values[rows.index])
	rows.index++
	return nil
}

func int64Argument(arg driver.NamedValue) int64 {
	switch value := arg.Value.(type) {
	case int64:
		return value
	case int:
		return int64(value)
	default:
		panic(fmt.Sprintf("unexpected int64 argument type %T", arg.Value))
	}
}

func stringArgument(arg driver.NamedValue) string {
	value, ok := arg.Value.(string)
	if !ok {
		panic(fmt.Sprintf("unexpected string argument type %T", arg.Value))
	}
	return value
}

func boolArgument(arg driver.NamedValue) bool {
	value, ok := arg.Value.(bool)
	if !ok {
		panic(fmt.Sprintf("unexpected bool argument type %T", arg.Value))
	}
	return value
}
