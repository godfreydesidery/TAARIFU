package com.taarifu.communications.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Externalised, non-secret tunables for the scheduled <b>announcement-expiry sweep</b>
 * (PRD §12 lifecycle {@code DRAFT → SCHEDULED → PUBLISHED → EXPIRED}, ADR-0017 §1 "remove on expire"),
 * bound from {@code taarifu.communications.announcement-expiry.*}.
 *
 * <p>Responsibility: carry the expiry scheduler's <b>enable toggle</b>, its cron expression, the time zone
 * the cron is evaluated in, and a small <b>grace</b> applied to the {@code expireAt} cutoff. Safe defaults
 * mean the job needs <b>no central configuration change</b> to exist (the brief's CENTRAL-NEEDS rule) — only
 * {@code enabled=true} need ever be set to switch it on in the one deployment that should own the sweep. The
 * record holds only timing knobs; there are no credentials here (PRD §18, CLAUDE.md §12).</p>
 *
 * <p><b>WHY an explicit {@link #enabled} toggle defaulting to {@code false}</b> (the brief: "gated by an
 * enabled-toggle defaulting safe-for-tests, like the outbox relay"): the expiry sweep is a periodic
 * {@code @Scheduled} job that <b>mutates {@code announcement} rows</b> (PUBLISHED → EXPIRED) and removes their
 * search-index projection. Leaving it <b>off by default</b> (1) keeps the {@code @Scheduled} job from racing
 * the many tests that create/publish announcements and assert their state or feed presence (a sweep firing
 * mid-test could expire a row a test just published) — the bean is only created when {@code enabled=true},
 * exactly mirroring the {@code DigestService}/{@code OutboxRelay} {@code @ConditionalOnProperty} gating; and
 * (2) makes turning the sweep on an explicit operational decision per environment, so exactly one instance
 * owns it and two instances never double-sweep (the {@code expire()} transition is itself idempotent, but the
 * toggle avoids the wasted work). Default-OFF is the strictly test-safe choice; the outbox relay can default
 * ON only because polling an empty/own table is harmless, whereas this job writes shared announcement state.</p>
 *
 * @param enabled whether the scheduled expiry sweep is active. Default {@code false}.
 * @param cron    the Spring cron expression the sweep runs on. Default every 15 minutes. Six-field Spring
 *                cron (sec min hour day month weekday).
 * @param zone    the time zone the {@link #cron} is evaluated in. Default {@code Africa/Dar_es_Salaam} (EAT).
 * @param grace   a small grace subtracted from "now" before comparing to {@code expireAt}: the sweep only
 *                expires rows whose {@code expireAt <= now - grace}. Default {@code PT0S} (none).
 */
@ConfigurationProperties(prefix = "taarifu.communications.announcement-expiry")
public record AnnouncementExpiryProperties(
        Boolean enabled,
        String cron,
        String zone,
        Duration grace
) {

    /** Default cron: every 15 minutes (sec min hour day month weekday). */
    private static final String DEFAULT_CRON = "0 */15 * * * *";
    /** Default zone: East Africa Time, so a wall-clock cron's hour is local. */
    private static final String DEFAULT_ZONE = "Africa/Dar_es_Salaam";
    /** Default grace: none — the feed/detail queries enforce the window independently, so no lag is needed. */
    private static final Duration DEFAULT_GRACE = Duration.ZERO;

    /**
     * Applies safe defaults to any unset (null/blank/negative) property so the job has a complete, valid
     * config without requiring a {@code taarifu.communications.announcement-expiry.*} block to exist
     * (KISS — works out of the box; only {@code enabled=true} need ever be set to switch it on).
     */
    public AnnouncementExpiryProperties {
        enabled = enabled != null && enabled;
        cron = (cron == null || cron.isBlank()) ? DEFAULT_CRON : cron;
        zone = (zone == null || zone.isBlank()) ? DEFAULT_ZONE : zone;
        grace = (grace != null && !grace.isNegative()) ? grace : DEFAULT_GRACE;
    }
}
