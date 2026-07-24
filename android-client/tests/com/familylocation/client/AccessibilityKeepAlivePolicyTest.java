package com.familylocation.client;

public final class AccessibilityKeepAlivePolicyTest {
    public static void main(String[] args) {
        String packageName = "com.familylocation.client";
        String full = packageName + "/com.familylocation.client.KeepAliveAccessibilityService";
        String shortName = packageName + "/.KeepAliveAccessibilityService";
        String other = "example.reader/.ReaderAccessibilityService";

        require(!AccessibilityKeepAlivePolicy.isOwnServiceEnabled("", packageName), "blank list");
        require(AccessibilityKeepAlivePolicy.isOwnServiceEnabled(full, packageName), "full component");
        require(AccessibilityKeepAlivePolicy.isOwnServiceEnabled(shortName, packageName), "short component");
        require(
            AccessibilityKeepAlivePolicy.isOwnServiceEnabled(other + ":" + shortName, packageName),
            "mixed component list"
        );
        require(!AccessibilityKeepAlivePolicy.hasOtherEnabledService(shortName, packageName), "own-only risk");
        require(
            AccessibilityKeepAlivePolicy.hasOtherEnabledService(shortName + ":" + other, packageName),
            "third-party risk"
        );
        require(AccessibilityKeepAlivePolicy.hasOtherEnabledService("malformed", packageName), "malformed risk");
        require(
            !AccessibilityKeepAlivePolicy.usesForegroundNotification(true),
            "accessibility mode has no foreground notification"
        );
        require(
            AccessibilityKeepAlivePolicy.usesForegroundNotification(false),
            "notification mode uses foreground notification"
        );
        require(
            AccessibilityKeepAlivePolicy.notificationPermissionSatisfied(true, false),
            "accessibility mode does not require notification permission"
        );
        require(
            !AccessibilityKeepAlivePolicy.notificationPermissionSatisfied(false, false),
            "notification mode requires notification permission"
        );
    }

    private static void require(boolean value, String name) {
        if (!value) {
            throw new AssertionError(name);
        }
    }
}
