package handlers

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

type readRateLimiterStub struct {
	allowedByBucket map[string]bool
	calls           []string
}

func (stub *readRateLimiterStub) Hit(_ context.Context, bucket string, identity string, _ int, _ time.Duration) (bool, error) {
	stub.calls = append(stub.calls, bucket+":"+identity)
	allowed, exists := stub.allowedByBucket[bucket]
	if !exists {
		return true, nil
	}
	return allowed, nil
}

func TestAllowScopedReadChecksUserAndIP(t *testing.T) {
	limiter := &readRateLimiterStub{allowedByBucket: map[string]bool{}}
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/api/locations", nil)
	request.RemoteAddr = "203.0.113.8:4242"

	if !allowScopedRead(recorder, request, limiter, "locations_read", 42, 120, 240, time.Minute) {
		t.Fatal("allowScopedRead() = false, want true")
	}
	if len(limiter.calls) != 2 {
		t.Fatalf("rate calls = %v, want user and IP", limiter.calls)
	}
}

func TestAllowScopedReadReturns429AndRetryAfter(t *testing.T) {
	limiter := &readRateLimiterStub{allowedByBucket: map[string]bool{"history_read_ip": false}}
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/api/history", nil)
	request.RemoteAddr = "203.0.113.9:4343"

	if allowScopedRead(recorder, request, limiter, "history_read", 7, 30, 90, time.Minute) {
		t.Fatal("allowScopedRead() = true, want false")
	}
	if recorder.Code != http.StatusTooManyRequests {
		t.Fatalf("status = %d, want %d", recorder.Code, http.StatusTooManyRequests)
	}
	if got := recorder.Header().Get("Retry-After"); got != "60" {
		t.Fatalf("Retry-After = %q, want 60", got)
	}
}
