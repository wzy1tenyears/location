package com.familylocation.client;

import java.util.Arrays;
import java.util.List;

public final class LocationHistorySnapshotPolicyTest {
    private LocationHistorySnapshotPolicyTest() {
    }

    public static void main(String[] args) {
        pageWindowUsesMergedTotalAndClampsPage();
        mapLimitIsPerGroupAndMemberPartition();
    }

    private static void pageWindowUsesMergedTotalAndClampsPage() {
        LocationHistorySnapshotPolicy.PageWindow first = LocationHistorySnapshotPolicy.pageWindow(21, 2, 20);
        assertEquals(2, first.page(), "second page");
        assertEquals(2, first.totalPages(), "merged total pages");
        assertEquals(20, first.startIndex(), "second page start");
        assertEquals(21, first.endIndex(), "second page end");

        LocationHistorySnapshotPolicy.PageWindow clamped = LocationHistorySnapshotPolicy.pageWindow(3, 9, 20);
        assertEquals(1, clamped.page(), "page clamps after merging shrinks total");
        assertEquals(3, clamped.total(), "merged total");
        assertEquals(0, clamped.startIndex(), "clamped page start");
        assertEquals(3, clamped.endIndex(), "clamped page end");
    }

    private static void mapLimitIsPerGroupAndMemberPartition() {
        List<String> keys = Arrays.asList("g1:u1", "g1:u1", "g1:u2", "g1:u1", "g2:u1", "g1:u2");
        List<Integer> indices = LocationHistorySnapshotPolicy.mapIndices(keys, 2);
        assertEquals(5, indices.size(), "per-partition map size");
        assertEquals(0, indices.get(0), "first record");
        assertEquals(1, indices.get(1), "second same-member record");
        assertEquals(2, indices.get(2), "different member");
        assertEquals(4, indices.get(3), "different group");
        assertEquals(5, indices.get(4), "second different-member record");
    }

    private static void assertEquals(long expected, long actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", actual " + actual);
        }
    }
}
