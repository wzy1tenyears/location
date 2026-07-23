package com.familylocation.client;

public final class GroupCodePolicyTest {
    private GroupCodePolicyTest() {
    }

    public static void main(String[] args) {
        assertTrue(GroupCodePolicy.isCurrent("a1b2c3d4"), "8-character lowercase alphanumeric code");
        assertTrue(GroupCodePolicy.isCurrent(" A1B2C3D4 "), "normalization");
        assertFalse(GroupCodePolicy.isCurrent("a1b2c3d"), "short code");
        assertFalse(GroupCodePolicy.isCurrent("a1b2-c3d"), "punctuation");
        assertTrue(GroupCodePolicy.isLegacy("0123456789abcdef0123456789abcdef"), "legacy code");
        assertTrue(GroupCodePolicy.isAcceptedExisting("0123456789abcdef0123456789abcdef"), "legacy join compatibility");
    }

    private static void assertTrue(boolean value, String label) {
        if (!value) {
            throw new AssertionError("Expected true: " + label);
        }
    }

    private static void assertFalse(boolean value, String label) {
        if (value) {
            throw new AssertionError("Expected false: " + label);
        }
    }
}
