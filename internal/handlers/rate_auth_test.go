package handlers

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"familylocation/location-v3/internal/repositories"

	"github.com/DATA-DOG/go-sqlmock"
)

func TestConcurrentUserLoginAttemptsReserveBeforePasswordCheck(t *testing.T) {
	db, mock, err := sqlmock.New(sqlmock.MonitorPingsOption(false))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	mock.MatchExpectationsInOrder(false)

	for attempt := 0; attempt < 5; attempt++ {
		mock.ExpectQuery(`(?s)SELECT.*FROM users.*WHERE username =`).
			WithArgs("victim1").
			WillReturnRows(testLoginUserRow())
	}

	rates := newFakeRateLimiter()
	var passwordChecks atomic.Int32
	handler := LoginHandler{
		users: repositories.NewUserRepository(db),
		rates: rates,
		checkUserPassword: func(string, string) bool {
			passwordChecks.Add(1)
			time.Sleep(10 * time.Millisecond)
			return false
		},
	}

	const attempts = 20
	statuses := make(chan int, attempts)
	var workers sync.WaitGroup
	for attempt := 0; attempt < attempts; attempt++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			req := httptest.NewRequest(http.MethodPost, "/api/login", nil)
			req.RemoteAddr = "203.0.113.30:41234"
			recorder := httptest.NewRecorder()
			handler.loginUser(recorder, req, loginRequest{Username: "victim1", Password: "wrong-password"})
			statuses <- recorder.Code
		}()
	}
	workers.Wait()
	close(statuses)

	if got := passwordChecks.Load(); got != 5 {
		t.Fatalf("password checks = %d, want 5", got)
	}
	unauthorized := 0
	limited := 0
	for status := range statuses {
		switch status {
		case http.StatusUnauthorized:
			unauthorized++
		case http.StatusTooManyRequests:
			limited++
		default:
			t.Fatalf("unexpected login status %d", status)
		}
	}
	if unauthorized != 5 || limited != attempts-5 {
		t.Fatalf("login statuses = %d unauthorized, %d limited", unauthorized, limited)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestConcurrentAdminLoginAttemptsReserveBeforePasswordCheck(t *testing.T) {
	limits := &fakeAdminAttemptLimiter{}
	var passwordChecks atomic.Int32
	handler := LoginHandler{
		limits: limits,
		checkAdminPassword: func(string) bool {
			passwordChecks.Add(1)
			time.Sleep(10 * time.Millisecond)
			return false
		},
	}

	const attempts = 20
	statuses := make(chan int, attempts)
	var workers sync.WaitGroup
	for attempt := 0; attempt < attempts; attempt++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			req := httptest.NewRequest(http.MethodPost, "/api/login", nil)
			req.RemoteAddr = "203.0.113.31:41235"
			recorder := httptest.NewRecorder()
			handler.loginAdmin(recorder, req, loginRequest{Password: "wrong-password"})
			statuses <- recorder.Code
		}()
	}
	workers.Wait()
	close(statuses)

	if got := passwordChecks.Load(); got != 5 {
		t.Fatalf("admin password checks = %d, want 5", got)
	}
	unauthorized := 0
	locked := 0
	for status := range statuses {
		switch status {
		case http.StatusUnauthorized:
			unauthorized++
		case 423:
			locked++
		default:
			t.Fatalf("unexpected admin login status %d", status)
		}
	}
	if unauthorized != 4 || locked != attempts-4 {
		t.Fatalf("admin statuses = %d unauthorized, %d locked", unauthorized, locked)
	}
}

func TestUserLoginAttemptBudgetIsSourceAndUsernameScoped(t *testing.T) {
	first := userLoginAttemptIdentity("203.0.113.40", " Victim1 ")
	if first != userLoginAttemptIdentity("203.0.113.40", "victim1") {
		t.Fatal("username case or whitespace split one account's attempt budget")
	}
	if first == userLoginAttemptIdentity("203.0.113.40", "victim2") {
		t.Fatal("different accounts behind one source share a password-attempt budget")
	}
	if first == userLoginAttemptIdentity("203.0.113.41", "victim1") {
		t.Fatal("different sources share a password-attempt budget")
	}
}

