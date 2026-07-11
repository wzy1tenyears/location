package handlers

import (
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"familylocation/location-v3/internal/config"
	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/session"
)

type UpdateHandler struct {
	cfg      config.Config
	sessions session.Reader
}

func NewUpdateHandler(cfg config.Config, sessions session.Reader) UpdateHandler {
	return UpdateHandler{cfg: cfg, sessions: sessions}
}

func (handler UpdateHandler) AppUpdate(w http.ResponseWriter, r *http.Request) {
	currentVersionCode := httpx.IntQuery(r, "version_code", 0)
	apkPath := "/" + handler.cfg.Files.UserAPKFilename
	apkFile := filepath.Join(handler.cfg.Files.PublicBaseDir, handler.cfg.Files.UserAPKFilename)
	_, statErr := os.Stat(apkFile)
	apkExists := statErr == nil

	httpx.OK(w, map[string]any{
		"ok":                   true,
		"latest_version_code":  handler.cfg.App.VersionCode,
		"latest_version_name":  handler.cfg.App.VersionName,
		"current_version_code": currentVersionCode,
		"update_required":      apkExists && currentVersionCode > 0 && handler.cfg.App.VersionCode > currentVersionCode,
		"force_update":         handler.cfg.App.ForceUpdate,
		"apk_url":              httpx.PublicURL(r, apkPath) + "?v=" + strconv.Itoa(handler.cfg.App.VersionCode),
		"apk_exists":           apkExists,
	})
}

func (handler UpdateHandler) AdminAppUpdate(w http.ResponseWriter, r *http.Request) {
	if !handler.sessions.IsAdmin(r) {
		httpx.Error(w, httpx.Unauthorized("请先登录后台。"))
		return
	}

	currentVersionCode := httpx.IntQuery(r, "version_code", 0)
	apkFile := filepath.Join(handler.cfg.Files.PublicBaseDir, handler.cfg.Files.AdminAPKFilename)
	_, statErr := os.Stat(apkFile)
	apkExists := statErr == nil

	expires := time.Now().Unix() + 600
	token := adminAPKToken(strconv.Itoa(handler.cfg.Admin.VersionCode), strconv.FormatInt(expires, 10), tokenSigningSecret(handler.cfg))
	apkURL := httpx.PublicURL(r, "/api/admin/apk") +
		"?v=" + strconv.Itoa(handler.cfg.Admin.VersionCode) +
		"&expires=" + strconv.FormatInt(expires, 10) +
		"&token=" + token

	httpx.OK(w, map[string]any{
		"ok":                   true,
		"latest_version_code":  handler.cfg.Admin.VersionCode,
		"latest_version_name":  handler.cfg.Admin.VersionName,
		"current_version_code": currentVersionCode,
		"update_required":      apkExists && currentVersionCode > 0 && handler.cfg.Admin.VersionCode > currentVersionCode,
		"force_update":         handler.cfg.Admin.ForceUpdate,
		"apk_url":              apkURL,
		"apk_exists":           apkExists,
	})
}
