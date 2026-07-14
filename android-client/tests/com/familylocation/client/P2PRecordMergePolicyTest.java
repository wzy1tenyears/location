package com.familylocation.client;

public final class P2PRecordMergePolicyTest {
    private static final String[] ALLOWED_LOCATION_FIELDS = {
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
        "encrypted_at"
    };

    private static final String[] AUTHORITATIVE_OUTER_FIELDS = {
        "id",
        "user_id",
        "member_id",
        "group_id",
        "group_name",
        "username",
        "display_name",
        "role",
        "role_label",
        "created_at",
        "updated_at",
        "encryption_mode",
        "encrypted_payload",
        "p2p_key_version",
        "p2p_decrypted",
        "encrypted_unreadable"
    };

    private P2PRecordMergePolicyTest() {
    }

    public static void main(String[] args) {
        for (String field : ALLOWED_LOCATION_FIELDS) {
            assertAllowed(field);
        }
        for (String field : AUTHORITATIVE_OUTER_FIELDS) {
            assertRejected(field);
        }
        assertRejected(null);
        assertRejected("");
        assertRejected("Latitude");
    }

    private static void assertAllowed(String field) {
        if (!P2PRecordMergePolicy.isAllowedLocationPayloadField(field)) {
            throw new AssertionError("Expected allowed location field: " + field);
        }
    }

    private static void assertRejected(String field) {
        if (P2PRecordMergePolicy.isAllowedLocationPayloadField(field)) {
            throw new AssertionError("Expected authoritative outer field: " + field);
        }
    }
}
