package handlers

import (
	"database/sql"
	_ "embed"
	"encoding/json"
	"errors"
	"html/template"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"familylocation/location-v3/internal/config"
	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/models"
	"familylocation/location-v3/internal/repositories"
	"familylocation/location-v3/internal/services"
	"familylocation/location-v3/internal/session"
)

const (
	maxSharedLocations             = 30
	defaultOwnedSharesLimit        = 20
	maxOwnedSharesLimit            = 100
	maxActiveOwnedShares           = 20
	maxActiveGroupShares           = 100
	maxLocationShareCreatesPerHour = 20
)

//go:embed templates/location_share.html
var locationShareHTML string

//go:embed templates/location_share_unlock.html
var locationShareUnlockHTML string

var publicShareTokenPattern = regexp.MustCompile(`^[a-f0-9]{64}$`)

var errUnverifiableStoredShare = errors.New("stored share cannot be verified")

const publicShareSnapshotProvenance = "server-v1"

type ownedLocationShareItem struct {
	ShareURL      string `json:"share_url"`
	AccessCode    string `json:"access_code"`
	LocationCount int    `json:"location_count"`
	CreatedAt     string `json:"created_at"`
	ExpiresAt     string `json:"expires_at"`
	Active        bool   `json:"active"`
}

type locationShareSnapshotInput struct {
	ID                 int64          `json:"id"`
	Latitude           float64        `json:"latitude"`
	Longitude          float64        `json:"longitude"`
	Address            string         `json:"address"`
	City               string         `json:"city"`
	AddressDiagnostics map[string]any `json:"address_diagnostics"`
	LocationMeta       map[string]any `json:"location_meta"`
}

type ShareHandler struct {
	cfg       config.Config
	scope     scopedHandler
	locations repositories.LocationRepository
	shares    repositories.LocationShareRepository
	users     repositories.UserRepository
	rates     repositories.RateLimitRepository
}

func NewShareHandler(cfg config.Config, db *sql.DB, sessions session.Reader) ShareHandler {
	return ShareHandler{
		cfg:       cfg,
		scope:     newScopedHandler(db, sessions),
		locations: repositories.NewLocationRepository(db),
		shares:    repositories.NewLocationShareRepository(db),
		users:     repositories.NewUserRepository(db),
		rates:     repositories.NewRateLimitRepository(db),
	}
}

