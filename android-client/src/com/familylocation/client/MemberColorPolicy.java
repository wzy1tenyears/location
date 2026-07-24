package com.familylocation.client;

import java.util.Locale;

final class MemberColorPolicy {
    private static final int HUE_MULTIPLIER = 137;
    private static final double SATURATION = 0.68d;
    private static final double TRACK_LIGHTNESS = 0.34d;
    private static final double HISTORY_POINT_LIGHTNESS = 0.23d;

    private MemberColorPolicy() {
    }

    static String stableKey(long userId, String username, String displayName) {
        if (userId > 0L) {
            return "user:" + userId;
        }
        String usernameValue = normalized(username);
        if (!usernameValue.isEmpty()) {
            return "username:" + usernameValue;
        }
        String displayNameValue = normalized(displayName);
        return displayNameValue.isEmpty() ? "member" : "display:" + displayNameValue;
    }

    static String trackColorHex(String stableKey) {
        return colorHex(stableKey, TRACK_LIGHTNESS);
    }

    static String historyPointColorHex(String stableKey) {
        return colorHex(stableKey, HISTORY_POINT_LIGHTNESS);
    }

    static int trackColorInt(String stableKey) {
        return colorInt(trackColorHex(stableKey));
    }

    private static String colorHex(String stableKey, double lightness) {
        int hue = Math.floorMod(mixedHash(stableKey) * HUE_MULTIPLIER, 360);
        double chroma = (1.0d - Math.abs(2.0d * lightness - 1.0d)) * SATURATION;
        double hueSector = hue / 60.0d;
        double secondary = chroma * (1.0d - Math.abs(hueSector % 2.0d - 1.0d));
        double red = 0.0d;
        double green = 0.0d;
        double blue = 0.0d;
        if (hueSector < 1.0d) {
            red = chroma;
            green = secondary;
        } else if (hueSector < 2.0d) {
            red = secondary;
            green = chroma;
        } else if (hueSector < 3.0d) {
            green = chroma;
            blue = secondary;
        } else if (hueSector < 4.0d) {
            green = secondary;
            blue = chroma;
        } else if (hueSector < 5.0d) {
            red = secondary;
            blue = chroma;
        } else {
            red = chroma;
            blue = secondary;
        }
        double match = lightness - chroma / 2.0d;
        int rgb = (channel(red + match) << 16) | (channel(green + match) << 8) | channel(blue + match);
        return String.format(Locale.ROOT, "#%06x", rgb);
    }

    private static int mixedHash(String value) {
        int hash = normalized(value).hashCode();
        return hash ^ (hash >>> 16);
    }

    private static int channel(double value) {
        return Math.max(0, Math.min(255, (int) Math.round(value * 255.0d)));
    }

    private static int colorInt(String hex) {
        return (int) (0xff000000L | Long.parseLong(hex.substring(1), 16));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
