package com.familylocation.client;

public final class P2PRecordMergePolicyTest {
    private P2PRecordMergePolicyTest() {
    }

    public static void main(String[] args) {
        assertAllowed("latitude");
        assertAllowed("address_diagnostics");
        assertAllowed("device_report");
        assertAllowed("encrypted_at");

        assertRejected("id");
        assertRejected("user_id");
        assertRejected("member_id");
        assertRejected("group_id");
        assertRejected("group_name");
        assertRejected("username");
        assertRejected("display_name");
        assertRejected("role");
        assertRejected("role_label");
        assertRejected("created_at");
        assertRejected("encryption_mode");
        assertRejected("encrypted_payload");
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
