package com.taarifu.communications.application.service;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.communications.domain.model.enums.Channel;
import com.taarifu.communications.domain.model.enums.NotificationType;
import com.taarifu.communications.domain.model.enums.SubscriptionTargetType;
import com.taarifu.communications.domain.repository.AnnouncementRepository;
import com.taarifu.communications.domain.repository.SubscriptionRepository;
import com.taarifu.communications.infrastructure.config.DigestProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * The scheduled <b>area-activity digest</b>: a periodic (daily/weekly) summary of new activity in the
 * areas a citizen follows, delivered over the durable FEED channel (PRD §13 "digest", EI-6, M5).
 *
 * <p>Responsibility: once per configured cadence, for every profile that follows at least one area, count
 * the announcements published in that profile's followed areas during the window and — if there is any —
 * dispatch a single lean {@link NotificationType#DIGEST} notification through
 * {@link NotificationDispatchService}. The digest is a <b>nudge, not a re-send</b>: it carries a count, not
 * the items (the citizen taps through to the feed), keeping the cost-metered channels free of bulk content
 * (PRD §15 data budget, cost-awareness — SMS is never the digest's default channel).</p>
 *
 * <p><b>Channel: FEED only by default.</b> The digest requests only the durable in-app FEED channel, so a
 * citizen sees it when they next open the app and is never charged SMS or interrupted by a 7am push for a
 * convenience summary. A citizen who explicitly opts {@code DIGEST→PUSH}/{@code DIGEST→EMAIL} in their
 * preferences gets it there too — but the dispatcher's channel matrix governs that, not this job, which
 * only proposes FEED. {@code DIGEST} is <b>not</b> an always-on type, so a citizen can silence it entirely
 * (it is convenience, not security).</p>
 *
 * <p><b>Idempotency (DI4).</b> The dispatch row's idempotency key is
 * {@code DIGEST:FEED:<recipient>:<windowSourceId>}, where {@code windowSourceId} is a deterministic UUID
 * derived from the window's {@code until} instant. A re-run for the same window (a second instance, a
 * manual re-trigger, an overlapping schedule) recomputes the identical key, finds the existing row, and
 * does <b>not</b> double-send — exactly like every other dispatch on this path. Each new window yields a
 * fresh source id, so the next day's/week's digest is a distinct, deliverable notification.</p>
 *
 * <p><b>Operability toggle.</b> The whole bean is gated by {@code taarifu.communications.digest.enabled}
 * (default {@code false}) via {@link ConditionalOnProperty}: when unset/false the bean — and therefore its
 * {@code @Scheduled} method — does not exist, so it never runs in tests or in a context that should not own
 * the fan-out (the brief's "gate the scheduler so tests don't race it"). The cron, zone, and look-back come
 * from {@link DigestProperties}; scheduling itself is enabled centrally by the outbox config's
 * {@code @EnableScheduling} (this module adds none).</p>
 *
 * <p><b>🔒 Boundary/PII (ADR-0013, PRD §18).</b> The job reads only this module's own
 * {@code subscription}/{@code announcement} tables and dispatches by recipient {@code UUID}; it resolves no
 * contact itself (the dispatcher does, transiently, only if a contact-bearing channel fires — and FEED is
 * not one). Logs carry counts and the window only — never a profile's PII, never a token, never an address.
 * It never throws on a single recipient's failure: one bad recipient is logged and skipped so the run
 * completes for everyone else (EI degradation — degrade, never crash a fan-out).</p>
 *
 * <p><b>WHY synchronous-but-fail-soft, paged.</b> The follower population can be large, so the job streams
 * recipients in bounded pages ({@link DigestProperties#batchSize()}) rather than loading them all. Each
 * recipient's dispatch is wrapped so a per-recipient exception is isolated. This mirrors the dispatch
 * service's own fail-soft contract (DI3) and keeps the run's memory and lock footprint bounded
 * (ARCHITECTURE §8).</p>
 */
@Service
@ConditionalOnProperty(name = "taarifu.communications.digest.enabled", havingValue = "true")
public class DigestService {

    private static final Logger log = LoggerFactory.getLogger(DigestService.class);

    /** The digest proposes only the durable in-app channel; the dispatcher widens per the citizen's prefs. */
    private static final Set<Channel> DIGEST_CHANNELS = EnumSet.of(Channel.FEED);

    private final SubscriptionRepository subscriptionRepository;
    private final AnnouncementRepository announcementRepository;
    private final NotificationDispatchService dispatchService;
    private final DigestProperties properties;
    private final ClockPort clock;

    /**
     * @param subscriptionRepository follower/area resolution (this module's own table).
     * @param announcementRepository the window's per-area activity count (this module's own table).
     * @param dispatchService        preference-aware, idempotent per-recipient dispatch.
     * @param properties             the digest's cadence/window tunables (cron is on the annotation).
     * @param clock                  injectable "now" for the window boundary (testability).
     */
    public DigestService(SubscriptionRepository subscriptionRepository,
                         AnnouncementRepository announcementRepository,
                         NotificationDispatchService dispatchService,
                         DigestProperties properties,
                         ClockPort clock) {
        this.subscriptionRepository = subscriptionRepository;
        this.announcementRepository = announcementRepository;
        this.dispatchService = dispatchService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The scheduled entry point: runs the digest for the window ending now.
     *
     * <p>Driven by {@code @Scheduled(cron, zone)} from {@link DigestProperties} so a deployment chooses a
     * daily or weekly cadence in its local time without code change. Annotation attributes must be constant
     * expressions, so the cron/zone are SpEL placeholders read from the same {@code taarifu.communications
     * .digest.*} namespace the bound {@link DigestProperties} reads (the look-back/batch are read from the
     * bound record). Delegates to {@link #runDigest(Instant)} so a test can invoke the logic directly with
     * a fixed instant.</p>
     */
    @Scheduled(
            cron = "${taarifu.communications.digest.cron:0 0 7 * * *}",
            zone = "${taarifu.communications.digest.zone:Africa/Dar_es_Salaam}")
    public void runScheduledDigest() {
        runDigest(clock.now());
    }

    /**
     * Runs one digest pass for the window {@code (now - lookback, now]}: stream area-followers in bounded
     * pages, and for each one with in-window activity in their followed areas, dispatch a FEED digest.
     *
     * @param now the upper bound (inclusive) of the digest window — the run instant.
     * @return the number of digest notifications dispatched (recipients with activity) — useful to tests/metrics.
     */
    public int runDigest(Instant now) {
        Instant since = now.minus(properties.lookback());
        UUID windowSourceId = windowSourceId(now);
        int sent = 0;
        int page = 0;
        Page<UUID> slice;
        do {
            slice = subscriptionRepository.findDistinctFollowerProfileIds(
                    SubscriptionTargetType.AREA, PageRequest.of(page, properties.batchSize()));
            for (UUID recipient : slice.getContent()) {
                if (sendDigestIfActivity(recipient, since, now, windowSourceId)) {
                    sent++;
                }
            }
            page++;
        } while (slice.hasNext());
        log.info("Digest run complete: window=({}, {}], dispatched={} recipient(s)", since, now, sent);
        return sent;
    }

    /**
     * Computes one recipient's followed-area activity for the window and dispatches a digest iff there is
     * any. Per-recipient failures are isolated (logged + skipped) so one bad recipient never aborts the run.
     *
     * @return {@code true} iff a digest was dispatched for this recipient (had ≥1 in-window area update).
     */
    private boolean sendDigestIfActivity(UUID recipient, Instant since, Instant until, UUID windowSourceId) {
        try {
            Set<UUID> areaIds = subscriptionRepository.findFollowedAreaIds(recipient);
            if (areaIds.isEmpty()) {
                return false; // distinct query said AREA-follower, but a concurrent unfollow may have emptied it.
            }
            long count = announcementRepository.countAreaActivitySince(areaIds, since, until);
            if (count <= 0) {
                return false; // nothing new in this citizen's areas → no digest (no empty nudges).
            }
            dispatchOne(recipient, count, windowSourceId);
            return true;
        } catch (RuntimeException ex) {
            // Degrade, never crash a fan-out: isolate this recipient and keep the run going (EI degradation).
            // Redacted — recipient ref + reason class only, never PII (PRD §18).
            log.warn("Digest skipped for one recipient (continuing run): recipient={}, reason={}",
                    recipient, ex.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Dispatches one recipient's digest over FEED (idempotent on the window source id). The
     * {@code payloadRef} carries the activity count as a deep-link hint; the localised title/body are
     * rendered by the dispatch/notification template layer (ADR-0010) — this job passes {@code null} for
     * both, as the announcement fan-out does, since the i18n digest template is keyed off the
     * {@code DIGEST} type and the count ref.
     */
    private void dispatchOne(UUID recipient, long count, UUID windowSourceId) {
        // payloadRef = "digest:<count>": a non-PII, lean hint the client/template renders ("N updates").
        String payloadRef = "digest:" + count;
        dispatchService.dispatch(recipient, NotificationType.DIGEST, DIGEST_CHANNELS,
                payloadRef, windowSourceId, null, null);
    }

    /**
     * A deterministic per-window source id so the dispatch idempotency key is stable for a window and fresh
     * for the next one. Derived as a name-based (type-3) UUID from the window's {@code until} epoch-second,
     * so two runs for the same window collide (no double-send) while the next window yields a new id.
     *
     * @param until the window's inclusive upper bound (the run instant).
     * @return a stable UUID identifying this digest window as the dispatch {@code sourceId}.
     */
    private static UUID windowSourceId(Instant until) {
        return UUID.nameUUIDFromBytes(("digest-window:" + until.getEpochSecond())
                .getBytes(StandardCharsets.UTF_8));
    }
}
