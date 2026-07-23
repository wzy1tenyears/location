package com.familylocation.client;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class P2PRecordMergePolicy {
    private static final Set<String> LOCATION_PAYLOAD_FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "latitude",
        "longitude",
        "altitude",
        "accuracy",
        "heading",
        "speed",
        "location_provider",
        "location_time",
        "location_mock_provider",
        "location_coordinate_system",
        "vertical_accuracy",
        "bearing_accuracy",
        "speed_accuracy",
        "address_diagnostics",
        "device_report",
        "encrypted_at"
    )));

    private P2PRecordMergePolicy() {
    }

    static boolean isAllowedLocationPayloadField(String field) {
        return field != null && LOCATION_PAYLOAD_FIELDS.contains(field);
    }
}
