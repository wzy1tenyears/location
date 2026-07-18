package com.familylocation.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class P2PRecordMergePolicy {
    static final double MAX_DISTANCE_METERS = 25.0d;
    private static final double EARTH_RADIUS_METERS = 6371008.8d;
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
        "encrypted_at"
    )));

    private P2PRecordMergePolicy() {
    }

    static final class Point {
        final int sourceIndex;
        final String partitionKey;
        final String coordinateSystem;
        final double latitude;
        final double longitude;
        final long reportedAtMillis;
        final boolean mergeable;

        Point(int sourceIndex, String partitionKey, String coordinateSystem, double latitude, double longitude, long reportedAtMillis) {
            this(sourceIndex, partitionKey, coordinateSystem, latitude, longitude, reportedAtMillis, true);
        }

        Point(int sourceIndex, String partitionKey, String coordinateSystem, double latitude, double longitude,
            long reportedAtMillis, boolean mergeable) {
            this.sourceIndex = sourceIndex;
            this.partitionKey = partitionKey == null ? "" : partitionKey;
            this.coordinateSystem = normalizeCoordinateSystem(coordinateSystem);
            this.latitude = latitude;
            this.longitude = longitude;
            this.reportedAtMillis = reportedAtMillis;
            this.mergeable = mergeable;
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

        long durationSeconds() {
            return Math.max(0L, (last().reportedAtMillis - first().reportedAtMillis) / 1000L);
        }
    }

    static final class PageWindow {
        private final int page;
        private final int perPage;
        private final int total;
        private final int totalPages;
        private final int startIndex;
        private final int endIndex;

        PageWindow(int page, int perPage, int total, int totalPages, int startIndex, int endIndex) {
            this.page = page;
            this.perPage = perPage;
            this.total = total;
            this.totalPages = totalPages;
            this.startIndex = startIndex;
            this.endIndex = endIndex;
        }

        int page() {
            return page;
        }

        int perPage() {
            return perPage;
        }

        int total() {
            return total;
        }

        int totalPages() {
            return totalPages;
        }

        int startIndex() {
            return startIndex;
        }

        int endIndex() {
            return endIndex;
        }
    }

    static boolean isAllowedLocationPayloadField(String field) {
        return field != null && LOCATION_PAYLOAD_FIELDS.contains(field);
    }

    static boolean isMergeableSnapshotRecord(String encryptionMode, boolean p2pDecrypted, boolean encryptedUnreadable) {
        if (encryptedUnreadable) {
            return false;
        }
        String normalizedMode = encryptionMode == null ? "" : encryptionMode.trim().toLowerCase(java.util.Locale.ROOT);
        return normalizedMode.isEmpty() || ("p2p-v1".equals(normalizedMode) && p2pDecrypted);
    }

    static boolean shouldReplaceDiagnosticSource(int currentScore, int currentSourceIndex,
        int candidateScore, int candidateSourceIndex) {
        return candidateScore > currentScore
            || (candidateScore == currentScore && candidateSourceIndex > currentSourceIndex);
    }

    static List<Cluster> merge(List<Point> input) {
        Map<String, List<Point>> partitions = new LinkedHashMap<>();
        if (input != null) {
            for (Point point : input) {
                if (isStructurallyValid(point)) {
                    partitions.computeIfAbsent(coordinatePartitionKey(point), ignored -> new ArrayList<>()).add(point);
                }
            }
        }

        Comparator<Point> ascending = Comparator
            .comparingLong((Point point) -> point.reportedAtMillis)
            .thenComparingInt(point -> point.sourceIndex);
        List<Cluster> clusters = new ArrayList<>();
        for (List<Point> partition : partitions.values()) {
            partition.sort(ascending);
            Cluster current = null;
            for (Point point : partition) {
                if (current == null || !canMerge(current.anchor, point)) {
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

    static PageWindow pageWindow(int total, int requestedPage, int requestedPerPage) {
        int safeTotal = Math.max(0, total);
        int safePerPage = Math.max(1, requestedPerPage);
        int totalPages = Math.max(1, (safeTotal + safePerPage - 1) / safePerPage);
        int page = Math.max(1, Math.min(requestedPage, totalPages));
        int startIndex = (int) Math.min((long) safeTotal, (long) (page - 1) * safePerPage);
        int endIndex = Math.min(safeTotal, startIndex + safePerPage);
        return new PageWindow(page, safePerPage, safeTotal, totalPages, startIndex, endIndex);
    }

    static List<Integer> mapIndices(List<String> partitionKeys, int perPartition) {
        List<Integer> indices = new ArrayList<>();
        if (partitionKeys == null || perPartition <= 0) {
            return indices;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int index = 0; index < partitionKeys.size(); index += 1) {
            String key = partitionKeys.get(index);
            if (key == null || key.trim().isEmpty()) {
                key = "record:" + index;
            }
            int count = counts.containsKey(key) ? counts.get(key) : 0;
            if (count >= perPartition) {
                continue;
            }
            counts.put(key, count + 1);
            indices.add(index);
        }
        return indices;
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
        if ("wgs84".equals(normalized) || "wgs-84".equals(normalized)) {
            return "wgs84";
        }
        if ("gcj02".equals(normalized) || "gcj-02".equals(normalized) || "gcj_02".equals(normalized)) {
            return "gcj02";
        }
        if ("bd09".equals(normalized) || "bd-09".equals(normalized) || "bd_09".equals(normalized)) {
            return "bd09";
        }
        return "";
    }

    private static boolean canMerge(Point anchor, Point point) {
        return anchor.mergeable
            && point.mergeable
            && hasMergeableCoordinates(anchor)
            && hasMergeableCoordinates(point)
            && !anchor.coordinateSystem.isEmpty()
            && anchor.coordinateSystem.equals(point.coordinateSystem)
            && distanceMeters(anchor, point) <= MAX_DISTANCE_METERS;
    }

    private static String coordinatePartitionKey(Point point) {
        return point.partitionKey + "\0" + point.coordinateSystem;
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
