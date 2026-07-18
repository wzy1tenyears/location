package com.familylocation.client;

public final class ReportAttemptGateTest {
    private ReportAttemptGateTest() {
    }

    public static void main(String[] args) {
        ReportAttemptGate gate = new ReportAttemptGate();
        long first = gate.begin();
        assertTrue(gate.isActive(first), "first active");
        long second = gate.begin();
        assertFalse(gate.isActive(first), "old token invalidated");
        assertFalse(gate.finish(first), "old finish ignored");
        assertTrue(gate.isActive(second), "second remains active");
        assertTrue(gate.finish(second), "active finish accepted");
        assertFalse(gate.finish(second), "finish is idempotent");
        assertTrue(gate.activeToken() == 0L, "no active token");
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
