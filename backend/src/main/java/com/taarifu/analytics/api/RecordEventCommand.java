package com.taarifu.analytics.api;

import com.taarifu.analytics.domain.model.enums.AnalyticsChannel;
import com.taarifu.analytics.domain.model.enums.AnalyticsEventType;
import com.taarifu.analytics.domain.model.enums.AnalyticsRole;
import com.taarifu.analytics.domain.model.enums.AnalyticsTier;
import com.taarifu.analytics.domain.model.enums.BreachType;

import java.time.Instant;
import java.util.UUID;

/**
 * The published, in-process command another module passes to {@link AnalyticsApi#record} to record one
 * analytics fact (PRD Appendix E; M15; ADR-0013 — cross-module calls go only through the callee's
 * {@code api} package, using DTOs/enums/UUIDs, never entities).
 *
 * <p>Responsibility: the immutable contract of "what happened" carried across the module boundary. It
 * exposes <b>only this module's public enums and opaque {@code UUID}s</b> — a caller never touches an
 * {@code AnalyticsEvent} entity or repository. The {@code eventId} is the caller-supplied idempotency key
 * (one occurrence = one key, derived from the outbox event) so a replay is a no-op (Appendix E.0/E.3).</p>
 *
 * <p><b>🔒 No PII (Appendix E.4, binding):</b> {@code actorRef} MUST be a salted pseudonymous hash, never
 * the account UUID/name/phone/ID; {@code geoAreaId} MUST be ward-or-coarser; there is deliberately no field
 * here for free-text, GPS, or any identifier — the contract makes a PII leak <i>unrepresentable</i>.</p>
 *
 * @param eventId        caller-supplied globally-unique idempotency key (e.g. the outbox event id).
 * @param eventType      the kind of occurrence.
 * @param occurredAt     when it actually happened (UTC); the pipeline keys on this, not ingest time.
 * @param actorRef       pseudonymous salted-hash actor reference, or {@code null} for guest/pre-auth.
 * @param geoAreaId      ward-or-coarser geographic-area public id, or {@code null}.
 * @param categoryId     issue-category public id, or {@code null}.
 * @param tier           the actor's trust tier, or {@code null}.
 * @param channel        the origin channel, or {@code null}.
 * @param activeRole     the actor's active role/"hat", or {@code null}.
 * @param latencySeconds the headline latency measure in seconds (TTFR/TTR/answer-latency), or {@code null}.
 * @param breachType     the breached SLA clock on an escalation event, or {@code null}.
 * @param outcome        a controlled-vocabulary outcome/qualifier code (never PII), or {@code null}.
 */
public record RecordEventCommand(
        UUID eventId,
        AnalyticsEventType eventType,
        Instant occurredAt,
        String actorRef,
        UUID geoAreaId,
        UUID categoryId,
        AnalyticsTier tier,
        AnalyticsChannel channel,
        AnalyticsRole activeRole,
        Long latencySeconds,
        BreachType breachType,
        String outcome
) {
}
