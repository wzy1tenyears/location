package middleware

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

const testDeploymentID = "20260719-010203-a1b2c3d4"
const testDeploymentToken = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

func TestWriteFreezeAllowsMutationsWhenDisabledOrAbsent(t *testing.T) {
	for _, path := range []string{"", filepath.Join(t.TempDir(), "missing-freeze")} {
		t.Run(path, func(t *testing.T) {
			status, called := serveThroughWriteFreeze(t, path, http.MethodPost, "")
			if status != http.StatusNoContent || !called {
				t.Fatalf("status = %d, called = %v", status, called)
			}
		})
	}
}

func TestWriteFreezeAllowsSafeMethodsWithoutCredential(t *testing.T) {
	path := writeValidFreezeFile(t)
	for _, method := range []string{http.MethodGet, http.MethodHead, http.MethodOptions} {
		t.Run(method, func(t *testing.T) {
			status, called := serveThroughWriteFreeze(t, path, method, "")
			if status != http.StatusNoContent || !called {
				t.Fatalf("status = %d, called = %v", status, called)
			}
		})
	}
}

func TestWriteFreezeBlocksMutationWithoutExactCredential(t *testing.T) {
	path := writeValidFreezeFile(t)
	for name, header := range map[string]string{
		"missing":             "",
		"wrong deployment":    "other-deployment:" + testDeploymentToken,
		"wrong token":         testDeploymentID + ":" + strings.Repeat("f", 64),
		"uppercase token":     testDeploymentID + ":" + strings.ToUpper(testDeploymentToken),
		"extra token segment": testDeploymentID + ":" + testDeploymentToken + ":extra",
	} {
		t.Run(name, func(t *testing.T) {
			status, called, body := serveThroughWriteFreezeWithBody(t, path, http.MethodPost, header)
			if status != http.StatusServiceUnavailable || called {
				t.Fatalf("status = %d, called = %v", status, called)
			}
			var payload map[string]any
			if err := json.Unmarshal(body, &payload); err != nil {
				t.Fatalf("response is not JSON: %v", err)
			}
			if payload["ok"] != false || payload["code"] != "deployment_write_frozen" {
				t.Fatalf("payload = %#v", payload)
			}
		})
	}
}

func TestWriteFreezeAllowsMutationWithExactCredential(t *testing.T) {
	path := writeValidFreezeFile(t)
	status, called := serveThroughWriteFreeze(t, path, http.MethodPost, testDeploymentID+":"+testDeploymentToken)
	if status != http.StatusNoContent || !called {
		t.Fatalf("status = %d, called = %v", status, called)
	}
}

func TestWriteFreezeRejectsMultipleCredentialHeaders(t *testing.T) {
	path := writeValidFreezeFile(t)
	called := false
	handler := WriteFreeze(path)(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		called = true
		w.WriteHeader(http.StatusNoContent)
	}))
	request := httptest.NewRequest(http.MethodPost, "/api/report-location", nil)
	request.Header.Add(deploymentVerifyHeader, testDeploymentID+":"+testDeploymentToken)
	request.Header.Add(deploymentVerifyHeader, testDeploymentID+":"+testDeploymentToken)
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusServiceUnavailable || called {
		t.Fatalf("status = %d, called = %v", recorder.Code, called)
	}
}

func TestWriteFreezeFailsClosedForMalformedOrUnsafeFile(t *testing.T) {
	malformed := map[string]string{
		"missing token":    "VERSION=1\nDEPLOYMENT_ID=" + testDeploymentID + "\n",
		"extra field":      validFreezeContent() + "EXTRA=value\n",
		"wrong version":    strings.Replace(validFreezeContent(), "VERSION=1", "VERSION=2", 1),
		"uppercase token":  strings.Replace(validFreezeContent(), testDeploymentToken, strings.ToUpper(testDeploymentToken), 1),
		"empty deployment": strings.Replace(validFreezeContent(), testDeploymentID, "", 1),
		"windows newline":  strings.ReplaceAll(validFreezeContent(), "\n", "\r\n"),
	}
	for name, content := range malformed {
		t.Run(name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "write-freeze")
			if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
				t.Fatal(err)
			}
			status, called := serveThroughWriteFreeze(t, path, http.MethodPost, testDeploymentID+":"+testDeploymentToken)
			if status != http.StatusServiceUnavailable || called {
				t.Fatalf("status = %d, called = %v", status, called)
			}
		})
	}

	t.Run("directory", func(t *testing.T) {
		status, called := serveThroughWriteFreeze(t, t.TempDir(), http.MethodPost, testDeploymentID+":"+testDeploymentToken)
		if status != http.StatusServiceUnavailable || called {
			t.Fatalf("status = %d, called = %v", status, called)
		}
	})

	t.Run("symlink", func(t *testing.T) {
		dir := t.TempDir()
		target := filepath.Join(dir, "target")
		if err := os.WriteFile(target, []byte(validFreezeContent()), 0o600); err != nil {
			t.Fatal(err)
		}
		path := filepath.Join(dir, "write-freeze")
		if err := os.Symlink(target, path); err != nil {
			t.Skipf("symlink unavailable: %v", err)
		}
		status, called := serveThroughWriteFreeze(t, path, http.MethodPost, testDeploymentID+":"+testDeploymentToken)
		if status != http.StatusServiceUnavailable || called {
			t.Fatalf("status = %d, called = %v", status, called)
		}
	})

	if runtime.GOOS != "windows" {
		t.Run("group writable", func(t *testing.T) {
			path := writeValidFreezeFile(t)
			if err := os.Chmod(path, 0o660); err != nil {
				t.Fatal(err)
			}
			status, called := serveThroughWriteFreeze(t, path, http.MethodPost, testDeploymentID+":"+testDeploymentToken)
			if status != http.StatusServiceUnavailable || called {
				t.Fatalf("status = %d, called = %v", status, called)
			}
		})
	}
}

func writeValidFreezeFile(t *testing.T) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "write-freeze")
	if err := os.WriteFile(path, []byte(validFreezeContent()), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func validFreezeContent() string {
	return "VERSION=1\nDEPLOYMENT_ID=" + testDeploymentID + "\nTOKEN=" + testDeploymentToken + "\n"
}

func serveThroughWriteFreeze(t *testing.T, path string, method string, header string) (int, bool) {
	t.Helper()
	status, called, _ := serveThroughWriteFreezeWithBody(t, path, method, header)
	return status, called
}

func serveThroughWriteFreezeWithBody(t *testing.T, path string, method string, header string) (int, bool, []byte) {
	t.Helper()
	called := false
	handler := WriteFreeze(path)(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		called = true
		w.WriteHeader(http.StatusNoContent)
	}))
	request := httptest.NewRequest(method, "/api/report-location", nil)
	if header != "" {
		request.Header.Set(deploymentVerifyHeader, header)
	}
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)
	return recorder.Code, called, recorder.Body.Bytes()
}
