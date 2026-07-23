package com.familylocation.client;

import java.util.ArrayList;
import java.util.List;

public final class LocationStayMergePolicyTest {
    private static final double METERS_PER_LONGITUDE_DEGREE_AT_EQUATOR = 111195.08023352182d;

    private LocationStayMergePolicyTest() {
    }

    public static void main(String[] args) {
        boundaryAndDuration();
        firstPointAnchorPreventsDrift();
        partitionsAndRevisitsStaySeparate();
        coordinateSystemsAndInvalidPointsStaySeparate();
        unreadablePointBreaksAnOtherwiseMergeableStay();
        plainAndP2PRecordsShareOneContinuousStay();
        unknownCoordinateSystemsStaySeparate();
        newestFirstInputUsesTheNewerSameSecondRecordAsLast();
    }

    private static void boundaryAndDuration() {
        List<LocationStayMergePolicy.Point> points = new ArrayList<>();
        points.add(point(0, "g:1", 0.0d, 0.001d, 1_000L));
        points.add(point(1, "g:1", 24.9d, 0.001d, 11_000L));
        points.add(point(2, "g:1", 25.0d, 0.001d, 21_000L));
        points.add(point(3, "g:1", 25.1d, 0.001d, 31_000L));
        List<LocationStayMergePolicy.Cluster> clusters = LocationStayMergePolicy.merge(points);
        assertEquals(2, clusters.size(), "boundary cluster count");
        LocationStayMergePolicy.Cluster merged = clusters.get(1);
        assertEquals(3, merged.reportCount(), "25m inclusive");
        assertEquals(20L, merged.durationSeconds(), "duration");
    }

    private static void firstPointAnchorPreventsDrift() {
        List<LocationStayMergePolicy.Point> points = new ArrayList<>();
        points.add(point(0, "g:1", 0.0d, 0.001d, 1_000L));
        points.add(point(1, "g:1", 20.0d, 0.001d, 2_000L));
        points.add(point(2, "g:1", 40.0d, 0.001d, 3_000L));
        List<LocationStayMergePolicy.Cluster> clusters = LocationStayMergePolicy.merge(points);
        assertEquals(2, clusters.size(), "anchor prevents transitive drift");
        assertEquals(2, clusters.get(1).reportCount(), "first anchor cluster");
    }

    private static void partitionsAndRevisitsStaySeparate() {
        List<LocationStayMergePolicy.Point> points = new ArrayList<>();
        points.add(point(0, "g:1", 0.0d, 0.001d, 1_000L));
        points.add(point(1, "g:2", 0.0d, 0.001d, 2_000L));
        points.add(point(2, "g:1", 100.0d, 0.001d, 3_000L));
        points.add(point(3, "g:1", 0.0d, 0.001d, 4_000L));
        List<LocationStayMergePolicy.Cluster> clusters = LocationStayMergePolicy.merge(points);
        assertEquals(4, clusters.size(), "partition and revisit isolation");
    }

    private static void coordinateSystemsAndInvalidPointsStaySeparate() {
        List<LocationStayMergePolicy.Point> points = new ArrayList<>();
        points.add(new LocationStayMergePolicy.Point(0, "group-a:user-1", "wgs84", 30.0d, 120.0d, 1_000L));
        points.add(new LocationStayMergePolicy.Point(1, "group-a:user-1", "gcj-02", 30.0d, 120.0d, 2_000L));
        points.add(new LocationStayMergePolicy.Point(2, "group-a:user-1", "", 30.0d, 120.0d, 3_000L));
        points.add(new LocationStayMergePolicy.Point(3, "group-a:user-1", "wgs84", 0.0d, 0.0d, 4_000L));
        assertEquals(4, LocationStayMergePolicy.merge(points).size(), "different, unknown, and invalid coordinates stay separate");
        assertEquals("gcj02", LocationStayMergePolicy.normalizeCoordinateSystem("GCJ-02"), "coordinate normalization");
    }

    private static void unreadablePointBreaksAnOtherwiseMergeableStay() {
        List<LocationStayMergePolicy.Point> points = new ArrayList<>();
        points.add(point(0, "group-a:user-1", 0.0d, 0.001d, 1_000L));
        points.add(new LocationStayMergePolicy.Point(1, "group-a:user-1", "", Double.NaN, Double.NaN, 2_000L));
        points.add(point(2, "group-a:user-1", 0.0d, 0.001d, 3_000L));
        assertEquals(3, LocationStayMergePolicy.merge(points).size(), "unreadable record is a hard stay boundary");
    }

    private static void plainAndP2PRecordsShareOneContinuousStay() {
        List<LocationStayMergePolicy.Point> points = new ArrayList<>();
        points.add(point(2, "group-a:user-1", 0.0d, 0.001d, 1_000L, "location", true));
        points.add(point(1, "group-a:user-1", 10.0d, 0.001d, 2_000L, "location", true));
        points.add(point(0, "group-a:user-1", 20.0d, 0.001d, 3_000L, "location", true));
        LocationStayMergePolicy.Cluster cluster = LocationStayMergePolicy.merge(points).get(0);
        assertEquals(1, LocationStayMergePolicy.merge(points).size(), "plain and decrypted P2P rows share one timeline");
        assertEquals(3, cluster.reportCount(), "mixed encryption-mode stay count");
    }

    private static void unknownCoordinateSystemsStaySeparate() {
        List<LocationStayMergePolicy.Point> points = new ArrayList<>();
        points.add(new LocationStayMergePolicy.Point(1, "group-a:user-1", "provider-x", 30.0d, 120.0d, 1_000L));
        points.add(new LocationStayMergePolicy.Point(0, "group-a:user-1", "provider-x", 30.0d, 120.0d, 2_000L));
        assertEquals(2, LocationStayMergePolicy.merge(points).size(), "unknown coordinate systems never merge");
        assertEquals("", LocationStayMergePolicy.normalizeCoordinateSystem("provider-x"), "unknown coordinate normalization");
    }

    private static void newestFirstInputUsesTheNewerSameSecondRecordAsLast() {
        List<LocationStayMergePolicy.Point> points = new ArrayList<>();
        points.add(point(0, "group-a:user-1", 1.0d, 0.001d, 1_000L));
        points.add(point(1, "group-a:user-1", 0.0d, 0.001d, 1_000L));
        LocationStayMergePolicy.Cluster cluster = LocationStayMergePolicy.merge(points).get(0);
        assertEquals(1, cluster.first().sourceIndex, "older same-second row is first");
        assertEquals(0, cluster.last().sourceIndex, "newer same-second row is last");
    }

    private static LocationStayMergePolicy.Point point(int index, String partition, double eastMeters, double latitude, long time) {
        return new LocationStayMergePolicy.Point(index, partition, "wgs84", latitude, eastMeters / METERS_PER_LONGITUDE_DEGREE_AT_EQUATOR, time);
    }

    private static LocationStayMergePolicy.Point point(
        int index,
        String partition,
        double eastMeters,
        double latitude,
        long time,
        String mergeClass,
        boolean mergeEligible
    ) {
        return new LocationStayMergePolicy.Point(
            index,
            partition,
            "wgs84",
            latitude,
            eastMeters / METERS_PER_LONGITUDE_DEGREE_AT_EQUATOR,
            time,
            -index,
            mergeClass,
            mergeEligible
        );
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
}
