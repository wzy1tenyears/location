package com.familylocation.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LocationStayMergePolicy {
    static final double MAX_DISTANCE_METERS = 25.0d;
    private static final double EARTH_RADIUS_METERS = 6371008.8d;

    static final class Point {
        final int sourceIndex;
        final String partitionKey;
        final String coordinateSystem;
        final String mergeClass;
        final boolean mergeEligible;
        final double latitude;
        final double longitude;
        final long reportedAtMillis;
        final long orderKey;

        Point(int sourceIndex, String partitionKey, String coordinateSystem, double latitude, double longitude, long reportedAtMillis) {
            this(sourceIndex, partitionKey, coordinateSystem, latitude, longitude, reportedAtMillis, -sourceIndex, "default", true);
        }

        Point(
            int sourceIndex,
            String partitionKey,
            String coordinateSystem,
            double latitude,
            double longitude,
            long reportedAtMillis,
            long orderKey,
            String mergeClass,
            boolean mergeEligible
        ) {
            this.sourceIndex = sourceIndex;
            this.partitionKey = partitionKey == null ? "" : partitionKey;
            this.coordinateSystem = normalizeCoordinateSystem(coordinateSystem);
            this.latitude = latitude;
            this.longitude = longitude;
            this.reportedAtMillis = reportedAtMillis;
            this.orderKey = orderKey;
            this.mergeClass = mergeClass == null ? "" : mergeClass;
            this.mergeEligible = mergeEligible;
        }
    }

    static final class Cluster {
        private final List<Point> points = new ArrayList<>();
        private final Point anchor;

        Cluster(Point anchor) {
            this.anchor = anchor;
            points.add(anchor);
        }

        void add(Point point) {
            points.add(point);
        }

        Point anchor() {
            return anchor;
        }

        Point first() {
            return points.get(0);
        }

        Point last() {
            return points.get(points.size() - 1);
        }

        List<Point> points() {
            return Collections.unmodifiableList(points);
        }

        int reportCount() {
            return points.size();
        }

        boolean mergeEligible() {
            return anchor.mergeEligible;
        }

        long durationSeconds() {
            return Math.max(0L, (last().reportedAtMillis - first().reportedAtMillis) / 1000L);
        }
    }

    private LocationStayMergePolicy() {
    }

    static List<Cluster> merge(List<Point> input) {
        Map<String, List<Point>> partitions = new LinkedHashMap<>();
        if (input != null) {
            for (Point point : input) {
                if (!isStructurallyValid(point)) {
                    continue;
                }
                partitions.computeIfAbsent(point.partitionKey, ignored -> new ArrayList<>()).add(point);
            }
        }

        List<Cluster> clusters = new ArrayList<>();
        Comparator<Point> ascending = Comparator
            .comparingLong((Point point) -> point.reportedAtMillis)
            .thenComparingLong(point -> point.orderKey)
            .thenComparing((left, right) -> Integer.compare(right.sourceIndex, left.sourceIndex));
        for (List<Point> partition : partitions.values()) {
            partition.sort(ascending);
            Cluster current = null;
            for (Point point : partition) {
                if (current == null || !canMerge(current.anchor(), point)) {
                    current = new Cluster(point);
                    clusters.add(current);
                } else {
                    current.add(point);
                }
            }
        }
        clusters.sort((left, right) -> {
            int timeOrder = Long.compare(right.last().reportedAtMillis, left.last().reportedAtMillis);
            return timeOrder != 0 ? timeOrder : Integer.compare(right.last().sourceIndex, left.last().sourceIndex);
        });
        return clusters;
    }

    static double distanceMeters(Point left, Point right) {
        double latitudeDelta = Math.toRadians(right.latitude - left.latitude);
        double longitudeDelta = Math.toRadians(right.longitude - left.longitude);
        double leftLatitude = Math.toRadians(left.latitude);
        double rightLatitude = Math.toRadians(right.latitude);
        double haversine = Math.sin(latitudeDelta / 2.0d) * Math.sin(latitudeDelta / 2.0d)
            + Math.cos(leftLatitude) * Math.cos(rightLatitude)
            * Math.sin(longitudeDelta / 2.0d) * Math.sin(longitudeDelta / 2.0d);
        return 2.0d * EARTH_RADIUS_METERS * Math.asin(Math.min(1.0d, Math.sqrt(haversine)));
    }

    static String normalizeCoordinateSystem(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if ("wgs-84".equals(normalized)) {
            return "wgs84";
        }
        if ("gcj-02".equals(normalized) || "gcj_02".equals(normalized)) {
            return "gcj02";
        }
        if ("bd-09".equals(normalized) || "bd_09".equals(normalized)) {
            return "bd09";
        }
        if ("wgs84".equals(normalized) || "gcj02".equals(normalized) || "bd09".equals(normalized)) {
            return normalized;
        }
        return "";
    }

    private static boolean canMerge(Point anchor, Point point) {
        return anchor.mergeEligible
            && point.mergeEligible
            && !anchor.mergeClass.isEmpty()
            && anchor.mergeClass.equals(point.mergeClass)
            && hasMergeableCoordinates(anchor)
            && hasMergeableCoordinates(point)
            && !anchor.coordinateSystem.isEmpty()
            && anchor.coordinateSystem.equals(point.coordinateSystem)
            && distanceMeters(anchor, point) <= MAX_DISTANCE_METERS;
    }

    private static boolean isStructurallyValid(Point point) {
        return point != null && !point.partitionKey.isEmpty() && point.reportedAtMillis > 0L;
    }

    private static boolean hasMergeableCoordinates(Point point) {
        return point != null
            && Double.isFinite(point.latitude)
            && Double.isFinite(point.longitude)
            && point.latitude >= -90.0d
            && point.latitude <= 90.0d
            && point.longitude >= -180.0d
            && point.longitude <= 180.0d
            && !(point.latitude == 0.0d && point.longitude == 0.0d);
    }
}
