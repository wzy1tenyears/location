package com.familylocation.client;

import java.util.Locale;

final class GroupCodePolicy {
    private static final String CURRENT_PATTERN = "^[0-9a-z]{8}$";
    private static final String LEGACY_PATTERN = "^[0-9a-f]{32}$";

    private GroupCodePolicy() {
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    static boolean isCurrent(String value) {
        return normalize(value).matches(CURRENT_PATTERN);
    }

    static boolean isLegacy(String value) {
        return normalize(value).matches(LEGACY_PATTERN);
    }

    static boolean isAcceptedExisting(String value) {
        return isCurrent(value) || isLegacy(value);
    }
}
