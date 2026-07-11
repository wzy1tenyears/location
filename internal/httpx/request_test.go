package httpx

import (
	"errors"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestDecodeJSONRejectsMalformedBody(t *testing.T) {
	request := httptest.NewRequest("POST", "/", strings.NewReader(`{"value":`))
	var payload map[string]any
	err := DecodeJSON(request, &payload)
	var apiErr APIError
	if !errors.As(err, &apiErr) || apiErr.Status != 400 {
		t.Fatalf("DecodeJSON() error = %#v, want APIError 400", err)
	}
}

func TestDecodeJSONRejectsTrailingObject(t *testing.T) {
	request := httptest.NewRequest("POST", "/", strings.NewReader(`{"value":1} {"value":2}`))
	var payload map[string]any
	if err := DecodeJSON(request, &payload); err == nil {
		t.Fatal("DecodeJSON() accepted two JSON objects")
	}
}
