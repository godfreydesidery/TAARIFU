package com.taarifu.common.domain.port;

import java.time.Instant;

/**
 * Outbound port abstracting "now" so time-dependent domain logic is testable and deterministic
 * (ARCHITECTURE.md §3.3, FOUNDATION-SCOPE.md §3).
 *
 * <p>Responsibility: any code that must read the current instant — e.g. effective-dated
 * {@code WardConstituency} resolution ("the mapping effective at date D"), OTP/refresh-token
 * expiry, SLA clocks — depends on this interface rather than calling {@link Instant#now()}
 * directly. Tests inject a fixed-clock adapter to assert behaviour at chosen instants without
 * sleeping or flaking.</p>
 *
 * <p>WHY a port (not {@code java.time.Clock} passed around): keeping it in {@code domain.port}
 * follows the ports-and-adapters rule uniformly (ADR-0004) and gives one injection point the whole
 * codebase shares (DRY).</p>
 */
public interface ClockPort {

    /**
     * @return the current instant in UTC. The production adapter returns {@link Instant#now()};
     *         test adapters return a fixed/controlled value.
     */
    Instant now();
}
