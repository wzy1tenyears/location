package handlers

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"familylocation/location-v3/internal/config"
)

type appChallengeSettingStub struct {
	required bool
	err      error
}

func (stub appChallengeSettingStub) AppChallengeRequired(context.Context) (bool, error) {
	return stub.required, stub.err
}

func TestAppChallengeDisabledSkipsLoginAndRegistrationVerification(t *testing.T) {
	request := httptest.NewRequest(http.MethodPost, "/api/login", nil)
	settings := appChallengeSettingStub{required: false}
	login := LoginHandler{
		cfg:      config.Config{External: config.ExternalConfig{TurnstileSecretKey: "configured"}},
		settings: settings,
	}
	if err := login.verifyTurnstile(request, "", "login"); err != nil {
		t.Fatalf("disabled login challenge returned error: %v", err)
	}
	register := RegisterHandler{
		cfg:      config.Config{External: config.ExternalConfig{TurnstileSecretKey: "configured"}},
		settings: settings,
	}
	if err := register.verifyTurnstile(request, ""); err != nil {
		t.Fatalf("disabled registration challenge returned error: %v", err)
	}
}

func TestAppChallengeStartReportsDisabledWithoutCreatingChallenge(t *testing.T) {
	handler := ChallengeHandler{
		cfg:      config.Config{App: config.AppConfig{UserAgentToken: "loc-app"}},
		rates:    newFakeRateLimiter(),
		settings: appChallengeSettingStub{required: false},
	}
	request := httptest.NewRequest(http.MethodPost, "/api/app-challenge", bytes.NewBufferString(`{"purpose":"login"}`))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("User-Agent", "loc-app/test")
	response := httptest.NewRecorder()
	handler.start(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", response.Code, response.Body.String())
	}
	var payload map[string]any
	if err := json.Unmarshal(response.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if payload["challenge_required"] != false {
		t.Fatalf("challenge_required = %#v, want false", payload["challenge_required"])
	}
}

func TestAppChallengeSettingFailureIsNotTreatedAsDisabled(t *testing.T) {
	expected := errors.New("settings unavailable")
	_, err := appChallengeRequired(context.Background(), appChallengeSettingStub{err: expected})
	if !errors.Is(err, expected) {
		t.Fatalf("error = %v, want %v", err, expected)
	}
}
