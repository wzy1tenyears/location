package com.familylocation.client;

public final class LocationCoordinateSystemPolicyTest {
    private LocationCoordinateSystemPolicyTest() {
    }

    public static void main(String[] args) {
        assertEquals("gcj02", LocationCoordinateSystemPolicy.resolve("gcj-02", "", "wgs84"), "top-level value");
        assertEquals("bd09", LocationCoordinateSystemPolicy.resolve("", "bd-09", "wgs84"), "metadata value");
        assertEquals("gcj02", LocationCoordinateSystemPolicy.resolve("", "", "gcj02"), "GPS source fallback");
        assertEquals("wgs84", LocationCoordinateSystemPolicy.resolve("", "", ""), "legacy GPS default");
        assertEquals("", LocationCoordinateSystemPolicy.resolve("provider-x", "", "wgs84"), "explicit unknown fails closed");
        assertEquals("", LocationCoordinateSystemPolicy.resolve("", "", "provider-x"), "unknown GPS source fails closed");
    }

    private static void assertEquals(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", actual " + actual);
        }
    }
}
