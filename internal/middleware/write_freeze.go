package middleware

import (
	"crypto/subtle"
	"fmt"
	"io"
	"net/http"
	"os"
	"regexp"
	"runtime"
	"strings"

	"familylocation/location-v3/internal/httpx"
)

const (
	deploymentVerifyHeader = "X-Location-Deployment-Verify"
	maxWriteFreezeBytes    = 512
)

var deploymentIDPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`)
var deploymentTokenPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

type writeFreezeAuthorization struct {
	deploymentID string
	token        string
}

// WriteFreeze blocks mutating requests while a deployment freeze file exists.
// The deployment verifier can pass one request with the file-bound credential.
func WriteFreeze(path string) Middleware {
	path = strings.TrimSpace(path)
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if path == "" || writeFreezeSafeMethod(r.Method) {
				next.ServeHTTP(w, r)
				return
			}

			authorization, err := loadWriteFreezeAuthorization(path)
			if err == nil && authorization == nil {
				next.ServeHTTP(w, r)
				return
			}
			if err != nil || !authorization.matches(r.Header.Values(deploymentVerifyHeader)) {
				httpx.Error(w, httpx.APIError{
					Status:  http.StatusServiceUnavailable,
					Message: "Service is temporarily read-only during deployment.",
					Code:    "deployment_write_frozen",
				})
				return
			}

			next.ServeHTTP(w, r)
		})
	}
}

func writeFreezeSafeMethod(method string) bool {
	switch method {
	case http.MethodGet, http.MethodHead, http.MethodOptions:
		return true
	default:
		return false
	}
}

func loadWriteFreezeAuthorization(path string) (*writeFreezeAuthorization, error) {
	pathInfo, err := os.Lstat(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, fmt.Errorf("inspect write freeze file: %w", err)
	}
	if err := validateWriteFreezeFileInfo(pathInfo); err != nil {
		return nil, err
	}

	file, err := os.Open(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, fmt.Errorf("open write freeze file: %w", err)
	}
	defer file.Close()

	openedInfo, err := file.Stat()
	if err != nil {
		return nil, fmt.Errorf("stat opened write freeze file: %w", err)
	}
	if !os.SameFile(pathInfo, openedInfo) {
		return nil, fmt.Errorf("write freeze file changed while opening")
	}
	if err := validateWriteFreezeFileInfo(openedInfo); err != nil {
		return nil, err
	}

	content, err := io.ReadAll(io.LimitReader(file, maxWriteFreezeBytes+1))
	if err != nil {
		return nil, fmt.Errorf("read write freeze file: %w", err)
	}
	if len(content) > maxWriteFreezeBytes || int64(len(content)) != openedInfo.Size() {
		return nil, fmt.Errorf("write freeze file changed or exceeds size limit")
	}
	afterReadInfo, err := file.Stat()
	if err != nil {
		return nil, fmt.Errorf("restat write freeze file: %w", err)
	}
	if !os.SameFile(openedInfo, afterReadInfo) || afterReadInfo.Size() != int64(len(content)) {
		return nil, fmt.Errorf("write freeze file changed while reading")
	}
	if err := validateWriteFreezeFileInfo(afterReadInfo); err != nil {
		return nil, err
	}

	return parseWriteFreezeAuthorization(string(content))
}

func validateWriteFreezeFileInfo(info os.FileInfo) error {
	if !info.Mode().IsRegular() {
		return fmt.Errorf("write freeze path must be a regular file")
	}
	if info.Size() <= 0 || info.Size() > maxWriteFreezeBytes {
		return fmt.Errorf("write freeze file has an invalid size")
	}
	// Windows ACLs are not represented by FileMode. Production runs on Linux,
	// where group- or other-writable credentials are always rejected.
	if runtime.GOOS != "windows" && info.Mode().Perm()&0o022 != 0 {
		return fmt.Errorf("write freeze file must not be writable by group or others")
	}
	return nil
}

func parseWriteFreezeAuthorization(content string) (*writeFreezeAuthorization, error) {
	if strings.ContainsAny(content, "\r\x00") {
		return nil, fmt.Errorf("write freeze file contains forbidden characters")
	}
	content = strings.TrimSuffix(content, "\n")
	lines := strings.Split(content, "\n")
	if len(lines) != 3 || lines[0] != "VERSION=1" {
		return nil, fmt.Errorf("write freeze file must use the version 1 three-line format")
	}
	if !strings.HasPrefix(lines[1], "DEPLOYMENT_ID=") || !strings.HasPrefix(lines[2], "TOKEN=") {
		return nil, fmt.Errorf("write freeze file fields are invalid")
	}

	deploymentID := strings.TrimPrefix(lines[1], "DEPLOYMENT_ID=")
	token := strings.TrimPrefix(lines[2], "TOKEN=")
	if !deploymentIDPattern.MatchString(deploymentID) || !deploymentTokenPattern.MatchString(token) {
		return nil, fmt.Errorf("write freeze file credential is invalid")
	}
	return &writeFreezeAuthorization{deploymentID: deploymentID, token: token}, nil
}

func (authorization writeFreezeAuthorization) matches(values []string) bool {
	if len(values) != 1 {
		return false
	}
	deploymentID, token, ok := strings.Cut(values[0], ":")
	if !ok || strings.Contains(token, ":") || deploymentID != authorization.deploymentID || len(token) != 64 {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(token), []byte(authorization.token)) == 1
}
