package com.familylocation.client;

public final class MemberColorPolicyTest {
    public static void main(String[] args) {
        String firstKey = MemberColorPolicy.stableKey(101L, "same", "same");
        String secondKey = MemberColorPolicy.stableKey(102L, "same", "same");

        assertEquals("user:101", firstKey, "positive user IDs must be the stable identity");
        assertNotEquals(
            MemberColorPolicy.trackColorHex(firstKey),
            MemberColorPolicy.trackColorHex(secondKey),
            "different users should receive different track colors"
        );
        assertEquals(
            MemberColorPolicy.trackColorHex(firstKey),
            MemberColorPolicy.trackColorHex(firstKey),
            "the same user color must remain deterministic"
        );
        assertNotEquals(
            MemberColorPolicy.trackColorHex(firstKey),
            MemberColorPolicy.historyPointColorHex(firstKey),
            "history points should use a deeper shade than the track"
        );
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertNotEquals(Object left, Object right, String message) {
        if (left == null ? right == null : left.equals(right)) {
            throw new AssertionError(message + ": both=" + left);
        }
    }
}
