package com.familylocation.client;

final class GuardianContinuousReportingPolicy {
    private GuardianContinuousReportingPolicy() {
    }

    static boolean resolve(
        boolean perGroupValuePresent,
        boolean perGroupValue,
        boolean currentGroup,
        boolean legacyValuePresent,
        boolean legacyValue,
        boolean sessionSnapshotValue
    ) {
        if (perGroupValuePresent) {
            return perGroupValue;
        }
        if (currentGroup && legacyValuePresent) {
            return legacyValue;
        }
        return sessionSnapshotValue;
    }
}
