package com.familylocation.client;

final class ReportAttemptGate {
    private long generation;
    private long activeToken;

    synchronized long begin() {
        generation += 1L;
        if (generation <= 0L) {
            generation = 1L;
        }
        activeToken = generation;
        return activeToken;
    }

    synchronized boolean isActive(long token) {
        return token > 0L && activeToken == token;
    }

    synchronized boolean finish(long token) {
        if (!isActive(token)) {
            return false;
        }
        activeToken = 0L;
        return true;
    }

    synchronized long activeToken() {
        return activeToken;
    }
}
