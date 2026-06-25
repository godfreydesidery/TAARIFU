package com.taarifu.communications.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Externalised, non-secret tunables for the scheduled area-activity {@code DIGEST}
 * (PRD §13 "digest", EI-6), bound from {@code taarifu.communications.digest.*}.
 *
 * <p>Responsibility: carry the digest scheduler's <b>enable toggle</b>, its cron expression, and the
 * look-back window, with safe defaults so the digest works out of the box with <b>no central
 * configuration change</b> (the brief's CENTRAL-NEEDS rule). The whole record holds only timing knobs —
 * there are no credentials here (the digest sends through the same in-module channel ports, which carry
 * their own env-provided secrets; PRD §18, CLAUDE.md §12).</p>
 *
 * <p><b>WHY an explicit {@link #enabled} toggle defaulting to {@code false}</b>: the digest is a periodic
 * {@code @Scheduled} fan-out. Leaving it <b>off by default</b> (1) keeps the {@code @Scheduled} job from
 * racing tests that drive the dispatch path directly or manipulate {@code notification} rows (the brief's
 * "gate the scheduler with an enabled-toggle so tests don't race it" requirement — the bean is only
 * created when {@code enabled=true}, mirroring the outbox relay's {@code @ConditionalOnProperty} pattern),
 * and (2) makes turning the digest on an explicit operational decision per environment (it is enabled in
 * the deployment that should own the fan-out, exactly one, so two instances never double-send — the
 * dispatch idempotency key is the hard backstop, but the toggle avoids the wasted work).</p>
 *
 * @param enabled    whether the scheduled digest job is active. Default {@code false} — the
 *                   {@code @ConditionalOnProperty} on the job bean keeps it out of every context (tests
 *                   included) until a deployment opts in with {@code taarifu.communications.digest.enabled=true}.
 * @param cron       the Spring cron expression the digest runs on. Default {@code 0 0 7 * * *} — 07:00 daily
 *                   (East Africa Time via {@link #zone}); set a weekly cron (e.g. {@code 0 0 7 * * MON}) for
 *                   a weekly digest. Six-field Spring cron (sec min hour day month weekday).
 * @param zone       the time zone the {@link #cron} is evaluated in. Default {@code Africa/Dar_es_Salaam}
 *                   so "07:00" is local morning for the citizen, not UTC.
 * @param lookback   how far back the run summarises when it cannot derive a precise previous-run boundary.
 *                   Default {@code P1D} (24h) for the daily cadence; widen to {@code P7D} for a weekly cron.
 *                   The window counts announcements with {@code now-lookback < publishAt <= now}.
 * @param batchSize  how many recipient profiles the job loads per page as it streams the follower population
 *                   (bounded so a citywide digest never loads every follower into one heap). Default 500.
 */
@ConfigurationProperties(prefix = "taarifu.communications.digest")
public record DigestProperties(
        Boolean enabled,
        String cron,
        String zone,
        Duration lookback,
        Integer batchSize
) {

    /** Default cron: 07:00 every day (local). */
    private static final String DEFAULT_CRON = "0 0 7 * * *";
    /** Default zone: East Africa Time, so the cron's hour is local morning (EAT). */
    private static final String DEFAULT_ZONE = "Africa/Dar_es_Salaam";
    /** Default look-back window: one day (matches the daily default cron). */
    private static final Duration DEFAULT_LOOKBACK = Duration.ofDays(1);
    /** Default recipient page size as the job streams the follower population. */
    private static final int DEFAULT_BATCH_SIZE = 500;

    /**
     * Applies safe defaults to any unset (null/blank) property so the job has a complete, valid config
     * without requiring a {@code taarifu.communications.digest.*} block to exist (KISS — works out of the
     * box; only {@code enabled=true} need ever be set to switch it on).
     */
    public DigestProperties {
        enabled = enabled != null && enabled;
        cron = (cron == null || cron.isBlank()) ? DEFAULT_CRON : cron;
        zone = (zone == null || zone.isBlank()) ? DEFAULT_ZONE : zone;
        lookback = lookback != null ? lookback : DEFAULT_LOOKBACK;
        batchSize = (batchSize != null && batchSize > 0) ? batchSize : DEFAULT_BATCH_SIZE;
    }
}
