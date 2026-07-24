package handlers

import (
	"strings"
	"testing"
)

func TestWebViewTemplatesDoNotEscapeJavaScriptInterpolation(t *testing.T) {
	for name, template := range map[string]string{
		"history map":  historyMapHTML,
		"AMap reverse": amapReverseHTML,
	} {
		if strings.Contains(template, `\${`) {
			t.Fatalf("%s template contains escaped JavaScript interpolation", name)
		}
	}
}

func TestMapTemplateBuildsAMapScriptURL(t *testing.T) {
	if !strings.Contains(historyMapHTML, "`${serviceHost}/maps?v=${encodeURIComponent(version)}&key=${encodeURIComponent(AMAP_KEY)}`") {
		t.Fatal("history map template does not build the AMap script URL dynamically")
	}
	if !strings.Contains(historyMapHTML, "loadMapScript('2.0', true)") ||
		!strings.Contains(historyMapHTML, "loadMapScript('1.4.15', false)") {
		t.Fatal("history map template must retain the legacy WebView fallback")
	}
}

func TestHistoryMapInfoWindowBuildsTextOnlyDOM(t *testing.T) {
	start := strings.Index(historyMapHTML, "    function makeMarkerContent(")
	end := strings.Index(historyMapHTML, "    function normalizeRecord(")
	if start < 0 || end <= start {
		t.Fatal("history map marker rendering block not found")
	}
	markerRendering := historyMapHTML[start:end]
	lowerMarkerRendering := strings.ToLower(markerRendering)

	for _, required := range []string{
		"const content = document.createElement('div');",
		"row.textContent = line;",
		"content.appendChild(row);",
		"停留时间：${firstText(firstReportedAt, lastReportedAt)} 至 ${firstText(lastReportedAt, firstReportedAt)}（${formatStayDuration(item.stayDurationSeconds)}，${reportCount}次上报）",
		"return content;",
	} {
		if !strings.Contains(markerRendering, required) {
			t.Fatalf("history map InfoWindow is missing safe DOM construction %q", required)
		}
	}
	for _, forbidden := range []string{
		"innerhtml",
		"outerhtml",
		"insertadjacenthtml",
		"document.write",
		"document.writeln",
		"createcontextualfragment",
		"domparser",
		"sethtml(",
		"eval(",
		"new function",
		"join('<br",
		"join(\"<br",
	} {
		if strings.Contains(lowerMarkerRendering, forbidden) {
			t.Fatalf("history map marker rendering contains HTML/code sink %q", forbidden)
		}
	}
	if !strings.Contains(historyMapHTML, "infoWindow.setContent(markerInfo(item));") {
		t.Fatal("history map marker click no longer passes the safe DOM node to InfoWindow")
	}
}

func TestHistoryMapExcludesIPAndWebRTCMarkers(t *testing.T) {
	start := strings.Index(historyMapHTML, "    function expandRecords(")
	end := strings.Index(historyMapHTML, "    function clearMap(")
	if start < 0 || end <= start {
		t.Fatal("history map expansion block not found")
	}
	expansion := historyMapHTML[start:end]
	for _, forbidden := range []string{"diagnostics.sources", "normalizeDiagnosticSource", "sourceIndex", "webrtc", "'ip'"} {
		if strings.Contains(expansion, forbidden) {
			t.Fatalf("history map still expands network diagnostic marker data %q", forbidden)
		}
	}
	for _, forbidden := range []string{".marker.ip", ".marker.webrtc"} {
		if strings.Contains(historyMapHTML, forbidden) {
			t.Fatalf("history map still contains network marker styling %q", forbidden)
		}
	}
}

func TestHistoryMapUsesStableGPSMarkerKeys(t *testing.T) {
	start := strings.Index(historyMapHTML, "    function stableMarkerKey(")
	end := strings.Index(historyMapHTML, "    function expandRecords(")
	if start < 0 || end <= start {
		t.Fatal("history map stable marker key helper not found")
	}
	stableKey := historyMapHTML[start:end]
	for _, required := range []string{
		"item.userKey",
		"item.time",
		"rawLat.toFixed(6)",
		"rawLng.toFixed(6)",
	} {
		if !strings.Contains(stableKey, required) {
			t.Fatalf("history map stable marker key is missing %q", required)
		}
	}
	if strings.Contains(stableKey, "sourceIndex") {
		t.Fatal("history map marker deduplication still depends on unstable source array indexes")
	}
	if !strings.Contains(historyMapHTML, "const key = stableMarkerKey(item);") {
		t.Fatal("history map does not use the stable marker key when deduplicating")
	}
}

