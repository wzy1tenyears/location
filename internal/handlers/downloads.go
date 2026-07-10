package handlers

import (
	"crypto/hmac"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"familylocation/location-v3/internal/config"
	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/session"
)

type DownloadHandler struct {
	cfg      config.Config
	sessions session.Reader
}

func NewDownloadHandler(cfg config.Config, sessions session.Reader) DownloadHandler {
	return DownloadHandler{cfg: cfg, sessions: sessions}
}

func (handler DownloadHandler) AdminAPK(w http.ResponseWriter, r *http.Request) {
	requestedVersion := httpx.IntQuery(r, "v", 0)
	expires := int64(httpx.IntQuery(r, "expires", 0))
	token := r.URL.Query().Get("token")
	expected := adminAPKToken(strconv.Itoa(requestedVersion), strconv.FormatInt(expires, 10), handler.cfg.Database.Pass)
	hasDownloadToken := requestedVersion == handler.cfg.Admin.VersionCode &&
		expires >= time.Now().Unix() &&
		token != "" &&
		hmac.Equal([]byte(expected), []byte(token))
	if !hasDownloadToken && !handler.sessions.IsAdmin(r) {
		httpx.Error(w, httpx.Unauthorized("请先登录后台。"))
		return
	}

	apkFile := filepath.Join(handler.cfg.Files.PublicBaseDir, handler.cfg.Files.AdminAPKFilename)
	info, err := os.Stat(apkFile)
	if err != nil || info.IsDir() {
		httpx.Error(w, httpx.APIError{Status: http.StatusNotFound, Message: "后台更新包不存在。"})
		return
	}

	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", `attachment; filename="`+filepath.Base(handler.cfg.Files.AdminAPKFilename)+`"`)
	w.Header().Set("Content-Length", strconv.FormatInt(info.Size(), 10))
	w.Header().Set("Cache-Control", "private, no-store, max-age=0")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	http.ServeFile(w, r, apkFile)
}
