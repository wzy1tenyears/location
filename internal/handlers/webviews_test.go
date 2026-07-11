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
	if !strings.Contains(historyMapHTML, "`${serviceHost}/maps?v=2.0&key=${encodeURIComponent(AMAP_KEY)}`") {
		t.Fatal("history map template does not build the AMap script URL dynamically")
	}
}
