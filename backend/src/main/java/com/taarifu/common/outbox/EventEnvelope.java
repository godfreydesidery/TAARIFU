package com.taarifu.common.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * The immutable cross-boundary wrapper a producer builds and the relay reconstructs (ADR-0014 §4).
 *
 * <p>Responsibility: carries one domain event across the in-process bus. It wraps a module's public
 * {@code <module>.api.event} payload record (which stays the stable contract — e.g.
 * {@code AnnouncementPublished}) together with the routing/idempotency metadata the dispatcher and
 * handlers need. The producer constructs it and hands it to {@link OutboxWriter#append}; the
 * {@code OutboxRelay} rebuilds it (with the same {@link #eventId}) when it dispatches the persisted row.</p>
 *
 * <p><b>{@link #eventId} is the idempotency key.</b> It equals the outbox row's {@code public_id}, so a
 * handler that records {@code (eventId, handlerName)} — or performs a naturally-idempotent upsert — is
 * safe under the at-least-once delivery the relay provides (a crash between "handler ran" and "row
 * marked PROCESSED" can redeliver the same envelope; ADR-0014 §3).</p>
 *
 * <p><b>🔒 {@link #payload} is ids/codes/enums ONLY — never PII</b> (PRD §18, §12). Consumers re-read the
 * aggregate by {@link #aggregateId} through the owner's {@code *QueryApi} (ADR-0013); they never expect
 * names, phones, IDs, or body text on the envelope.</p>
 *
 * @param <P>           the payload record type (a {@code <module>.api.event} record).
 * @param eventId       the unique event id; <b>== the outbox row's {@code public_id}</b> and the
 *                      <b>idempotency key</b> handlers dedup on. May be {@code null} when a producer
 *                      builds the envelope before persistence — {@link OutboxWriter#append} assigns it
 *                      from the saved row's {@code public_id}; it is always non-null on dispatch.
 * @param eventType     the taxonomy key the dispatcher routes by (e.g. {@code "ANNOUNCEMENT_PUBLISHED"}).
 * @param aggregateType the producing aggregate's type (e.g. {@code "ANNOUNCEMENT"}).
 * @param aggregateId   the producing aggregate's <b>public</b> id (opaque UUID — ADR-0013).
 * @param payload       the {@code api.event} record — <b>ids/codes/enums ONLY, NO PII</b>.
 * @param occurredAt    domain-time the event happened (UTC); the producer sets it.
 */
public record EventEnvelope<P>(
        UUID eventId,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        P payload,
        Instant occurredAt
) {

    /**
     * Factory for a producer building an envelope <i>before</i> persistence: {@link #eventId} is left
     * {@code null} and assigned by {@link OutboxWriter#append} from the saved outbox row's
     * {@code public_id}, guaranteeing the idempotency key and the durable row share one identity.
     *
     * @param eventType     the taxonomy key (never blank).
     * @param aggregateType the producing aggregate type (never blank).
     * @param aggregateId   the producing aggregate's public id (never {@code null}).
     * @param payload       the {@code api.event} payload — <b>ids/codes/enums only, never PII</b>.
     * @param occurredAt    domain-time the event happened (UTC; never {@code null}).
     * @param <P>           the payload record type.
     * @return a new envelope with a {@code null} {@link #eventId} ready for {@link OutboxWriter#append}.
     */
    public static <P> EventEnvelope<P> of(String eventType, String aggregateType, UUID aggregateId,
                                          P payload, Instant occurredAt) {
        return new EventEnvelope<>(null, eventType, aggregateType, aggregateId, payload, occurredAt);
    }

    /**
     * Returns a copy of this envelope with {@link #eventId} bound to the durable row's id. Used by the
     * writer once the outbox row is persisted so the dispatched envelope's idempotency key matches the
     * stored {@code public_id}.
     *
     * @param eventId the persisted outbox row's {@code public_id}.
     * @return a new envelope identical to this one but carrying {@code eventId}.
     */
    public EventEnvelope<P> withEventId(UUID eventId) {
        return new EventEnvelope<>(eventId, eventType, aggregateType, aggregateId, payload, occurredAt);
    }
}
