package httpx

import (
	"encoding/json"
	"errors"
	"net/http"
)

type APIError struct {
	Status  int
	Message string
	Code    string
	Details map[string]any
}

func (e APIError) Error() string {
	return e.Message
}

func JSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func OK(w http.ResponseWriter, payload any) {
	JSON(w, http.StatusOK, payload)
}

func Error(w http.ResponseWriter, err error) {
	var apiErr APIError
	if errors.As(err, &apiErr) {
		payload := map[string]any{
			"ok":      false,
			"message": apiErr.Message,
		}
		if apiErr.Code != "" {
			payload["code"] = apiErr.Code
		}
		if len(apiErr.Details) > 0 {
			payload["details"] = apiErr.Details
		}
		JSON(w, apiErr.Status, payload)
		return
	}

	JSON(w, http.StatusInternalServerError, map[string]any{
		"ok":      false,
		"message": "服务器错误，请稍后重试。",
	})
}

func BadRequest(message string) APIError {
	return APIError{Status: http.StatusBadRequest, Message: message}
}

func Unauthorized(message string) APIError {
	return APIError{Status: http.StatusUnauthorized, Message: message}
}

func Forbidden(message string) APIError {
	return APIError{Status: http.StatusForbidden, Message: message}
}

func Unprocessable(message string) APIError {
	return APIError{Status: http.StatusUnprocessableEntity, Message: message}
}
