package com.educore.lifecycle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** The system clock shifted by an offset that tests move forward ("time travel") and reset. */
final class MutableClock extends Clock {

    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    void advance(Duration duration) {
        offset.updateAndGet(current -> current.plus(duration));
    }

    void reset() {
        offset.set(Duration.ZERO);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("The test clock is always UTC");
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset.get());
    }
}
