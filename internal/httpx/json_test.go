package httpx

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestErrorIncludesStableCodeAndDetailsWithoutChangingMessage(t *testing.T) {
	recorder := httptest.NewRecorder()
	Error(recorder, APIError{
		Status:  http.StatusUnprocessableEntity,
		Message: "请选择成员。",
		Code:    "selection_required",
		Details: map[string]any{"endpoint": "/api/members"},
	})
	if recorder.Code != http.StatusUnprocessableEntity {
		t.Fatalf("status = %d", recorder.Code)
	}
	var payload map[string]any
	if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil {
		t.Fatal(err)
	}
	if payload["message"] != "请选择成员。" || payload["code"] != "selection_required" {
		t.Fatalf("error payload = %#v", payload)
	}
	details, _ := payload["details"].(map[string]any)
	if details["endpoint"] != "/api/members" {
		t.Fatalf("error details = %#v", details)
	}
}
