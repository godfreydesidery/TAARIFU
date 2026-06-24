package com.taarifu.identity.application.service;

import com.taarifu.analytics.api.event.AnalyticsEventTypes;
import com.taarifu.analytics.api.event.CivicActivityRecorded;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.common.outbox.OutboxWriter;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Identity's single emission point for the <b>verification-funnel</b> analytics facts (T0→T3) that power the
 * PRD §3.3 verification-funnel KPI (gap A1; PRD Appendix E; ADR-0013 §2 — analytics is event-driven via the
 * outbox; ADR-0014 §2/§4).
 *
 * <p>Responsibility: emit one {@link CivicActivityRecorded} fact to the transactional outbox in the
 * <b>caller's</b> transaction, at each real identity flow that moves a citizen up the funnel — account
 * signed up (T1), profile completed (T2), identity-verification started / succeeded / failed (T3). Every
 * call site funnels through here so the envelope shape (taxonomy key, aggregate type, the pseudonymised
 * dimensions) lives in exactly one place (DRY; CLAUDE.md §3) and a reviewer can confirm the no-PII rule on a
 * single class rather than at five scattered emit blocks.</p>
 *
 * <p><b>WHY off the citizen path:</b> the producer appends the event inside its own domain transaction and
 * returns; the analytics sink ({@code AnalyticsEventHandler}) records it <b>asynchronously</b> on the outbox
 * relay, so a slow or failed analytics write never rolls back signup/verification (Appendix E "never on the
 * critical path"; PRD §15). At-least-once delivery is idempotent on the outbox {@code public_id} in the
 * recorder, so a redelivered funnel fact never double-counts (ADR-0014 §3).</p>
 *
 * <p><b>🔒 No PII (PRD §18, PDPA, ADR-0014 §1):</b> these facts carry <b>only coarse dimensions</b> — the
 * trust-tier name and origin-channel name — and a deliberately {@code null} {@code actorRef} (the funnel KPI
 * counts pseudonymous volume per tier/channel, never a person). The account/profile public id, phone, name,
 * national/voter {@code idNo}, and the verification evidence are <b>never</b> placed on the event. Even the
 * outbox {@code aggregateId} is a fresh random UUID, not the subject account, so the queryable/replayable
 * outbox cannot be used to re-identify which citizen moved up the funnel.</p>
 */
@Service
public class IdentityFunnelAnalytics {

    /** The {@code aggregateType} stamped on every identity funnel outbox row (coarse, for replay/diagnostics). */
    private static final String AGGREGATE_TYPE = "IDENTITY_FUNNEL";

    /**
     * Origin-channel names carried on a funnel fact. These are the {@code AnalyticsChannel} enum <b>names</b>
     * held as plain strings — identity must NOT import {@code analytics.domain.model.enums} (a sibling
     * module's internal package is off-limits across the boundary, ADR-0013 §3); the analytics handler maps
     * the string back to its own enum. WHY constants and not literals at each call site: one declaration keeps
     * the channel vocabulary in step with the analytics catalogue (DRY) and prevents a typo at a producer.
     */
    public static final String CHANNEL_APP = "APP";

    /** The USSD feature-phone channel name (see {@link #CHANNEL_APP}). */
    public static final String CHANNEL_USSD = "USSD";

    /** The admin/console-originated channel name — moderator-driven verification decisions (see {@link #CHANNEL_APP}). */
    public static final String CHANNEL_ADMIN = "ADMIN";

    private final OutboxWriter outboxWriter;
    private final ClockPort clock;

    /**
     * @param outboxWriter the transactional-outbox port; each emit appends one PENDING row in the caller's
     *                     transaction (atomic with the identity write — ADR-0014 §2).
     * @param clock        injectable time source for {@code occurredAt} (testable; never inline {@code now()}).
     */
    public IdentityFunnelAnalytics(OutboxWriter outboxWriter, ClockPort clock) {
        this.outboxWriter = outboxWriter;
        this.clock = clock;
    }

    /**
     * Emits one funnel fact with the given analytics catalogue value, trust tier, and origin channel —
     * pseudonymised ({@code actorRef=null}, no geo/category), inside the caller's transaction.
     *
     * @param analyticsEventType the funnel catalogue value (an {@link AnalyticsEventTypes} constant).
     * @param tier               the trust-tier name reached/at the step ({@code T1}–{@code T3}), or {@code null}.
     * @param channel            the origin-channel name (e.g. {@code APP}, {@code USSD}, {@code ADMIN}), or
     *                           {@code null}.
     */
    public void emit(String analyticsEventType, String tier, String channel) {
        // Dimensions are coarse codes ONLY: tier + channel. No actorRef, no geoAreaId, no categoryId — the
        // funnel KPI is a pseudonymised per-tier/channel count, never a per-person trace (PRD §18).
        CivicActivityRecorded fact = new CivicActivityRecorded(
                analyticsEventType,
                clock.now(),
                null,     // actorRef: pseudonymised — the funnel counts volume, not a person
                null,     // geoAreaId: identity funnel is not geo-scoped
                null,     // categoryId: n/a
                tier,     // tier name (string — NOT the analytics enum; ADR-0013 §3)
                channel,  // channel name (string)
                null,     // activeRole: n/a (the actor is a citizen onboarding themselves)
                null,     // latencySeconds: n/a
                null,     // breachType: n/a
                null);    // outcome: n/a
        // aggregateId is a fresh UUID (not the subject account) so the outbox cannot re-identify the citizen.
        outboxWriter.append(EventEnvelope.of(
                AnalyticsEventTypes.CIVIC_ACTIVITY_RECORDED,
                AGGREGATE_TYPE,
                UUID.randomUUID(),
                fact,
                clock.now()));
    }
}