func (handler ShareHandler) Create(w http.ResponseWriter, r *http.Request) {
	var req struct {
		GroupName         string                       `json:"group_name"`
		LocationIDs       []int64                      `json:"location_ids"`
		LocationSnapshots []locationShareSnapshotInput `json:"location_snapshots"`
		ExpiresHours      int                          `json:"expires_hours"`
		AccessCode        string                       `json:"access_code"`
	}
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.Error(w, err)
		return
	}
	locationIDs := normalizeShareLocationIDs(req.LocationIDs)
	if len(locationIDs) == 0 {
		httpx.Error(w, httpx.Unprocessable("请至少选择一条已经上报的定位。"))
		return
	}
	if len(locationIDs) > maxSharedLocations {
		httpx.Error(w, httpx.Unprocessable("一次最多分享 30 条定位。"))
		return
	}
	accessCode := strings.TrimSpace(req.AccessCode)
	if length := len([]rune(accessCode)); length < 4 || length > 16 {
		httpx.Error(w, httpx.Unprocessable("分享码需要 4–16 个字符。"))
		return
	}
	lifetime, ok := shareLifetimeForHours(req.ExpiresHours)
	if !ok {
		httpx.Error(w, httpx.Unprocessable("分享有效期不受支持。"))
		return
	}

	scope, _, err := handler.scope.requireScope(r, selectedGroupName(r, req.GroupName))
	if err != nil {
		httpx.Error(w, err)
		return
	}
	allowed, err := handler.rates.Hit(r.Context(), "location_share_create", strconv.FormatInt(scope.User.ID, 10), maxLocationShareCreatesPerHour, time.Hour)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if !allowed {
		httpx.Error(w, httpx.APIError{Status: http.StatusTooManyRequests, Message: "创建分享过于频繁，请稍后再试。"})
		return
	}
	rows := make([]models.Location, 0, len(locationIDs))
	snapshotCoordinates := make(map[int64]locationShareSnapshotInput, len(locationIDs))
	for _, locationID := range locationIDs {
		location, err := handler.locations.HistoryByIDForGroup(r.Context(), scope.Membership.GroupName, locationID)
		if err != nil {
			httpx.Error(w, err)
			return
		}
		if location == nil {
			httpx.Error(w, httpx.Unprocessable("所选定位不存在或没有可显示的坐标。"))
			return
		}
		coordinate, err := shareSnapshotCoordinate(*location)
		if err != nil {
			httpx.Error(w, err)
			return
		}
		if !shareableCoordinate(coordinate.Latitude, coordinate.Longitude) {
			httpx.Error(w, httpx.Unprocessable("所选定位不存在或没有可显示的坐标。"))
			return
		}
		rows = append(rows, *location)
		snapshotCoordinates[locationID] = coordinate
	}
	snapshotJSON, err := json.Marshal(publicShareSnapshot(rows, snapshotCoordinates))
	if err != nil {
		httpx.Error(w, err)
		return
	}
	locationIDsJSON, _ := json.Marshal(locationIDs)
	accessCodeHash, err := services.HashPassword(accessCode)
	if err != nil {
		httpx.Error(w, err)
		return
	}

	token, err := randomHex(32)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	tokenHash := sha256Hex(token)
	expiresAt := time.Now().Add(lifetime)
	share := repositories.LocationShare{
		TokenHash:           tokenHash,
		TokenPlaintext:      token,
		OwnerUserID:         scope.User.ID,
		GroupName:           scope.Membership.GroupName,
		LocationIDsJSON:     string(locationIDsJSON),
		SnapshotJSON:        string(snapshotJSON),
		AccessCodeHash:      accessCodeHash,
		AccessCodePlaintext: accessCode,
		ExpiresAt:           expiresAt,
	}
	if err := handler.shares.CreateWithinQuota(r.Context(), share, time.Now(), maxActiveOwnedShares, maxActiveGroupShares); err != nil {
		if errors.Is(err, repositories.ErrLocationShareAuthorization) {
			err = httpx.Forbidden("家庭组身份已失效，请刷新后重试。")
		} else if errors.Is(err, repositories.ErrLocationShareUserQuota) {
			err = httpx.APIError{Status: http.StatusTooManyRequests, Message: "你的有效位置分享已达到上限，请等待已有分享过期。"}
		} else if errors.Is(err, repositories.ErrLocationShareGroupQuota) {
			err = httpx.APIError{Status: http.StatusTooManyRequests, Message: "当前家庭组的有效位置分享已达到上限，请稍后再试。"}
		}
		httpx.Error(w, err)
		return
	}
	id := scope.User.ID
	_ = handler.users.RecordLog(r.Context(), &id, scope.Membership.GroupName, "location_share_create", "创建位置分享", map[string]any{
		"location_count": len(locationIDs),
		"expires_at":     services.FormatDatetime(expiresAt),
	}, httpx.ClientIP(r), r.UserAgent())

	shareURL := httpx.PublicURL(r, publicSharePath(token))
	httpx.OK(w, map[string]any{
		"ok":         true,
		"share_url":  shareURL,
		"expires_at": services.FormatDatetime(expiresAt),
	})
}

func (handler ShareHandler) List(w http.ResponseWriter, r *http.Request) {
	scope, _, err := handler.scope.requireScope(r, selectedGroupName(r, ""))
	if err != nil {
		httpx.Error(w, err)
		return
	}
	limit, offset := ownedSharesPagination(r)
	shares, err := handler.shares.ListOwned(r.Context(), scope.User.ID, scope.Membership.GroupName, limit, offset)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	now := time.Now()
	items := make([]ownedLocationShareItem, 0, len(shares))
	for _, share := range shares {
		items = append(items, buildOwnedLocationShareItem(r, share, now))
	}
	httpx.OK(w, map[string]any{
		"ok":     true,
		"shares": items,
		"limit":  limit,
		"offset": offset,
	})
}

