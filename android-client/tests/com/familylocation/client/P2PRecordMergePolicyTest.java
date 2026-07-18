package com.familylocation.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class P2PRecordMergePolicyTest {
    private static final double METERS_PER_LONGITUDE_DEGREE_AT_EQUATOR = 111195.08023352182d;
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
        "first_reported_at",
        "last_reported_at",
        "stay_duration_seconds",
        "report_count",
        "encryption_mode",
        "encrypted_payload",
        "p2p_key_version",
        "p2p_decrypted",
        "encrypted_unreadable"
    };

    private P2PRecordMergePolicyTest() {
    }

    public static void main(String[] args) {
        locationPayloadFieldBoundary();
        plaintextAndReadableP2pAreMergeable();
        distanceBoundaryIsInclusive();
        firstPointAnchorPreventsDrift();
        interleavedCoordinateSystemsUseIndependentPartitions();
        partitionsAndRevisitsStaySeparate();
        plaintextFarPointBreaksP2pRevisit();
        invalidUnknownAndUnreadableRowsStaySingle();
        diagnosticSourceSelectionPrefersPrecisionThenNewest();
        coordinateSystemsAndInvalidPointsStaySeparate();
        newestClusterIsReturnedFirst();
        ascendingRawInputUsesHigherIdAsLatestTie();
        pageWindowAndMapLimits();
    }

    private static void locationPayloadFieldBoundary() {
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

    private static void plaintextAndReadableP2pAreMergeable() {
        assertTrue(P2PRecordMergePolicy.isMergeableSnapshotRecord("", false, false), "plaintext raw record");
        assertTrue(P2PRecordMergePolicy.isMergeableSnapshotRecord("p2p-v1", true, false), "readable decrypted P2P record");
        assertFalse(P2PRecordMergePolicy.isMergeableSnapshotRecord("p2p-v1", false, false), "encrypted P2P without decrypted payload");
        assertFalse(P2PRecordMergePolicy.isMergeableSnapshotRecord("p2p-v1", true, true), "unreadable encrypted record");
    }

    private static void distanceBoundaryIsInclusive() {
        List<P2PRecordMergePolicy.Point> points = new ArrayList<>();
        points.add(point(0, "group-a:user-1", 0.0d, 0.001d, 1_000L));
        points.add(point(1, "group-a:user-1", 24.9d, 0.001d, 11_000L));
        points.add(point(2, "group-a:user-1", 25.0d, 0.001d, 21_000L));
        points.add(point(3, "group-a:user-1", 25.1d, 0.001d, 31_000L));
        List<P2PRecordMergePolicy.Cluster> clusters = P2PRecordMergePolicy.merge(points);
        assertEquals(2, clusters.size(), "boundary cluster count");
        P2PRecordMergePolicy.Cluster merged = clusters.get(1);
        assertEquals(3, merged.reportCount(), "25m inclusive");
        assertEquals(20L, merged.durationSeconds(), "duration");
    }

    private static void firstPointAnchorPreventsDrift() {
        List<P2PRecordMergePolicy.Point> points = new ArrayList<>();
        points.add(point(0, "group-a:user-1", 0.0d, 0.001d, 1_000L));
        points.add(point(1, "group-a:user-1", 20.0d, 0.001d, 2_000L));
        points.add(point(2, "group-a:user-1", 40.0d, 0.001d, 3_000L));
        List<P2PRecordMergePolicy.Cluster> clusters = P2PRecordMergePolicy.merge(points);
        assertEquals(2, clusters.size(), "anchor prevents transitive drift");
        assertEquals(2, clusters.get(1).reportCount(), "first anchor cluster");
    }

    private static void interleavedCoordinateSystemsUseIndependentPartitions() {
        List<P2PRecordMergePolicy.Point> points = new ArrayList<>();
        points.add(new P2PRecordMergePolicy.Point(0, "group-a:user-1", "wgs84", 0.001d, 0.0d, 1_000L));
        points.add(new P2PRecordMergePolicy.Point(1, "group-a:user-1", "gcj02", 0.001d, 0.0d, 2_000L));
        points.add(new P2PRecordMergePolicy.Point(
            2,
            "group-a:user-1",
            "wgs84",
            0.001d,
            5.0d / METERS_PER_LONGITUDE_DEGREE_AT_EQUATOR,
            3_000L
        ));
        List<P2PRecordMergePolicy.Cluster> clusters = P2PRecordMergePolicy.merge(points);
        assertEquals(2, clusters.size(), "interleaved coordinate systems are independent partitions");
        assertEquals(2, clusters.get(0).reportCount(), "WGS points merge across an interleaved GCJ point");
        assertEquals(1, clusters.get(1).reportCount(), "GCJ point remains its own stay");
    }

    private static void partitionsAndRevisitsStaySeparate() {
        List<P2PRecordMergePolicy.Point> points = new ArrayList<>();
        points.add(point(0, "group-a:user-1", 0.0d, 0.001d, 1_000L));
        points.add(point(1, "group-a:user-2", 0.0d, 0.001d, 2_000L));
        points.add(point(2, "group-a:user-1", 100.0d, 0.001d, 3_000L));
        points.add(point(3, "group-a:user-1", 0.0d, 0.001d, 4_000L));
        assertEquals(4, P2PRecordMergePolicy.merge(points).size(), "partition and revisit isolation");
    }

    private static void plaintextFarPointBreaksP2pRevisit() {
        List<P2PRecordMergePolicy.Point> points = new ArrayList<>();
        points.add(point(0, "group-a:user-1", 0.0d, 0.001d, 1_000L));
        points.add(point(1, "group-a:user-1", 100.0d, 0.001d, 2_000L));
        points.add(point(2, "group-a:user-1", 0.0d, 0.001d, 3_000L));
        assertEquals(3, P2PRecordMergePolicy.merge(points).size(), "P2P A, plaintext B, P2P A remain separate stays");
    }

    private static void invalidUnknownAndUnreadableRowsStaySingle() {
        List<P2PRecordMergePolicy.Point> invalid = new ArrayList<>();
        invalid.add(point(0, "group-a:user-1", 0.0d, 0.001d, 1_000L));
        invalid.add(new P2PRecordMergePolicy.Point(1, "group-a:user-1", "wgs84", 0.0d, 0.0d, 2_000L));
        invalid.add(point(2, "group-a:user-1", 0.0d, 0.001d, 3_000L));
        assertEquals(3, P2PRecordMergePolicy.merge(invalid).size(), "invalid coordinate row is a singleton breaker");

        List<P2PRecordMergePolicy.Point> unknown = new ArrayList<>();
        unknown.add(point(0, "group-a:user-1", 0.0d, 0.001d, 1_000L));
        unknown.add(new P2PRecordMergePolicy.Point(1, "group-a:user-1", "custom-grid", 0.001d, 0.0d, 2_000L));
        unknown.add(point(2, "group-a:user-1", 0.0d, 0.001d, 3_000L));
        List<P2PRecordMergePolicy.Cluster> unknownClusters = P2PRecordMergePolicy.merge(unknown);
        assertEquals(2, unknownClusters.size(), "unknown coordinate system row stays single without breaking WGS");
        assertEquals(2, unknownClusters.get(0).reportCount(), "WGS points merge across an unknown-system row");
        assertEquals(1, unknownClusters.get(1).reportCount(), "unknown-system row remains single");

        List<P2PRecordMergePolicy.Point> unreadable = new ArrayList<>();
        unreadable.add(point(0, "group-a:user-1", 0.0d, 0.001d, 1_000L));
        unreadable.add(new P2PRecordMergePolicy.Point(1, "group-a:user-1", "wgs84", 0.001d, 0.0d, 2_000L, false));
        unreadable.add(point(2, "group-a:user-1", 0.0d, 0.001d, 3_000L));
        assertEquals(3, P2PRecordMergePolicy.merge(unreadable).size(), "unreadable P2P row is a singleton breaker");
    }

    private static void diagnosticSourceSelectionPrefersPrecisionThenNewest() {
        assertTrue(P2PRecordMergePolicy.shouldReplaceDiagnosticSource(4, 9, 5, 1), "higher precision wins even when older");
        assertTrue(P2PRecordMergePolicy.shouldReplaceDiagnosticSource(5, 1, 5, 2), "newer source wins equal precision");
        assertFalse(P2PRecordMergePolicy.shouldReplaceDiagnosticSource(5, 2, 5, 1), "older equal precision source stays rejected");
        assertFalse(P2PRecordMergePolicy.shouldReplaceDiagnosticSource(6, 1, 5, 9), "newer lower precision source stays rejected");
    }

    private static void coordinateSystemsAndInvalidPointsStaySeparate() {
        List<P2PRecordMergePolicy.Point> points = new ArrayList<>();
        points.add(new P2PRecordMergePolicy.Point(0, "group-a:user-1", "wgs84", 30.0d, 120.0d, 1_000L));
        points.add(new P2PRecordMergePolicy.Point(1, "group-a:user-1", "gcj-02", 30.0d, 120.0d, 2_000L));
        points.add(new P2PRecordMergePolicy.Point(2, "group-a:user-1", "", 30.0d, 120.0d, 3_000L));
        points.add(new P2PRecordMergePolicy.Point(3, "group-a:user-1", "wgs84", 0.0d, 0.0d, 4_000L));
        points.add(new P2PRecordMergePolicy.Point(4, "group-a:user-1", "custom-grid", 30.0d, 120.0d, 5_000L));
        points.add(new P2PRecordMergePolicy.Point(5, "group-a:user-1", "custom-grid", 30.0d, 120.000001d, 6_000L));
        assertEquals(6, P2PRecordMergePolicy.merge(points).size(), "different, missing, unknown, and invalid coordinates stay separate");
        assertEquals("gcj02", P2PRecordMergePolicy.normalizeCoordinateSystem("GCJ-02"), "coordinate normalization");
        assertEquals("", P2PRecordMergePolicy.normalizeCoordinateSystem("custom-grid"), "unknown coordinate systems fail closed");
    }

    private static void newestClusterIsReturnedFirst() {
        List<P2PRecordMergePolicy.Point> points = new ArrayList<>();
        points.add(point(0, "group-a:user-1", 0.0d, 0.001d, 1_000L));
        points.add(point(1, "group-a:user-1", 100.0d, 0.001d, 3_000L));
        List<P2PRecordMergePolicy.Cluster> clusters = P2PRecordMergePolicy.merge(points);
        assertEquals(1, clusters.get(0).last().sourceIndex, "newest first");
    }

    private static void ascendingRawInputUsesHigherIdAsLatestTie() {
        List<P2PRecordMergePolicy.Point> sameStay = new ArrayList<>();
        sameStay.add(point(0, "group-a:user-1", 0.0d, 0.001d, 1_000L));
        sameStay.add(point(1, "group-a:user-1", 1.0d, 0.001d, 1_000L));
        List<P2PRecordMergePolicy.Cluster> merged = P2PRecordMergePolicy.merge(sameStay);
        assertEquals(1, merged.get(0).last().sourceIndex, "higher id represents an equal-time stay");

        List<P2PRecordMergePolicy.Point> separateStays = new ArrayList<>();
        separateStays.add(point(0, "group-a:user-1", 0.0d, 0.001d, 1_000L));
        separateStays.add(point(1, "group-a:user-1", 100.0d, 0.001d, 1_000L));
        List<P2PRecordMergePolicy.Cluster> clusters = P2PRecordMergePolicy.merge(separateStays);
        assertEquals(1, clusters.get(0).last().sourceIndex, "higher id wins equal-time cluster order");
    }

    private static void pageWindowAndMapLimits() {
        P2PRecordMergePolicy.PageWindow window = P2PRecordMergePolicy.pageWindow(7, 9, 3);
        assertEquals(3, window.page(), "page clamped after merge");
        assertEquals(3, window.totalPages(), "total pages after merge");
        assertEquals(6, window.startIndex(), "last page start");
        assertEquals(7, window.endIndex(), "last page end");

        List<Integer> mapIndices = P2PRecordMergePolicy.mapIndices(
            Arrays.asList("group-a:user-1", "group-a:user-2", "group-a:user-1", "group-a:user-2", "group-a:user-1"),
            2
        );
        assertEquals(4, mapIndices.size(), "map per-member count");
        assertEquals(3, mapIndices.get(3), "map retains newest order while enforcing member limit");
    }

    private static P2PRecordMergePolicy.Point point(int index, String partition, double eastMeters, double latitude, long time) {
        return new P2PRecordMergePolicy.Point(
            index,
            partition,
            "wgs84",
            latitude,
            eastMeters / METERS_PER_LONGITUDE_DEGREE_AT_EQUATOR,
            time
        );
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

    private static void assertEquals(long expected, long actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", actual " + actual);
        }
    }

    private static void assertEquals(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", actual " + actual);
        }
    }

    private static void assertTrue(boolean value, String label) {
        if (!value) {
            throw new AssertionError("Expected true: " + label);
        }
    }

    private static void assertFalse(boolean value, String label) {
        if (value) {
            throw new AssertionError("Expected false: " + label);
        }
    }
}
