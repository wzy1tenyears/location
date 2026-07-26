package com.familylocation.client;

public final class GuardianContinuousReportingPolicyTest {
    public static void main(String[] args) {
        require(
            GuardianContinuousReportingPolicy.resolve(true, true, true, true, false, false),
            "per-group enabled overrides stale disabled values"
        );
        require(
            !GuardianContinuousReportingPolicy.resolve(true, false, true, true, true, true),
            "per-group disabled overrides stale enabled values"
        );
        require(
            GuardianContinuousReportingPolicy.resolve(false, false, true, true, true, false),
            "legacy current-group value remains compatible"
        );
        require(
            GuardianContinuousReportingPolicy.resolve(false, false, false, true, false, true),
            "session snapshot remains a final compatibility fallback"
        );
        require(
            !GuardianContinuousReportingPolicy.resolve(false, false, false, false, false, false),
            "missing state stays disabled"
        );
    }

    private static void require(boolean value, String name) {
        if (!value) {
            throw new AssertionError(name);
        }
    }
}
