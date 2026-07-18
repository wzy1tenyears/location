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

func TestHistoryMapRendersIPAndWebRTCSourcesWithoutAddingThemToGPSPaths(t *testing.T) {
	for _, required := range []string{
		"function normalizeDiagnosticSource(record, source, sourceIndex, recordIndex)",
		"if (type !== 'ip' && type !== 'webrtc') return null;",
		"const selected = bestDiagnosticSource(source, type);",
		"if (!selected) return null;",
		"const sources = Array.isArray(diagnostics.sources) ? diagnostics.sources : [];",
		"const diagnostic = normalizeDiagnosticSource(record, source, sourceIndex, index);",
		"if (diagnostic) items.push(diagnostic);",
		"if (item.type !== 'gps') return;",
	} {
		if !strings.Contains(historyMapHTML, required) {
			t.Fatalf("history map diagnostic marker handling is missing %q", required)
		}
	}
}

func TestHistoryMapDiagnosticAddressUsesMostPreciseAvailableText(t *testing.T) {
	for _, required := range []string{
		"const ip = firstText(source && source.ip, source && source.server_ip, source && source.ipv4, source && source.ipv6);",
		"if (!text || text === ip || parts.some((part) => part.includes(text))) return;",
		"if (text.includes(parts[index])) parts.splice(index, 1);",
		"source && source.district",
		"source && source.street",
		"source && source.address",
		"source && source.detail",
		"source && source.poi",
		"const address = parts.join(' ');",
		"const postalCode = firstText(source && source.postal_code);",
		"return address && postalCode && !address.includes(postalCode) ? `${address} ${postalCode}` : address;",
	} {
		if !strings.Contains(historyMapHTML, required) {
			t.Fatalf("history map diagnostic address handling is missing %q", required)
		}
	}
	if !strings.Contains(historyMapHTML, "normalizedCoordinateSystem(source && source.coordinate_system) || 'wgs84'") {
		t.Fatal("history map diagnostic coordinates must default to WGS84")
	}
}

func TestHistoryMapNestedDiagnosticsSelectMostPreciseEffectiveSource(t *testing.T) {
	start := strings.Index(historyMapHTML, "    function diagnosticAddressPrecision(")
	end := strings.Index(historyMapHTML, "    function normalizeDiagnosticSource(")
	if start < 0 || end <= start {
		t.Fatal("history map nested diagnostic selection helpers not found")
	}
	selection := historyMapHTML[start:end]
	for _, required := range []string{
		"const nestedField = type === 'ip' ? 'variants' : 'candidates';",
		"const nested = source && Array.isArray(source[nestedField]) ? source[nestedField] : [];",
		"const effective = inheritDiagnosticSource(source, candidate, type);",
		"effective.type = type;",
		"const inheritParent = candidate !== parent && diagnosticIdentitiesCompatible(parent, candidate);",
		"const coordinateSource = hasDiagnosticCoordinate(candidate)",
		": (inheritParent && hasDiagnosticCoordinate(parent) ? parent : null);",
		"effective.latitude = Number(coordinateSource.latitude);",
		"effective.longitude = Number(coordinateSource.longitude);",
		"const mayUseParentSystem = coordinateSource === parent || (inheritParent && sameDiagnosticCoordinate(parent, coordinateSource));",
		"mayUseParentSystem && parent && parent.coordinate_system",
		"address: diagnosticAddressPrecision(effective),",
		"ownCoordinate: hasDiagnosticCoordinate(candidate),",
		"candidate.address.score > selected.address.score",
		"candidate.address.populated > selected.address.populated",
		"candidate.accuracy < selected.accuracy",
	} {
		if !strings.Contains(selection, required) {
			t.Fatalf("history map nested diagnostic selection is missing %q", required)
		}
	}
	for _, required := range []string{
		"if (parentIdentity && nestedIdentity) return parentIdentity === nestedIdentity;",
		"if (!parentIdentity && nestedIdentity) return false;",
		"if (inheritParent) copyFields(parent);",
	} {
		if !strings.Contains(selection, required) {
			t.Fatalf("history map diagnostic identity isolation is missing %q", required)
		}
	}
	for _, inherited := range []string{"provider", "ip", "server_ip", "stun_server", "stun_label"} {
		if !strings.Contains(selection, "Object.keys(source).forEach((key) => {") {
			t.Fatal("history map diagnostic inheritance no longer copies parent fields")
		}
		if strings.Contains(selection, "key === '"+inherited+"'") {
			t.Fatalf("history map diagnostic inheritance unexpectedly excludes %q", inherited)
		}
	}
}

func TestHistoryMapUsesSemanticStableMarkerKeys(t *testing.T) {
	start := strings.Index(historyMapHTML, "    function stableMarkerKey(")
	end := strings.Index(historyMapHTML, "    function expandRecords(")
	if start < 0 || end <= start {
		t.Fatal("history map stable marker key helper not found")
	}
	stableKey := historyMapHTML[start:end]
	for _, required := range []string{
		"item.type",
		"item.userKey",
		"item.time",
		"rawLat.toFixed(6)",
		"rawLng.toFixed(6)",
		"firstText(item.ip, item.address, item.provider).toLowerCase()",
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

func TestHistoryMapGPSCityUsesPreciseDiagnosticsBeforeRecordFallbacks(t *testing.T) {
	required := "city: firstText(diagnostics.preferred_city, record.city, gpsSource && gpsSource.city),"
	if !strings.Contains(historyMapHTML, required) {
		t.Fatalf("history map GPS city priority is missing %q", required)
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

func TestHistoryMapDiagnosticCoordinateConversionMatchesAMap(t *testing.T) {
	start := strings.Index(historyMapHTML, "    function mapCoordinateForDiagnosticSource(")
	end := strings.Index(historyMapHTML, "    function sourceLabel(")
	if start < 0 || end <= start {
		t.Fatal("history map diagnostic coordinate conversion block not found")
	}
	conversion := historyMapHTML[start:end]
	for _, required := range []string{
		"normalizedCoordinateSystem(source && source.coordinate_system) || 'wgs84'",
		"if (system === 'bd09') return bd09ToGcj02(lng, lat);",
		"if (system === 'gcj02') return { lng, lat };",
		"return wgs84ToGcj02(lng, lat);",
	} {
		if !strings.Contains(conversion, required) {
			t.Fatalf("history map diagnostic coordinate conversion is missing %q", required)
		}
	}
}

func TestHistoryMapCarriesMergedStayFieldsToEveryMarker(t *testing.T) {
	for _, required := range []string{
		"record.first_reported_at",
		"record.last_reported_at",
		"record.stay_duration_seconds",
		"record.report_count",
		"record && record.first_reported_at",
		"record && record.last_reported_at",
		"record && record.stay_duration_seconds",
		"record && record.report_count",
		"function formatStayDuration(value)",
	} {
		if !strings.Contains(historyMapHTML, required) {
			t.Fatalf("history map merged stay rendering is missing %q", required)
		}
	}
}
