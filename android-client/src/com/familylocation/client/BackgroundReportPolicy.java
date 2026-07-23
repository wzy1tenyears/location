package com.familylocation.client;

final class BackgroundReportPolicy {
    static final long MIN_REPORT_INTERVAL_MS = 60_000L;
    static final long SETTINGS_REFRESH_INTERVAL_MS = 15 * 60_000L;
    static final long LOCATION_SAMPLE_TIMEOUT_MS = 20_000L;
    static final long LOCATION_RETRY_DELAY_MS = 30_000L;
    static final long MAX_RETRY_DELAY_MS = 5 * 60_000L;

    private BackgroundReportPolicy() {
    }

    static long reportIntervalMs(int seconds) {
        return Math.max(MIN_REPORT_INTERVAL_MS, seconds * 1000L);
    }

    static long retryDelayMs(int consecutiveFailures) {
        int exponent = Math.max(0, Math.min(5, consecutiveFailures - 1));
        return Math.min(MAX_RETRY_DELAY_MS, 15_000L << exponent);
    }

    static long nextRegularDelayMs(long now, long lastSuccessAt, long intervalMs) {
        if (lastSuccessAt <= 0) {
            return 0;
        }
        return Math.max(0, intervalMs - Math.max(0, now - lastSuccessAt));
    }

    static boolean locationIsFresh(long locationTime, long now, long intervalMs) {
        if (locationTime <= 0 || now < locationTime) {
            return true;
        }
        long maximumAge = Math.max(15 * 60_000L, intervalMs * 3L);
        return now - locationTime <= maximumAge;
    }
}
