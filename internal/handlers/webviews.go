package handlers

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"net/http"
	"regexp"
	"strconv"
	"strings"

	"familylocation/location-v3/internal/config"
)

//go:embed templates/history_map.html
var historyMapHTML string

//go:embed templates/amap_reverse.html
var amapReverseHTML string

//go:embed templates/webrtc_probe.html
var webrtcProbeHTML string

var coordinateSystemPattern = regexp.MustCompile(`[^a-z0-9]`)

type WebViewHandler struct {
	cfg config.Config
}

func NewWebViewHandler(cfg config.Config) WebViewHandler {
	return WebViewHandler{cfg: cfg}
}

func (handler WebViewHandler) HistoryMap(w http.ResponseWriter, r *http.Request) {
	page := historyMapHTML
	page = strings.ReplaceAll(page, "__AMAP_KEY__", jsonLiteral(handler.cfg.External.AMapJSAPIKey))
	page = strings.ReplaceAll(page, "__AMAP_SERVICE_PATH__", jsonLiteral(handler.cfg.External.AMapServicePath))
	writeNoStoreHTML(w, page)
}

func (handler WebViewHandler) AMapReverse(w http.ResponseWriter, r *http.Request) {
	latitude, latOK := queryFloat(r, "lat")
	longitude, lngOK := queryFloat(r, "lng")
	if !latOK || !lngOK || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180 {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.WriteHeader(http.StatusUnprocessableEntity)
		_, _ = fmt.Fprint(w, `<!doctype html><meta charset="utf-8"><script>if(window.locApp){locApp.onAmapReverseError("invalid coordinate")}</script>`)
		return
	}
	coordinateSystem := normalizeCoordinateSystem(firstString(r.URL.Query().Get("coord"), r.URL.Query().Get("coordinate_system")))
	page := amapReverseHTML
	page = strings.ReplaceAll(page, "__AMAP_KEY__", jsonLiteral(handler.cfg.External.AMapJSAPIKey))
	page = strings.ReplaceAll(page, "__AMAP_SERVICE_PATH__", jsonLiteral(handler.cfg.External.AMapServicePath))
	page = strings.ReplaceAll(page, "__LATITUDE__", strconv.FormatFloat(latitude, 'f', -1, 64))
	page = strings.ReplaceAll(page, "__LONGITUDE__", strconv.FormatFloat(longitude, 'f', -1, 64))
	page = strings.ReplaceAll(page, "__COORDINATE_SYSTEM__", jsonLiteral(coordinateSystem))
	writeNoStoreHTML(w, page)
}

func (handler WebViewHandler) WebRTCProbe(w http.ResponseWriter, r *http.Request) {
	writeNoStoreHTML(w, webrtcProbeHTML)
}

func queryFloat(r *http.Request, key string) (float64, bool) {
	text := strings.TrimSpace(r.URL.Query().Get(key))
	if text == "" {
		return 0, false
	}
	value, err := strconv.ParseFloat(text, 64)
	return value, err == nil
}

func normalizeCoordinateSystem(value string) string {
	value = coordinateSystemPattern.ReplaceAllString(strings.ToLower(value), "")
	switch value {
	case "bd09", "gcj02", "wgs84":
		return value
	default:
		return "wgs84"
	}
}

func jsonLiteral(value any) string {
	raw, err := json.Marshal(value)
	if err != nil {
		return `""`
	}
	return string(raw)
}

func writeNoStoreHTML(w http.ResponseWriter, content string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store, no-transform")
	_, _ = fmt.Fprint(w, content)
}
