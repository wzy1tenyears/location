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
    }

    private static void require(boolean value, String name) {
        if (!value) {
            throw new AssertionError(name);
        }
    }
}
