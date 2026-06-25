package com.taarifu.payments.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Externalised tunables for the scheduled top-up reconciliation job (ADR-0015 addendum; PRD §23.5).
 *
 * <p>Responsibility: binds {@code taarifu.payments.reconciliation.*}. The job re-checks stale non-terminal
 * top-ups against the provider (driving confirmed ones to SUCCEEDED) and expires intents that have been
 * stuck past {@link #expireAfter}. All values default so the job works with <b>no central configuration
 * change</b> (the CENTRAL-NEEDS rule) and can be turned off for a maintenance window or a test.</p>
 *
 * <p><b>Operability toggle:</b> {@link #enabled} (default {@code true}) gates the {@code @Scheduled} bean
 * exactly like the outbox relay's {@code taarifu.outbox.relay.enabled} — disabling removes only the poller,
 * never the correctness of the webhook-driven path. Tests that manipulate {@code top_up} rows directly turn
 * it off to keep the scheduler from racing them.</p>
 *
 * @param enabled        whether the scheduled reconciliation job runs (default {@code true}).
 * @param batchSize      max stale rows claimed + processed per tick (bounds per-instance work; default 50).
 * @param minAge         how old a non-terminal row must be before the job re-checks it — skips in-flight
 *                       attempts the citizen may still be approving (default 2m).
 * @param expireAfter    how long a non-terminal row may stay un-settled before the job expires it to FAILED
 *                       (default 30m). MUST be {@code >= minAge}.
 * @param pollInterval   the fixed delay between ticks (read on the {@code @Scheduled} annotation, mirrored
 *                       here for documentation/validation; default 5m).
 */
@ConfigurationProperties(prefix = "taarifu.payments.reconciliation")
public record PaymentsReconciliationProperties(
        Boolean enabled,
        Integer batchSize,
        Duration minAge,
        Duration expireAfter,
        Duration pollInterval
) {

    /**
     * Applies safe defaults so the job works out of the box with no central config, and clamps nonsensical
     * values (a non-positive batch size, an {@code expireAfter} shorter than {@code minAge}) to sane ones.
     */
    public PaymentsReconciliationProperties {
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
        if (batchSize == null || batchSize < 1) {
            batchSize = 50;
        }
        if (minAge == null || minAge.isNegative()) {
            minAge = Duration.ofMinutes(2);
        }
        if (expireAfter == null || expireAfter.compareTo(minAge) < 0) {
            // An expiry window shorter than the re-check age would expire rows the job has not even looked at
            // yet — clamp to at least minAge (default 30m otherwise).
            expireAfter = (expireAfter == null) ? Duration.ofMinutes(30) : minAge;
        }
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            pollInterval = Duration.ofMinutes(5);
        }
    }
}