func (handler ShareHandler) PublicPage(w http.ResponseWriter, r *http.Request) {
	token := publicShareToken(r)
	if !publicShareTokenPattern.MatchString(token) {
		handler.writePublicError(w, http.StatusNotFound, "分享链接无效")
		return
	}
	share, err := handler.shares.FindActive(r.Context(), sha256Hex(token), time.Now())
	if err != nil {
		handler.writePublicError(w, http.StatusInternalServerError, "分享页面暂时无法加载")
		return
	}
	if share == nil {
		handler.writePublicError(w, http.StatusGone, "分享链接已过期或已失效")
		return
	}
	if r.Method != http.MethodPost {
		handler.writeUnlockPage(w, token, "")
		return
	}
	allowed, err := handler.rates.Hit(r.Context(), "location_share_unlock", share.TokenHash+":"+httpx.ClientIP(r), 20, 10*time.Minute)
	if err != nil {
		handler.writePublicError(w, http.StatusInternalServerError, "分享页面暂时无法加载")
		return
	}
	if !allowed {
		handler.writeUnlockPage(w, token, "尝试次数过多，请稍后再试。")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 4096)
	if err := r.ParseForm(); err != nil || !services.CheckPassword(strings.TrimSpace(r.FormValue("access_code")), share.AccessCodeHash) {
		handler.writeUnlockPage(w, token, "分享码不正确。")
		return
	}
	locations := make([]map[string]any, 0)
	trustedSnapshot := false
	if strings.TrimSpace(share.SnapshotJSON) != "" {
		if err := json.Unmarshal([]byte(share.SnapshotJSON), &locations); err == nil && len(locations) > 0 {
			trustedSnapshot = consumeTrustedPublicShareSnapshot(locations)
		}
	}
	if !trustedSnapshot {
		locations, err = handler.verifiedStoredShareLocations(r, *share)
		if errors.Is(err, errUnverifiableStoredShare) {
			handler.writePublicError(w, http.StatusGone, "分享数据来源无法验证，请重新创建分享")
			return
		}
		if err != nil {
			handler.writePublicError(w, http.StatusInternalServerError, "历史位置暂时无法加载")
			return
		}
	}
	if len(locations) == 0 {
		handler.writePublicError(w, http.StatusGone, "分享的定位记录已不存在")
		return
	}
	payload := map[string]any{
		"selected_id": locations[0]["id"],
		"expires_at":  services.FormatDatetime(share.ExpiresAt),
		"locations":   locations,
	}
	rawPayload, err := json.Marshal(payload)
	if err != nil {
		handler.writePublicError(w, http.StatusInternalServerError, "分享页面暂时无法加载")
		return
	}

	page := strings.ReplaceAll(locationShareHTML, "__AMAP_KEY__", jsonLiteral(handler.cfg.External.AMapJSAPIKey))
	page = strings.ReplaceAll(page, "__AMAP_SERVICE_PATH__", jsonLiteral(handler.cfg.External.AMapSharePath))
	page = strings.ReplaceAll(page, "__SHARE_PAYLOAD__", string(rawPayload))
	w.Header().Set("Referrer-Policy", "strict-origin-when-cross-origin")
	w.Header().Set("X-Robots-Tag", "noindex, nofollow, noarchive")
	writeNoStoreHTML(w, page)
}

func (handler ShareHandler) writeUnlockPage(w http.ResponseWriter, token string, message string) {
	page := strings.ReplaceAll(locationShareUnlockHTML, "__SHARE_ACTION__", template.HTMLEscapeString(publicSharePath(token)))
	page = strings.ReplaceAll(page, "__SHARE_MESSAGE__", template.HTMLEscapeString(message))
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.Header().Set("X-Robots-Tag", "noindex, nofollow, noarchive")
	writeNoStoreHTML(w, page)
}

