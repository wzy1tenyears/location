package com.familylocation.client;

import java.util.Locale;

final class DiagnosticSourcePolicy {
    private DiagnosticSourcePolicy() {
    }

    static boolean isNetworkProbeType(String type) {
        String normalized = normalize(type);
        return "ip".equals(normalized) || "webrtc".equals(normalized);
    }

    static String sourceMergeKey(
        String type,
        String ip,
        String serverIp,
        String ipv4,
        String ipv6,
        String provider,
        String source,
        String name,
        String stunServer,
        String coordinateIdentity,
        int fallbackIndex
    ) {
        String normalizedType = normalize(type);
        if (normalizedType.isEmpty()) {
            normalizedType = "source";
        }
        String networkIdentity = first(ip, serverIp, ipv4, ipv6);
        if ("webrtc".equals(normalizedType) && !networkIdentity.isEmpty()) {
            String stunIdentity = first(stunServer, source);
            return stunIdentity.isEmpty()
                ? stableKey(normalizedType, "network", networkIdentity)
                : stableKey(normalizedType, "network", networkIdentity, "stun", stunIdentity);
        }
        if ("ip".equals(normalizedType) && !networkIdentity.isEmpty()) {
            return stableKey(normalizedType, "network", networkIdentity);
        }
        if (hasAny(provider, source, name, stunServer)) {
            return stableKey(normalizedType, "origin", provider, source, name, stunServer);
        }
        if (!networkIdentity.isEmpty()) {
            return stableKey(normalizedType, "network", networkIdentity);
        }
        if (!normalize(coordinateIdentity).isEmpty()) {
            return stableKey(normalizedType, "coordinate", coordinateIdentity);
        }
        return stableKey(normalizedType, "fallback", String.valueOf(fallbackIndex));
    }

    static String evidenceKey(String... fields) {
        return stableKey(fields);
    }

    static boolean identitiesCompatible(
        String parentNetworkIdentity,
        String childNetworkIdentity,
        String parentStunIdentity,
        String childStunIdentity,
        boolean webRtc
    ) {
        String parentNetwork = normalize(parentNetworkIdentity);
        String childNetwork = normalize(childNetworkIdentity);
        if (!parentNetwork.isEmpty() && !childNetwork.isEmpty() && !parentNetwork.equals(childNetwork)) {
            return false;
        }
        if (parentNetwork.isEmpty() && !childNetwork.isEmpty()) {
            return false;
        }
        if (webRtc) {
            String parentStun = normalize(parentStunIdentity);
            String childStun = normalize(childStunIdentity);
            if (!childStun.isEmpty() && (parentStun.isEmpty() || !parentStun.equals(childStun))) {
                return false;
            }
        }
        return childNetwork.isEmpty() || !parentNetwork.isEmpty();
    }

    private static boolean hasAny(String... values) {
        for (String value : values) {
            if (!normalize(value).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static String first(String... values) {
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }
        return "";
    }

    private static String stableKey(String... values) {
        StringBuilder key = new StringBuilder();
        for (String value : values) {
            String normalized = normalize(value);
            key.append(normalized.length()).append(':').append(normalized).append('|');
        }
        return key.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
