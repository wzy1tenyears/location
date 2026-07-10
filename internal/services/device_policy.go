package services

import (
	"fmt"
	"strings"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/models"
)

func EnforceDeviceReportPolicy(user models.User, report map[string]any, settings map[string]bool) error {
	if user.DebugMode || !securityPolicyEnabled(settings) {
		return nil
	}
	if len(report) == 0 {
		return httpx.Forbidden("缺少设备环境数据，已拒绝请求。")
	}
	violations := DeviceReportPolicyViolations(report, settings)
	if len(violations) == 0 {
		return nil
	}
	return httpx.Forbidden(fmt.Sprintf("当前设备环境不符合后台安全策略：%s。", strings.Join(violations, "、")))
}

func DeviceReportPolicyViolations(report map[string]any, settings map[string]bool) []string {
	violations := make([]string, 0)
	if settings["ban_root_users"] && truthy(report["root_detected"]) {
		violations = append(violations, "Root")
	}
	if settings["ban_adb_users"] && truthy(report["adb_enabled"]) {
		violations = append(violations, "ADB")
	}
	if settings["ban_fake_location_users"] && (truthy(report["mock_location_risk"]) || truthy(report["fake_location_detected"])) {
		violations = append(violations, "模拟定位")
	}
	if settings["ban_accessibility_users"] && (truthy(report["accessibility_risk"]) || truthy(report["accessibility_services"])) {
		violations = append(violations, "无障碍风险服务")
	}
	if settings["ban_packet_capture_users"] && hasPacketCaptureRisk(report) {
		violations = append(violations, "抓包工具")
	}
	if settings["ban_suspicious_packages_users"] && hasSuspiciousPackages(report) {
		violations = append(violations, "可疑包名")
	}
	return violations
}

func securityPolicyEnabled(settings map[string]bool) bool {
	for _, enabled := range settings {
		if enabled {
			return true
		}
	}
	return false
}

func hasSuspiciousPackages(report map[string]any) bool {
	packages, ok := report["suspicious_packages"].([]any)
	return ok && len(packages) > 0
}

func hasPacketCaptureRisk(report map[string]any) bool {
	if truthy(report["reqable_detected"]) {
		return true
	}
	packages, ok := report["suspicious_packages"].([]any)
	if !ok {
		return false
	}
	for _, item := range packages {
		name := strings.ToLower(fmt.Sprint(item))
		if strings.Contains(name, "reqable") || strings.Contains(name, "httpcanary") || strings.Contains(name, "charles") {
			return true
		}
	}
	return false
}

func truthy(value any) bool {
	switch typed := value.(type) {
	case bool:
		return typed
	case string:
		switch strings.ToLower(strings.TrimSpace(typed)) {
		case "1", "true", "yes", "on":
			return true
		}
	case float64:
		return typed != 0
	case int:
		return typed != 0
	}
	return false
}