func (handler ShareHandler) writePublicError(w http.ResponseWriter, status int, message string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.Header().Set("X-Robots-Tag", "noindex, nofollow, noarchive")
	w.WriteHeader(status)
	_, _ = w.Write([]byte(`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>位置分享</title><style>html,body{margin:0;min-height:100%;background:#eef3f1;color:#173b35;font-family:system-ui,sans-serif}body{display:grid;place-items:center}.message{padding:24px;text-align:center;font-size:16px}</style></head><body><div class="message">` + template.HTMLEscapeString(message) + `</div></body></html>`))
}

func publicShareToken(r *http.Request) string {
	if token := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("token"))); token != "" {
		return token
	}
	return strings.ToLower(strings.TrimSpace(strings.TrimPrefix(r.URL.Path, "/share/token=")))
}

func publicSharePath(token string) string {
	return "/share?token=" + token
}

func shareableCoordinate(latitude float64, longitude float64) bool {
	return latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180 && !(latitude == 0 && longitude == 0)
}

func shareSnapshotCoordinate(location models.Location) (locationShareSnapshotInput, error) {
	if location.EncryptionMode == "p2p-v1" {
		return locationShareSnapshotInput{}, httpx.Unprocessable("端到端加密定位暂不支持创建公开链接分享。")
	}
	return locationShareSnapshotInput{
		ID:                 location.ID,
		Latitude:           location.Latitude,
		Longitude:          location.Longitude,
		AddressDiagnostics: shareJSONMap(location.AddressDiagnostics.String, location.AddressDiagnostics.Valid),
		LocationMeta:       shareJSONMap(location.LocationMeta.String, location.LocationMeta.Valid),
	}, nil
}

func publicShareLocations(rows []models.Location) []map[string]any {
	coordinates := make(map[int64]locationShareSnapshotInput, len(rows))
	for _, row := range rows {
		coordinates[row.ID] = locationShareSnapshotInput{
			ID:                 row.ID,
			Latitude:           row.Latitude,
			Longitude:          row.Longitude,
			AddressDiagnostics: shareJSONMap(row.AddressDiagnostics.String, row.AddressDiagnostics.Valid),
			LocationMeta:       shareJSONMap(row.LocationMeta.String, row.LocationMeta.Valid),
		}
	}
	result := publicShareSnapshot(rows, coordinates)
	_ = consumeTrustedPublicShareSnapshot(result)
	return result
}

func publicShareSnapshot(rows []models.Location, coordinates map[int64]locationShareSnapshotInput) []map[string]any {
	result := make([]map[string]any, 0, len(rows))
	memberKeys := map[int64]string{}
	for _, row := range rows {
		coordinate := coordinates[row.ID]
		if !shareableCoordinate(coordinate.Latitude, coordinate.Longitude) {
			continue
		}
		diagnostics := coordinate.AddressDiagnostics
		meta := coordinate.LocationMeta
		minimalDiagnostics := map[string]any{}
		for _, key := range []string{"preferred_address", "preferred_city", "preferred_coordinate_system"} {
			if value, ok := diagnostics[key]; ok {
				minimalDiagnostics[key] = value
			}
		}
		minimalMeta := map[string]any{}
		for _, key := range []string{"coordinate_system", "mock_provider"} {
			if value, ok := meta[key]; ok {
				minimalMeta[key] = value
			}
		}
		if strings.TrimSpace(coordinate.Address) != "" {
			minimalDiagnostics["preferred_address"] = strings.TrimSpace(coordinate.Address)
		}
		if strings.TrimSpace(coordinate.City) != "" {
			minimalDiagnostics["preferred_city"] = strings.TrimSpace(coordinate.City)
		}
		name := strings.TrimSpace(row.DisplayName)
		if name == "" {
			name = "成员"
		}
		memberKey, exists := memberKeys[row.UserID]
		if !exists {
			memberKey = "member-" + strconv.Itoa(len(memberKeys)+1)
			memberKeys[row.UserID] = memberKey
		}
		result = append(result, map[string]any{
			"_snapshot_provenance": publicShareSnapshotProvenance,
			"id":                   row.ID,
			"display_name":         name,
			"role_label":           services.RoleLabel(row.Role),
			"member_key":           memberKey,
			"latitude":             coordinate.Latitude,
			"longitude":            coordinate.Longitude,
			"created_at":           services.FormatDatetime(row.CreatedAt),
			"address":              shareTextValue(minimalDiagnostics["preferred_address"]),
			"city":                 shareTextValue(minimalDiagnostics["preferred_city"]),
			"address_diagnostics":  minimalDiagnostics,
			"location_meta":        minimalMeta,
		})
	}
	return result
}

