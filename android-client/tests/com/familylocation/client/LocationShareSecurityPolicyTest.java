package com.familylocation.client;

public final class LocationShareSecurityPolicyTest {
    private LocationShareSecurityPolicyTest() {
    }

    public static void main(String[] args) {
        assertAllowed("", false);
        assertAllowed("plain", false);
        assertRejected("p2p-v1", false);
        assertRejected("p2p-v1", true);
        assertRejected("", true);
        assertRejected("plain", false, true);

        boolean plainThenP2P = false;
        plainThenP2P = LocationShareSecurityPolicy.accumulateContainsP2P(plainThenP2P, "plain", false, false);
        plainThenP2P = LocationShareSecurityPolicy.accumulateContainsP2P(plainThenP2P, "p2p-v1", true, false);
        assertTrue(plainThenP2P, "plain then P2P aggregate must fail closed");

        boolean p2pThenPlain = false;
        p2pThenPlain = LocationShareSecurityPolicy.accumulateContainsP2P(p2pThenPlain, "p2p-v1", true, false);
        p2pThenPlain = LocationShareSecurityPolicy.accumulateContainsP2P(p2pThenPlain, "plain", false, false);
        assertTrue(p2pThenPlain, "P2P then plain aggregate must remain fail closed");
    }

    private static void assertAllowed(String mode, boolean decrypted) {
        if (!LocationShareSecurityPolicy.allowsPublicLink(mode, decrypted)) {
            throw new AssertionError("Expected ordinary server-backed location to be link-shareable.");
        }
    }

    private static void assertRejected(String mode, boolean decrypted) {
        assertRejected(mode, decrypted, false);
    }

    private static void assertRejected(String mode, boolean decrypted, boolean containsP2P) {
        if (LocationShareSecurityPolicy.allowsPublicLink(mode, decrypted, containsP2P)) {
            throw new AssertionError("Expected P2P location to be rejected from public link sharing.");
        }
    }

    private static void assertTrue(boolean value, String label) {
        if (!value) {
            throw new AssertionError(label);
        }
    }
}
