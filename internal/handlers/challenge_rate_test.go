package handlers

import (
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"familylocation/location-v3/internal/config"
	"familylocation/location-v3/internal/repositories"

	"github.com/DATA-DOG/go-sqlmock"
)

func TestConcurrentChallengeVerificationAdmitsOneProviderCall(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	mock.MatchExpectationsInOrder(false)

	const attempts = 20
	challengeID := "0123456789abcdef0123456789abcdef"
	deviceFingerprint := "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	for attempt := 0; attempt < attempts; attempt++ {
		expectActiveChallenge(mock, challengeID, deviceFingerprint)
	}
	mock.ExpectExec(`UPDATE app_challenges SET verified_at = NOW\(\)`).
		WithArgs(challengeID, deviceFingerprint).
		WillReturnResult(sqlmock.NewResult(0, 1))

	rates := newFakeRateLimiter()
	var providerCalls atomic.Int32
	handler := ChallengeHandler{
		cfg:   config.Config{App: config.AppConfig{DeviceCookieName: "loc_device"}, External: config.ExternalConfig{TurnstileSecretKey: "secret"}},
		rates: rates,
		store: repositories.NewAppChallengeRepository(db),
		turnstileVerifier: func(string, string, string) (bool, error) {
			providerCalls.Add(1)
			time.Sleep(20 * time.Millisecond)
			return true, nil
		},
	}

	var workers sync.WaitGroup
	for attempt := 0; attempt < attempts; attempt++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			req := httptest.NewRequest(http.MethodPost, "/api/app-challenge", nil)
			req.AddCookie(&http.Cookie{Name: "loc_device", Value: deviceFingerprint})
			_ = handler.verifyBrowserChallenge(req, challengeID, "turnstile-token")
		}()
	}
	workers.Wait()

	if got := providerCalls.Load(); got != 1 {
		t.Fatalf("Turnstile provider calls = %d, want 1", got)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestChallengeVerificationLeaseAllowsLaterRetry(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	challengeID := "fedcba9876543210fedcba9876543210"
	deviceFingerprint := "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
	for attempt := 0; attempt < 3; attempt++ {
		expectActiveChallenge(mock, challengeID, deviceFingerprint)
	}
	mock.ExpectExec(`UPDATE app_challenges SET verified_at = NOW\(\)`).
		WithArgs(challengeID, deviceFingerprint).
		WillReturnResult(sqlmock.NewResult(0, 1))

	rates := newFakeRateLimiter()
	var providerCalls atomic.Int32
	handler := ChallengeHandler{
		cfg:   config.Config{App: config.AppConfig{DeviceCookieName: "loc_device"}, External: config.ExternalConfig{TurnstileSecretKey: "secret"}},
		rates: rates,
		store: repositories.NewAppChallengeRepository(db),
		turnstileVerifier: func(string, string, string) (bool, error) {
			return providerCalls.Add(1) > 1, nil
		},
	}
	request := func() string {
		req := httptest.NewRequest(http.MethodPost, "/api/app-challenge", nil)
		req.AddCookie(&http.Cookie{Name: "loc_device", Value: deviceFingerprint})
		return handler.verifyBrowserChallenge(req, challengeID, "turnstile-token")
	}

	if message := request(); message != "Cloudflare 验证失败，请重试。" {
		t.Fatalf("first verification message = %q", message)
	}
	if message := request(); message != "验证正在处理中，请稍后重试。" {
		t.Fatalf("leased verification message = %q", message)
	}
	rates.advance(appChallengeVerificationLease + time.Second)
	if message := request(); message != "验证已完成，请回到 App 继续登录。" {
		t.Fatalf("retry verification message = %q", message)
	}
	if got := providerCalls.Load(); got != 2 {
		t.Fatalf("Turnstile provider calls = %d, want 2", got)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func expectActiveChallenge(mock sqlmock.Sqlmock, challengeID string, deviceFingerprint string) {
	mock.ExpectQuery(`(?s)SELECT id, secret_hash, device_fingerprint, purpose, verified_at, consumed_at, expires_at.*FROM app_challenges`).
		WithArgs(challengeID, deviceFingerprint).
		WillReturnRows(sqlmock.NewRows([]string{
			"id", "secret_hash", "device_fingerprint", "purpose", "verified_at", "consumed_at", "expires_at",
		}).AddRow(challengeID, "hash", deviceFingerprint, "login", nil, nil, time.Now().Add(time.Minute)))
}
