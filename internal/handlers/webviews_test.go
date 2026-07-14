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
