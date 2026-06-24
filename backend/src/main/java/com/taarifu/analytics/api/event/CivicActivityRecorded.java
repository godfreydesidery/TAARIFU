package com.taarifu.analytics.api.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published outbox payload: a single civic-activity fact a feature module emits for the analytics sink
 * (ADR-0013 §2 — analytics is event-driven via the outbox; ADR-0014 §1/§4; PRD Appendix E measurement plan).
 *
 * <p>Responsibility: the immutable, cross-module <b>async</b> contract carrying "a measurable civic thing
 * happened" from a producer (reporting / moderation / responders / engagement) to the analytics
 * {@code AnalyticsEventHandler}, which records one {@code AnalyticsEvent} from it. The producer appends this
 * to the transactional outbox <b>inside its own domain transaction</b> ({@code outboxWriter.append(...)}),
 * so the civic write and the analytics intent commit atomically and the analytics work runs
 * <b>asynchronously off the citizen's critical path</b> (Appendix E "never on the critical path"; PRD §15).</p>
 *
 * <p><b>WHY strings/UUIDs only (no analytics enums):</b> this record lives in {@code analytics.api.event} so a
 * sibling module may import it across the boundary (a sanctioned {@code ..api..} reference, ADR-0013 §3) — but
 * the analytics dimension <i>enums</i> live in {@code analytics.domain.model.enums}, which is off-limits across
 * the boundary. So every dimension that is an analytics enum (event type, tier, channel, role) is carried here
 * as its enum <b>name string</b>; the handler maps each back to the enum on the analytics side. A producer
 * therefore depends only on {@code analytics.api.event}, never on analytics' internals.</p>
 *
 * <p><b>🔒 No PII, ever (Appendix E.4, binding — §18/PDPA, ADR-0014 §1):</b> this payload carries only
 * pseudonymous/opaque values — a salted {@link #actorRef} hash (never the account UUID, name, phone, or ID),
 * ward-or-coarser {@link #geoAreaId}, an opaque {@link #categoryId}, controlled-vocabulary code strings, and
 * timestamps. There is deliberately no field for free text, a precise GPS point, or any direct identifier, so a
 * PII leak into the queryable/replayable outbox is unrepresentable.</p>
 *
 * @param analyticsEventType the analytics catalogue value (an {@code AnalyticsEventType} name — see
 *                           {@link AnalyticsEventTypes}); the kind of occurrence the dashboards count.
 * @param occurredAt         domain-time the thing actually happened (UTC); the analytics pipeline keys on this,
 *                           not ingest time (Appendix E.3).
 * @param actorRef           pseudonymous salted-hash actor reference, or {@code null} for guest/pre-auth/system.
 * @param geoAreaId          ward-or-coarser geographic-area public id, or {@code null}.
 * @param categoryId         issue-category public id, or {@code null}.
 * @param tier               the actor's trust-tier name ({@code T0}–{@code T3}), or {@code null}.
 * @param channel            the origin-channel name (e.g. {@code APP}, {@code USSD}), or {@code null}.
 * @param activeRole         the actor's active-role name (e.g. {@code CITIZEN}, {@code RESPONDER}), or {@code null}.
 * @param latencySeconds     a headline latency measure in seconds (TTFR/TTR), or {@code null}.
 * @param breachType         a breached-SLA-clock name ({@code TTFR}/{@code TTR}) on an escalation, or {@code null}.
 * @param outcome            a controlled-vocabulary outcome/qualifier code (never PII), or {@code null}.
 */
public record CivicActivityRecorded(
        String analyticsEventType,
        Instant occurredAt,
        String actorRef,
        UUID geoAreaId,
        UUID categoryId,
        String tier,
        String channel,
        String activeRole,
        Long latencySeconds,
        String breachType,
        String outcome
) {

    /**
     * Convenience factory for the common case — an event type, the area/category it concerns, the actor role,
     * and when it happened — leaving the optional pseudonymous/measure fields {@code null}. Keeps producer call
     * sites terse while the full canonical constructor remains available for richer facts (e.g. carrying a
     * latency or outcome).
     *
     * @param analyticsEventType the analytics catalogue value (see {@link AnalyticsEventTypes}).
     * @param geoAreaId          ward-or-coarser area public id, or {@code null}.
     * @param categoryId         issue-category public id, or {@code null}.
     * @param activeRole         the actor's active-role name, or {@code null}.
     * @param occurredAt         domain-time the thing happened (UTC).
     * @return a new payload with the optional actor-ref/tier/channel/measure fields {@code null}.
     */
    public static CivicActivityRecorded of(String analyticsEventType, UUID geoAreaId, UUID categoryId,
                                           String activeRole, Instant occurredAt) {
        return new CivicActivityRecorded(analyticsEventType, occurredAt, null, geoAreaId, categoryId,
                null, null, activeRole, null, null, null);
    }
}
