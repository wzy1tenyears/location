package com.familylocation.client;

import java.util.concurrent.atomic.AtomicLong;

final class LatestRequestGate {
    private final AtomicLong generation = new AtomicLong();

    long begin() {
        return generation.incrementAndGet();
    }

    boolean isCurrent(long token) {
        return token > 0L && generation.get() == token;
    }

    void invalidate() {
        generation.incrementAndGet();
    }
}
