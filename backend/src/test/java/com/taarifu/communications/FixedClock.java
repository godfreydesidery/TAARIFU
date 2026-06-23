package com.taarifu.communications;

import com.taarifu.common.domain.port.ClockPort;

import java.time.Instant;

/**
 * A fixed {@link ClockPort} for deterministic communications unit tests (CLAUDE.md §10).
 *
 * <p>Responsibility: returns a controllable instant so quiet-hours, scheduling, and send-timestamp logic
 * is asserted at a chosen moment without sleeping or flaking — the same testability rationale as the
 * production {@code ClockPort} (ARCHITECTURE §3.3).</p>
 */
public final class FixedClock implements ClockPort {

    private Instant now;

    /** @param now the instant this clock reports until changed. */
    public FixedClock(Instant now) {
        this.now = now;
    }

    /** @param now the new instant to report. */
    public void set(Instant now) {
        this.now = now;
    }

    /** {@inheritDoc} */
    @Override
    public Instant now() {
        return now;
    }
}
