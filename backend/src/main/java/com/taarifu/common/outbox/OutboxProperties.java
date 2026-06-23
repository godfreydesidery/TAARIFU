package com.taarifu.common.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for the outbox relay (ADR-0014 §3), bound from the {@code taarifu.outbox.*} namespace.
 *
 * <p>Responsibility: hold the relay's batch size, retry cap, and backoff schedule with safe defaults so
 * the outbox works out of the box with <b>no central configuration change</b> — any override is purely
 * optional (a deployment may add {@code taarifu.outbox.*} to its environment/YAML). The poll interval
 * itself is read directly on the {@code @Scheduled} annotation
 * ({@code taarifu.outbox.poll-interval-ms:1000}) because annotation attributes must be constant
 * expressions and cannot reference this bean.</p>
 *
 * <p>WHY exponential backoff <b>with jitter</b>: a batch that fails on a shared downstream dependency
 * must not retry in lockstep (thundering herd) — jitter spreads the retries (PRD §21 DI3).</p>
 *
 * @param batchSize   max rows a single poll cycle claims and dispatches. Default 100 — bounded so one
 *                    cycle's work (and its row locks) stays small.
 * @param maxAttempts dispatch attempts before a row is moved to terminal {@link OutboxStatus#FAILED}
 *                    (the DLQ). Default 8 (ADR-0014 §3).
 * @param backoffBase the base delay for the exponential schedule {@code base * 2^attempts}. Default 2s.
 * @param backoffCap  the ceiling the exponential delay is clamped to. Default 5 min.
 */
@ConfigurationProperties(prefix = "taarifu.outbox")
public record OutboxProperties(
        Integer batchSize,
        Integer maxAttempts,
        Duration backoffBase,
        Duration backoffCap
) {

    /** Default batch size when {@code taarifu.outbox.batch-size} is unset. */
    private static final int DEFAULT_BATCH_SIZE = 100;
    /** Default attempt cap when {@code taarifu.outbox.max-attempts} is unset (ADR-0014 §3). */
    private static final int DEFAULT_MAX_ATTEMPTS = 8;
    /** Default exponential-backoff base when unset. */
    private static final Duration DEFAULT_BACKOFF_BASE = Duration.ofSeconds(2);
    /** Default exponential-backoff ceiling when unset. */
    private static final Duration DEFAULT_BACKOFF_CAP = Duration.ofMinutes(5);

    /**
     * Applies defaults to any unset (null) property so the relay always has a complete, valid config
     * without requiring a {@code taarifu.outbox.*} block to exist (KISS — works out of the box).
     */
    public OutboxProperties {
        batchSize = batchSize != null ? batchSize : DEFAULT_BATCH_SIZE;
        maxAttempts = maxAttempts != null ? maxAttempts : DEFAULT_MAX_ATTEMPTS;
        backoffBase = backoffBase != null ? backoffBase : DEFAULT_BACKOFF_BASE;
        backoffCap = backoffCap != null ? backoffCap : DEFAULT_BACKOFF_CAP;
    }

    /** @return a fully-defaulted instance (used when no {@code taarifu.outbox.*} config is bound). */
    public static OutboxProperties defaults() {
        return new OutboxProperties(null, null, null, null);
    }
}
