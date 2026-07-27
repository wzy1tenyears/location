package com.familylocation.client;

public final class LocationFixPolicyTest {
    public static void main(String[] args) {
        long now = 1_000_000L;
        assertAccepted("fresh GPS", "gps", now - 5_000L, now, true, 12f, false);
        assertRejected("network provider", "network", now - 1_000L, now, true, 5f, false);
        assertRejected("passive provider", "passive", now - 1_000L, now, true, 5f, false);
        assertRejected("fused provider", "fused", now - 1_000L, now, true, 5f, false);
        assertRejected("mock GPS", "gps", now - 1_000L, now, true, 5f, true);
        assertRejected("stale GPS", "gps", now - LocationFixPolicy.MAX_FIX_AGE_MS - 1L, now, true, 5f, false);
        assertRejected("future GPS", "gps", now + LocationFixPolicy.MAX_FUTURE_SKEW_MS + 1L, now, true, 5f, false);
        assertRejected("missing accuracy", "gps", now - 1_000L, now, false, 0f, false);
        assertRejected("poor accuracy", "gps", now - 1_000L, now, true, LocationFixPolicy.MAX_ACCURACY_METERS + 1f, false);
    }

    private static void assertAccepted(String name, String provider, long fixTime, long now, boolean hasAccuracy, float accuracy, boolean mock) {
        if (!LocationFixPolicy.isAcceptable(provider, fixTime, now, hasAccuracy, accuracy, mock)) {
            throw new AssertionError(name + " should be accepted");
        }
    }

    private static void assertRejected(String name, String provider, long fixTime, long now, boolean hasAccuracy, float accuracy, boolean mock) {
        if (LocationFixPolicy.isAcceptable(provider, fixTime, now, hasAccuracy, accuracy, mock)) {
            throw new AssertionError(name + " should be rejected");
        }
    }
}