func TestInviteCheckReturnsSameGenericResponse(t *testing.T) {
	handler := InviteHandler{rates: newFakeRateLimiter()}
	requests := []*http.Request{
		httptest.NewRequest(http.MethodGet, "/api/invite-check?code=ABC123", nil),
		httptest.NewRequest(http.MethodGet, "/api/invite-check?code=invalid!", nil),
		httptest.NewRequest(http.MethodPost, "/api/invite-check", strings.NewReader("not-json")),
	}

	var baseline string
	for index, req := range requests {
		req.RemoteAddr = "203.0.113." + string(rune('4'+index)) + ":41236"
		recorder := httptest.NewRecorder()
		handler.Check(recorder, req)
		if recorder.Code != http.StatusOK {
			t.Fatalf("request %d status = %d; body=%s", index, recorder.Code, recorder.Body.String())
		}
		if index == 0 {
			baseline = recorder.Body.String()
		} else if recorder.Body.String() != baseline {
			t.Fatalf("invite preflight response differs: %q vs %q", baseline, recorder.Body.String())
		}
	}
}

func TestInviteCheckAppliesSharedQuota(t *testing.T) {
	handler := InviteHandler{rates: newFakeRateLimiter()}
	for attempt := 1; attempt <= 21; attempt++ {
		req := httptest.NewRequest(http.MethodGet, "/api/invite-check?code=ABC123", nil)
		req.RemoteAddr = "203.0.113.50:41237"
		recorder := httptest.NewRecorder()
		handler.Check(recorder, req)
		want := http.StatusOK
		if attempt == 21 {
			want = http.StatusTooManyRequests
		}
		if recorder.Code != want {
			t.Fatalf("attempt %d status = %d, want %d", attempt, recorder.Code, want)
		}
	}
}

func TestRandomGroupCodeUsesEightUnbiasedLowerAlphaNumericCharacters(t *testing.T) {
	code, err := randomGroupCode(bytes.NewReader([]byte{
		0xfc, 0xfd, 0xfe, 0xff,
		0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x23,
	}))
	if err != nil {
		t.Fatal(err)
	}
	if code != "0123456z" {
		t.Fatalf("randomGroupCode() = %q", code)
	}
	if len(code) != groupCodeLength || !groupCodePattern.MatchString(code) {
		t.Fatalf("generated code %q is not an 8-character group code", code)
	}
}

func TestRandomGroupCodePropagatesEntropySourceFailure(t *testing.T) {
	if _, err := randomGroupCode(bytes.NewReader(make([]byte, groupCodeLength-1))); err == nil {
		t.Fatal("short entropy source was silently accepted")
	}
}

func TestStagedGroupCodeWriteModeKeepsRollbackCompatibility(t *testing.T) {
	legacy, err := randomGroupCodeForMode(bytes.NewReader(make([]byte, legacyGroupCodeByteLength)), groupCodeModeForBackfill(false))
	if err != nil {
		t.Fatal(err)
	}
	if legacy != strings.Repeat("0", 32) || len(legacy) != 32 || !groupCodePattern.MatchString(legacy) {
		t.Fatalf("stage-one group code = %q, want 32-character lowercase hex", legacy)
	}

	current, err := randomGroupCodeForMode(bytes.NewReader(make([]byte, groupCodeLength)), groupCodeModeForBackfill(true))
	if err != nil {
		t.Fatal(err)
	}
	if current != "00000000" || len(current) != groupCodeLength || !groupCodePattern.MatchString(current) {
		t.Fatalf("stage-two group code = %q, want 8-character lowercase alphanumeric", current)
	}
	if groupCodeModeForBackfill(false) != legacyGroupCodeWriteMode || groupCodeModeForBackfill(true) != currentGroupCodeWriteMode {
		t.Fatal("group-code write mode does not follow the staged backfill switch")
	}
}

func TestGroupCodeValidationAcceptsCurrentAndLegacyAliases(t *testing.T) {
	for _, code := range []string{"0a1b2c3d", "000102030405060708090a0b0c0d0e0f"} {
		if !groupCodePattern.MatchString(code) {
			t.Fatalf("compatible group code %q was rejected", code)
		}
	}
	for _, code := range []string{"ABC12345", "abc123", "000102030405060708090A0B0C0D0E0F"} {
		if groupCodePattern.MatchString(code) {
			t.Fatalf("invalid non-normalized group code %q was accepted", code)
		}
	}
}

