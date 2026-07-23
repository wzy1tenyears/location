package com.familylocation.client;

final class LocationCoordinateSystemPolicy {
    private LocationCoordinateSystemPolicy() {
    }

    static String resolve(String topLevel, String metadata, String gpsSource) {
        String explicit = first(topLevel, metadata);
        if (!explicit.isEmpty()) {
            return LocationStayMergePolicy.normalizeCoordinateSystem(explicit);
        }
        String gps = clean(gpsSource);
        if (!gps.isEmpty()) {
            return LocationStayMergePolicy.normalizeCoordinateSystem(gps);
        }
        return "wgs84";
    }

    private static String first(String first, String second) {
        String value = clean(first);
        return value.isEmpty() ? clean(second) : value;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
