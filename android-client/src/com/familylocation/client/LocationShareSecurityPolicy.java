package com.familylocation.client;

final class LocationShareSecurityPolicy {
    private LocationShareSecurityPolicy() {
    }

    static boolean allowsPublicLink(String encryptionMode, boolean p2pDecrypted) {
        return allowsPublicLink(encryptionMode, p2pDecrypted, false);
    }

    static boolean allowsPublicLink(String encryptionMode, boolean p2pDecrypted, boolean containsP2P) {
        return !"p2p-v1".equals(encryptionMode) && !p2pDecrypted && !containsP2P;
    }

    static boolean accumulateContainsP2P(
        boolean alreadyContainsP2P,
        String encryptionMode,
        boolean p2pDecrypted,
        boolean containsP2P
    ) {
        return alreadyContainsP2P || !allowsPublicLink(encryptionMode, p2pDecrypted, containsP2P);
    }
}
