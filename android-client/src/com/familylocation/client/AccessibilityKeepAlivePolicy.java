package com.familylocation.client;

final class AccessibilityKeepAlivePolicy {
    private static final String SERVICE_CLASS_NAME =
        "com.familylocation.client.KeepAliveAccessibilityService";
    private static final String SERVICE_SHORT_CLASS_NAME = ".KeepAliveAccessibilityService";

    private AccessibilityKeepAlivePolicy() {
    }

    static boolean isOwnServiceEnabled(String enabledServices, String packageName) {
        for (String serviceId : serviceIds(enabledServices)) {
            if (isOwnServiceId(serviceId, packageName)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasOtherEnabledService(String enabledServices, String packageName) {
        for (String serviceId : serviceIds(enabledServices)) {
            if (!isOwnServiceId(serviceId, packageName)) {
                return true;
            }
        }
        return false;
    }

    private static String[] serviceIds(String enabledServices) {
        String value = enabledServices == null ? "" : enabledServices.trim();
        return value.isEmpty() ? new String[0] : value.split(":");
    }

    private static boolean isOwnServiceId(String serviceId, String packageName) {
        String owner = packageName == null ? "" : packageName.trim();
        String value = serviceId == null ? "" : serviceId.trim();
        if (owner.isEmpty() || value.isEmpty()) {
            return false;
        }
        return value.equals(owner + "/" + SERVICE_CLASS_NAME)
            || value.equals(owner + "/" + SERVICE_SHORT_CLASS_NAME);
    }
}
