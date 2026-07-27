package com.familylocation.client;

final class LocationFixPolicy {
    static final String TRUSTED_PROVIDER = "gps";
    static final long MAX_FIX_AGE_MS = 60_000L;
    static final long MAX_FUTURE_SKEW_MS = 15_000L;
    static final float MAX_ACCURACY_METERS = 100f;

    private LocationFixPolicy() {
    }

    static boolean isAcceptable(
        String provider,
        long locationTimeMs,
        long nowMs,
        boolean hasAccuracy,
        float accuracyMeters,
        boolean mockProvider
    ) {
        if (!TRUSTED_PROVIDER.equalsIgnoreCase(provider == null ? "" : provider.trim())) {
            return false;
        }
        if (mockProvider || !hasAccuracy || Float.isNaN(accuracyMeters) || Float.isInfinite(accuracyMeters)
            || accuracyMeters < 0f || accuracyMeters > MAX_ACCURACY_METERS) {
            return false;
        }
        if (locationTimeMs <= 0L || nowMs <= 0L) {
            return false;
        }
        long ageMs = nowMs - locationTimeMs;
        return ageMs <= MAX_FIX_AGE_MS && ageMs >= -MAX_FUTURE_SKEW_MS;
    }
}
