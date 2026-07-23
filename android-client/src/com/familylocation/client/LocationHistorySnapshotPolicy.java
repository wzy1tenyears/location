package com.familylocation.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LocationHistorySnapshotPolicy {
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

    private LocationHistorySnapshotPolicy() {
    }

    static PageWindow pageWindow(int total, int requestedPage, int requestedPerPage) {
        int safeTotal = Math.max(0, total);
        int perPage = Math.max(1, requestedPerPage);
        int totalPages = Math.max(1, (safeTotal + perPage - 1) / perPage);
        int page = Math.max(1, Math.min(requestedPage, totalPages));
        int startIndex = Math.min(safeTotal, (page - 1) * perPage);
        int endIndex = Math.min(safeTotal, startIndex + perPage);
        return new PageWindow(page, perPage, safeTotal, totalPages, startIndex, endIndex);
    }

    static List<Integer> mapIndices(List<String> orderedPartitionKeys, int perPartitionLimit) {
        List<Integer> indices = new ArrayList<>();
        if (orderedPartitionKeys == null || perPartitionLimit <= 0) {
            return indices;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int index = 0; index < orderedPartitionKeys.size(); index += 1) {
            String key = orderedPartitionKeys.get(index);
            String partitionKey = key == null || key.isEmpty() ? "record:" + index : key;
            int count = counts.containsKey(partitionKey) ? counts.get(partitionKey) : 0;
            if (count >= perPartitionLimit) {
                continue;
            }
            counts.put(partitionKey, count + 1);
            indices.add(index);
        }
        return indices;
    }
}