func consumeTrustedPublicShareSnapshot(locations []map[string]any) bool {
	for _, location := range locations {
		if location["_snapshot_provenance"] != publicShareSnapshotProvenance {
			return false
		}
	}
	for _, location := range locations {
		delete(location, "_snapshot_provenance")
	}
	return true
}

func (handler ShareHandler) verifiedStoredShareLocations(r *http.Request, share repositories.LocationShare) ([]map[string]any, error) {
	var locationIDs []int64
	if err := json.Unmarshal([]byte(share.LocationIDsJSON), &locationIDs); err != nil {
		return nil, errUnverifiableStoredShare
	}
	locationIDs = normalizeShareLocationIDs(locationIDs)
	if len(locationIDs) == 0 {
		return nil, errUnverifiableStoredShare
	}
	rows := make([]models.Location, 0, len(locationIDs))
	for _, locationID := range locationIDs {
		location, err := handler.locations.HistoryByIDForGroup(r.Context(), share.GroupName, locationID)
		if err != nil {
			return nil, err
		}
		if location == nil || location.EncryptionMode == "p2p-v1" {
			return nil, errUnverifiableStoredShare
		}
		rows = append(rows, *location)
	}
	locations := publicShareLocations(rows)
	if len(locations) != len(locationIDs) {
		return nil, errUnverifiableStoredShare
	}
	return locations, nil
}

func normalizeShareLocationIDs(values []int64) []int64 {
	result := make([]int64, 0, len(values))
	seen := map[int64]bool{}
	for _, value := range values {
		if value <= 0 || seen[value] {
			continue
		}
		seen[value] = true
		result = append(result, value)
	}
	return result
}

func ownedSharesPagination(r *http.Request) (int, int) {
	limit := httpx.IntQuery(r, "limit", defaultOwnedSharesLimit)
	if limit < 1 {
		limit = defaultOwnedSharesLimit
	} else if limit > maxOwnedSharesLimit {
		limit = maxOwnedSharesLimit
	}
	offset := httpx.IntQuery(r, "offset", 0)
	if offset < 0 {
		offset = 0
	}
	return limit, offset
}

func buildOwnedLocationShareItem(r *http.Request, share repositories.LocationShare, now time.Time) ownedLocationShareItem {
	item := ownedLocationShareItem{
		LocationCount: shareLocationCount(share.LocationIDsJSON),
		CreatedAt:     services.FormatDatetime(share.CreatedAt),
		ExpiresAt:     services.FormatDatetime(share.ExpiresAt),
		Active:        share.ExpiresAt.After(now),
	}
	token := strings.TrimSpace(share.TokenPlaintext)
	if publicShareTokenPattern.MatchString(token) {
		item.ShareURL = httpx.PublicURL(r, publicSharePath(token))
	}
	item.AccessCode = strings.TrimSpace(share.AccessCodePlaintext)
	return item
}

func shareLocationCount(raw string) int {
	var locationIDs []int64
	if err := json.Unmarshal([]byte(raw), &locationIDs); err != nil {
		return 0
	}
	return len(normalizeShareLocationIDs(locationIDs))
}

func shareLifetimeForHours(hours int) (time.Duration, bool) {
	switch hours {
	case 1, 24, 168, 720:
		return time.Duration(hours) * time.Hour, true
	default:
		return 0, false
	}
}

func shareJSONMap(raw string, valid bool) map[string]any {
	result := map[string]any{}
	if !valid || strings.TrimSpace(raw) == "" {
		return result
	}
	_ = json.Unmarshal([]byte(raw), &result)
	return result
}

func shareTextValue(value any) string {
	text, ok := value.(string)
	if !ok {
		return ""
	}
	return strings.TrimSpace(text)
}