func TestHistoryMapInfoWindowUsesOnlyGPSAddressSource(t *testing.T) {
	for _, required := range []string{
		"address: firstText(gpsSource && gpsSource.address, gpsSource && gpsSource.detail),",
		"city: firstText(gpsSource && gpsSource.city),",
		"region: firstText(gpsSource && gpsSource.region),",
		"country: firstText(gpsSource && gpsSource.country),",
	} {
		if !strings.Contains(historyMapHTML, required) {
			t.Fatalf("history map GPS-only information priority is missing %q", required)
		}
	}
	start := strings.Index(historyMapHTML, "function normalizeRecord(record, index)")
	end := strings.Index(historyMapHTML, "function stableMarkerKey(item)")
	if start < 0 || end < 0 || start >= end {
		t.Fatal("history map record normalization block not found")
	}
	normalize := historyMapHTML[start:end]
	if strings.Contains(normalize, "diagnostics.preferred_address") || strings.Contains(normalize, "diagnostics.preferred_city") ||
		strings.Contains(normalize, "record.address,") || strings.Contains(normalize, "record.address)") ||
		strings.Contains(normalize, "record.location_address") {
		t.Fatal("history map information window still consumes a network-preferred address")
	}
}

func TestHistoryMapRawGPSCoordinatesIgnoreAddressOnlyPreferredSystem(t *testing.T) {
	start := strings.Index(historyMapHTML, "    function coordinateSystemFor(")
	end := strings.Index(historyMapHTML, "    function firstGpsSource(")
	if start < 0 || end <= start {
		t.Fatal("history map coordinate-system block not found")
	}
	conversion := historyMapHTML[start:end]
	if !strings.Contains(conversion, "meta.coordinate_system,\n          source && source.coordinate_system") {
		t.Fatal("history map does not prioritize raw location metadata and GPS source coordinates")
	}
	if strings.Contains(conversion, "preferred_coordinate_system") {
		t.Fatal("history map treated an address/IP preferred coordinate system as the raw GPS coordinate system")
	}
	if strings.Contains(conversion, "mock_provider") || !strings.Contains(conversion, "return 'wgs84';") {
		t.Fatal("history map must default unknown raw GPS coordinates to WGS84 without inferring a coordinate system from mock-provider state")
	}
}

func TestHistoryMapCarriesMergedStayFieldsToEveryMarker(t *testing.T) {
	for _, required := range []string{
		"record.first_reported_at",
		"record.last_reported_at",
		"record.stay_duration_seconds",
		"record.report_count",
		"function formatStayDuration(value)",
	} {
		if !strings.Contains(historyMapHTML, required) {
			t.Fatalf("history map merged stay rendering is missing %q", required)
		}
	}
}

func TestHistoryMapUsesStableMemberColorsAndDistinctHistoryPoints(t *testing.T) {
	for _, required := range []string{
		"function fallbackMemberColor(userKey, lightness)",
		"record.member_color",
		"record.history_point_color",
		"strokeColor: group[0].color",
		"strokeWeight: TRACK_STROKE_WIDTH",
		"const HISTORY_POINT_DIAMETER = TRACK_STROKE_WIDTH * 2;",
		"node.style.setProperty('--marker-color', latest ? color : historyPointColor);",
	} {
		if !strings.Contains(historyMapHTML, required) {
			t.Fatalf("history map stable member styling is missing %q", required)
		}
	}
	if strings.Contains(historyMapHTML, "colors[groupIndex % colors.length]") {
		t.Fatal("history map member colors still depend on group iteration order")
	}
}

func TestHistoryMapHistoryPointSelectsNativeDetail(t *testing.T) {
	for _, required := range []string{
		"record.history_selection_key",
		"record.history_page",
		"window.LocMapSelection.selectHistoryRecord(item.selectionKey, item.historyPage);",
	} {
		if !strings.Contains(historyMapHTML, required) {
			t.Fatalf("history map native detail selection is missing %q", required)
		}
	}
}
