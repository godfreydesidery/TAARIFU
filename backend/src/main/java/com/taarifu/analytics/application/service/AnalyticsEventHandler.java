package com.taarifu.analytics.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.analytics.api.AnalyticsApi;
import com.taarifu.analytics.api.RecordEventCommand;
import com.taarifu.analytics.api.event.AnalyticsEventTypes;
import com.taarifu.analytics.api.event.CivicActivityRecorded;
import com.taarifu.analytics.domain.model.enums.AnalyticsChannel;
import com.taarifu.analytics.domain.model.enums.AnalyticsEventType;
import com.taarifu.analytics.domain.model.enums.AnalyticsRole;
import com.taarifu.analytics.domain.model.enums.AnalyticsTier;
import com.taarifu.analytics.domain.model.enums.BreachType;
import com.taarifu.common.outbox.DomainEventHandler;
import com.taarifu.common.outbox.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * The analytics module's outbox handler — the sink that turns every {@link CivicActivityRecorded} outbox
 * event emitted by a feature module into one durable {@code AnalyticsEvent} via the public
 * {@link AnalyticsApi} (ADR-0013 §2 "analytics is event-driven"; ADR-0014 §4; PRD Appendix E; M15).
 *
 * <p>Responsibility: register for the single {@link AnalyticsEventTypes#CIVIC_ACTIVITY_RECORDED} taxonomy key,
 * deserialise the payload the relay delivers, translate its string-coded dimensions (event type, tier,
 * channel, role, breach) into this module's own enums, and record the fact through {@link AnalyticsApi}. This
 * is the consumer half of the emission seam: producers (reporting / moderation / responders / engagement)
 * append the event in their own transaction off the citizen path; this handler runs <b>asynchronously</b> on
 * the outbox relay thread, so a slow or failed analytics write never touches the citizen's request (Appendix
 * E "never on the critical path"; PRD §15).</p>
 *
 * <h3>Idempotency (at-least-once delivery — ADR-0014 §3)</h3>
 * The relay can deliver the same {@code eventId} more than once. This handler passes
 * {@code event.eventId()} (== the outbox row's {@code public_id}) straight through as the
 * {@link RecordEventCommand#eventId()} idempotency key; the {@code AnalyticsRecordingService} is idempotent on
 * it (a duplicate is a no-op via the {@code uq_analytics_event_event_id} unique index — Appendix E.0/E.3). So a
 * redelivery records the fact exactly once, no double-count.
 *
 * <p><b>🔒 No PII (PRD §18, ADR-0014 §1):</b> the payload carries only pseudonymous/opaque values; this handler
 * reads no identifier and logs none. An <b>unknown</b> string-coded value (a catalogue value this build does
 * not yet know — forward compatibility, Appendix E.0) is treated as a <b>no-op success</b>: the event is
 * dropped rather than failed, so a newer producer can emit a value an older analytics build ignores without
 * pushing the row to the DLQ.</p>
 */
@Component
public class AnalyticsEventHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsEventHandler.class);

    private final AnalyticsApi analyticsApi;
    private final ObjectMapper objectMapper;

    /**
     * @param analyticsApi the public recording port (idempotent on {@code eventId}) — the only analytics
     *                     surface this handler uses; it records the fact and returns.
     * @param objectMapper the shared Jackson mapper — deserialises the relay's {@code JsonNode} payload into
     *                     the public {@link CivicActivityRecorded} record (ids/codes only).
     */
    public AnalyticsEventHandler(AnalyticsApi analyticsApi, ObjectMapper objectMapper) {
        this.analyticsApi = analyticsApi;
        this.objectMapper = objectMapper;
    }

    /** {@inheritDoc} Registers for the single analytics {@code CIVIC_ACTIVITY_RECORDED} taxonomy key. */
    @Override
    public Set<String> handledEventTypes() {
        return Set.of(AnalyticsEventTypes.CIVIC_ACTIVITY_RECORDED);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Records the civic-activity fact idempotently. An unknown {@code analyticsEventType} string is a no-op
     * (forward compatibility — see the class Javadoc); any genuine deserialisation defect surfaces as an
     * exception so the relay records the failure rather than silently dropping a malformed event.</p>
     */
    @Override
    public void handle(EventEnvelope<?> event) {
        CivicActivityRecorded fact = deserialise(event.payload());

        AnalyticsEventType type = parseEventType(fact.analyticsEventType());
        if (type == null) {
            // Forward-compatible no-op: a catalogue value this build does not know yet (Appendix E.0 additive).
            log.debug("Unknown analyticsEventType '{}' (eventId={}); dropping as no-op (forward-compatible)",
                    fact.analyticsEventType(), event.eventId());
            return;
        }

        // Idempotency key = the outbox public_id (== EventEnvelope.eventId); the recorder dedups on it.
        Instant occurredAt = fact.occurredAt() != null ? fact.occurredAt() : event.occurredAt();
        RecordEventCommand command = new RecordEventCommand(
                event.eventId(),
                type,
                occurredAt,
                fact.actorRef(),
                fact.geoAreaId(),
                fact.categoryId(),
                parseTier(fact.tier()),
                parseChannel(fact.channel()),
                parseRole(fact.activeRole()),
                fact.latencySeconds(),
                parseBreach(fact.breachType()),
                fact.outcome());

        boolean recorded = analyticsApi.record(command);
        log.debug("CIVIC_ACTIVITY_RECORDED eventId={} type={} recorded={} (idempotent)",
                event.eventId(), type, recorded);
    }

    /**
     * Deserialises the relay-delivered payload (a Jackson {@link JsonNode} tree — the relay is payload-agnostic,
     * ADR-0014 §3) into the public {@link CivicActivityRecorded} record. The record carries ids/codes only; no
     * PII can appear here by the event contract (PRD §18).
     *
     * @param payload the envelope payload as delivered (a {@code JsonNode} from the persisted {@code jsonb}).
     * @return the typed {@link CivicActivityRecorded} record.
     * @throws IllegalStateException if the payload cannot be read as {@link CivicActivityRecorded} (a
     *                               non-retryable data defect — surfaced so the relay records the failure
     *                               rather than silently dropping).
     */
    private CivicActivityRecorded deserialise(Object payload) {
        try {
            if (payload instanceof JsonNode node) {
                return objectMapper.treeToValue(node, CivicActivityRecorded.class);
            }
            // Defensive: an in-process producer test may hand the concrete record straight through.
            return objectMapper.convertValue(payload, CivicActivityRecorded.class);
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("CIVIC_ACTIVITY_RECORDED payload is not a valid CivicActivityRecorded", ex);
        }
    }

    /** Maps the string event-type code to the enum, or {@code null} if unknown (forward-compatible no-op). */
    private AnalyticsEventType parseEventType(String raw) {
        return parseEnum(AnalyticsEventType.class, raw);
    }

    /** Maps the string tier code to the enum, or {@code null} if absent/unknown. */
    private AnalyticsTier parseTier(String raw) {
        return parseEnum(AnalyticsTier.class, raw);
    }

    /** Maps the string channel code to the enum, or {@code null} if absent/unknown. */
    private AnalyticsChannel parseChannel(String raw) {
        return parseEnum(AnalyticsChannel.class, raw);
    }

    /** Maps the string role code to the enum, or {@code null} if absent/unknown. */
    private AnalyticsRole parseRole(String raw) {
        return parseEnum(AnalyticsRole.class, raw);
    }

    /** Maps the string breach-type code to the enum, or {@code null} if absent/unknown. */
    private BreachType parseBreach(String raw) {
        return parseEnum(BreachType.class, raw);
    }

    /**
     * Null-and-unknown-tolerant enum parse: a {@code null}/blank string yields {@code null} (the dimension was
     * simply not supplied), and an unrecognised value also yields {@code null} rather than throwing — an older
     * analytics build must tolerate a newer producer's value without failing the event (Appendix E.0 additive).
     *
     * @param type the target enum type.
     * @param raw  the candidate enum name, or {@code null}.
     * @param <E>  the enum type.
     * @return the matching enum constant, or {@code null} if {@code raw} is blank or unrecognised.
     */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
