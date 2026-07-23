package com.familylocation.client;

public final class LatestRequestGateTest {
    private LatestRequestGateTest() {
    }

    public static void main(String[] args) {
        LatestRequestGate gate = new LatestRequestGate();
        long first = gate.begin();
        assertTrue(gate.isCurrent(first), "first request is current");

        long second = gate.begin();
        assertFalse(gate.isCurrent(first), "new request invalidates the old request");
        assertTrue(gate.isCurrent(second), "newest request remains current");

        gate.invalidate();
        assertFalse(gate.isCurrent(second), "screen change invalidates the current request");
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