func TestFamilyGroupInternalNameFitsLong128BitCode(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	displayName := strings.Repeat("组", 93)
	code := strings.Repeat("a", 32)
	mock.ExpectBegin()
	mock.ExpectQuery(`SELECT COUNT\(\*\) FROM family_groups WHERE group_name = \? LIMIT 1`).
		WithArgs(displayName).
		WillReturnRows(sqlmock.NewRows([]string{"count"}).AddRow(1))
	mock.ExpectRollback()
	tx, err := db.Begin()
	if err != nil {
		t.Fatal(err)
	}

	name, err := familyGroupInternalNameTx(context.Background(), tx, displayName, code)
	if err != nil {
		t.Fatal(err)
	}
	if len([]rune(name)) != 100 || !strings.HasSuffix(name, "#"+code) {
		t.Fatalf("internal group name does not fit VARCHAR(100): len=%d value=%q", len([]rune(name)), name)
	}
	if err := tx.Rollback(); err != nil {
		t.Fatal(err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func testLoginUserRow() *sqlmock.Rows {
	return sqlmock.NewRows([]string{
		"id", "username", "display_name", "password_hash", "group_name", "role", "is_active", "disabled_reason",
		"failed_login_count", "login_locked_at", "debug_mode", "report_interval_seconds", "terms_accepted_at",
		"user_agreement_accepted_at", "privacy_policy_accepted_at", "cross_border_transfer_accepted_at",
		"environment_data_consent_at", "created_at", "updated_at",
	}).AddRow(
		1, "victim1", "Victim", "hash", "family", "member", true, "",
		3, time.Now(), false, 60, nil, nil, nil, nil, nil, time.Now(), time.Now(),
	)
}

type fakeRateLimiter struct {
	mu      sync.Mutex
	now     time.Time
	windows map[string]fakeRateWindow
}

func newFakeRateLimiter() *fakeRateLimiter {
	return &fakeRateLimiter{now: time.Now(), windows: make(map[string]fakeRateWindow)}
}

type fakeRateWindow struct {
	startedAt time.Time
	hitCount  int
}

func (limiter *fakeRateLimiter) Hit(_ context.Context, bucket string, identity string, maxHits int, window time.Duration) (bool, error) {
	limiter.mu.Lock()
	defer limiter.mu.Unlock()
	key := bucket + "\x00" + identity
	state, ok := limiter.windows[key]
	if !ok || state.startedAt.Before(limiter.now.Add(-window)) {
		state = fakeRateWindow{startedAt: limiter.now}
	}
	state.hitCount++
	limiter.windows[key] = state
	return state.hitCount <= maxHits, nil
}

func (limiter *fakeRateLimiter) Clear(_ context.Context, bucket string, identity string) error {
	limiter.mu.Lock()
	defer limiter.mu.Unlock()
	delete(limiter.windows, bucket+"\x00"+identity)
	return nil
}

func (limiter *fakeRateLimiter) advance(duration time.Duration) {
	limiter.mu.Lock()
	defer limiter.mu.Unlock()
	limiter.now = limiter.now.Add(duration)
}

type fakeAdminAttemptLimiter struct {
	mu     sync.Mutex
	count  int
	locked bool
}

func (limiter *fakeAdminAttemptLimiter) ReserveAdminLoginAttempt(_ context.Context, _ string, limit int, _ time.Duration) (bool, bool, error) {
	limiter.mu.Lock()
	defer limiter.mu.Unlock()
	if limiter.locked {
		return false, true, nil
	}
	limiter.count++
	exhausted := limiter.count >= limit
	if exhausted {
		limiter.locked = true
	}
	return true, exhausted, nil
}

func (limiter *fakeAdminAttemptLimiter) ClearFailedAdminLogin(_ context.Context, _ string) error {
	limiter.mu.Lock()
	defer limiter.mu.Unlock()
	limiter.count = 0
	limiter.locked = false
	return nil
}
