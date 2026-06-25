package com.taarifu.communications.application.service;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.communications.domain.model.Announcement;
import com.taarifu.communications.domain.model.enums.AnnouncementStatus;
import com.taarifu.communications.domain.repository.AnnouncementRepository;
import com.taarifu.communications.infrastructure.config.AnnouncementExpiryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The scheduled <b>announcement-expiry sweep</b>: periodically transitions {@code PUBLISHED} announcements
 * whose {@code expireAt} has passed to {@code EXPIRED}, which also <b>removes them from the search/discovery
 * index</b> (PRD §12 lifecycle, ADR-0017 §1 "remove on expire"; wave-3 wired the index removal on
 * {@code AnnouncementService.expire}).
 *
 * <p>Responsibility: drive the announcement lifecycle's time-based transition that nothing else drives. The
 * {@code Announcement.expire()} domain transition and the
 * {@link AnnouncementRepository#findDueForTransition expiry-sweep query} both already existed, and
 * {@link AnnouncementService#expire(UUID)} already routes through the single discovery-index fence (whose
 * non-{@code PUBLISHED} branch removes the projection) — but <b>no application path called them on a
 * schedule</b>. So an announcement that passed its {@code expireAt} stopped appearing in the feed (the feed
 * query filters on the window) yet lingered as a {@code PUBLISHED} row in {@code search_document},
 * discoverable forever. This job closes that gap: each run finds the due rows and expires each one, pulling
 * it out of discovery (the no-leak rule, PRD §18).</p>
 *
 * <p><b>WHY it delegates per-row to {@link AnnouncementService#expire(UUID)} (not a bulk UPDATE)</b>: that
 * method is the single, tested expiry path — it loads the row, applies the {@code expire()} domain
 * transition, saves it, and routes through the same {@code indexForDiscovery} fence that removes the
 * search-index projection. A bulk {@code UPDATE announcement SET status='EXPIRED'} would skip the domain
 * transition and, fatally, leave the now-stale {@code PUBLISHED} rows in the search index (the exact leak we
 * are closing). Going row-by-row keeps the index honest and is acceptable at this volume (expiries per sweep
 * are few); each call is its own short transaction, so one bad row never rolls back the whole sweep.</p>
 *
 * <p><b>Idempotency &amp; failure isolation (DI4, EI degradation).</b> {@link AnnouncementService#expire}
 * is idempotent — re-expiring an already-{@code EXPIRED} row is a no-op that still re-removes the (absent)
 * projection — so a re-delivered/overlapping sweep, or two instances racing, never errors or double-counts.
 * Each row's expiry is wrapped so a single row's failure is logged (by id only) and skipped, and the sweep
 * completes for the rest (degrade, never crash a maintenance run).</p>
 *
 * <p><b>Operability toggle (the brief: "gated by an enabled-toggle defaulting safe-for-tests, like the
 * outbox relay").</b> The whole bean is gated by {@code taarifu.communications.announcement-expiry.enabled}
 * (default {@code false}) via {@link ConditionalOnProperty}: when unset/false the bean — and therefore its
 * {@code @Scheduled} method — does not exist, so it never runs in tests or in a context that should not own
 * the sweep. This mirrors {@code DigestService}/{@code OutboxRelay} gating; default-OFF is the strictly
 * test-safe choice because this job <b>mutates shared announcement state</b> (unlike the relay, which only
 * polls its own table). The cron + zone come from {@link AnnouncementExpiryProperties}; scheduling itself is
 * enabled centrally by the outbox config's {@code @EnableScheduling} (this module adds none — DRY).</p>
 *
 * <p><b>🔒 Boundary/PII (ADR-0013, PRD §18).</b> The job reads only this module's own {@code announcement}
 * table and acts by public {@code UUID}; it resolves nothing from another module and logs counts + the
 * window only — never a title, body, author, or any PII. The index removal is an {@code api → api} call made
 * inside {@link AnnouncementService}, not here.</p>
 */
@Service
@ConditionalOnProperty(name = "taarifu.communications.announcement-expiry.enabled", havingValue = "true")
public class AnnouncementExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementExpiryScheduler.class);

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementService announcementService;
    private final AnnouncementExpiryProperties properties;
    private final ClockPort clock;

    /**
     * @param announcementRepository this module's announcement persistence (the due-rows query).
     * @param announcementService    the single, tested expiry path ({@code expire(publicId)}) that applies
     *                               the domain transition and removes the discovery-index projection.
     * @param properties             the sweep's enable-toggle (on the bean's {@code @ConditionalOnProperty}),
     *                               cron/zone (on the {@code @Scheduled}), and the {@code expireAt} grace.
     * @param clock                  injectable "now" for the cutoff (testability — a test drives
     *                               {@link #sweep(Instant)} with a fixed instant).
     */
    public AnnouncementExpiryScheduler(AnnouncementRepository announcementRepository,
                                       AnnouncementService announcementService,
                                       AnnouncementExpiryProperties properties,
                                       ClockPort clock) {
        this.announcementRepository = announcementRepository;
        this.announcementService = announcementService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The scheduled entry point: runs one expiry sweep for the cutoff "now − grace".
     *
     * <p>Driven by {@code @Scheduled(cron, zone)} from {@link AnnouncementExpiryProperties}. Annotation
     * attributes must be constant expressions, so the cron/zone are SpEL placeholders read from the same
     * {@code taarifu.communications.announcement-expiry.*} namespace the bound properties read. Delegates to
     * {@link #sweep(Instant)} so a test can invoke the logic directly with a fixed instant (no scheduler).</p>
     */
    @Scheduled(
            cron = "${taarifu.communications.announcement-expiry.cron:0 */15 * * * *}",
            zone = "${taarifu.communications.announcement-expiry.zone:Africa/Dar_es_Salaam}")
    public void runScheduledExpiry() {
        sweep(clock.now());
    }

    /**
     * Runs one expiry pass: find {@code PUBLISHED} announcements whose {@code expireAt <= now − grace} and
     * expire each one (transition to {@code EXPIRED} + remove its discovery-index projection). Per-row
     * failures are isolated so one bad row never aborts the run.
     *
     * @param now the run instant (the upper bound; the effective cutoff is {@code now − grace}).
     * @return the number of announcements expired this pass — useful to tests/metrics.
     */
    @Transactional(readOnly = true)
    public int sweep(Instant now) {
        Instant cutoff = now.minus(properties.grace());
        List<Announcement> due =
                announcementRepository.findDueForTransition(AnnouncementStatus.PUBLISHED, cutoff);
        int expired = 0;
        for (Announcement a : due) {
            if (expireOne(a.getPublicId())) {
                expired++;
            }
        }
        log.info("Announcement-expiry sweep complete: cutoff={}, due={}, expired={}",
                cutoff, due.size(), expired);
        return expired;
    }

    /**
     * Expires one announcement by public id, isolating any per-row failure so the sweep continues.
     *
     * @param publicId the due announcement's public id.
     * @return {@code true} iff the row was expired without error (a failure is logged + counted as not
     *         expired so the run is honest about partial progress).
     */
    private boolean expireOne(UUID publicId) {
        try {
            announcementService.expire(publicId);
            return true;
        } catch (RuntimeException ex) {
            // Degrade, never crash a maintenance run: isolate this row and keep sweeping (EI degradation).
            // Redacted — the announcement public id + reason class only, never a title/body/PII (PRD §18).
            log.warn("Announcement-expiry skipped for one row (continuing sweep): announcement={}, reason={}",
                    publicId, ex.getClass().getSimpleName());
            return false;
        }
    }
}
