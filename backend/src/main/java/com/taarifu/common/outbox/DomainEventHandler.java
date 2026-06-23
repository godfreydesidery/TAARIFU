package com.taarifu.common.outbox;

import java.util.Set;

/**
 * The SPI a module implements to react to a domain event delivered by the in-process bus (ADR-0014 §4).
 *
 * <p>Responsibility: one handler per concern (ISP — feed fan-out, notification dispatch, OWNER routing,
 * SLA-clock start, search indexing, analytics, moderation takedown each get their own small handler). A
 * handler declares the {@link #handledEventTypes()} it consumes; the {@code EventDispatcher} builds a
 * {@code Map<eventType, handlers>} from every {@code DomainEventHandler} bean at startup and fans each
 * dispatched event to the matching handlers. Register a handler simply by declaring it a Spring bean.</p>
 *
 * <p><b>Handlers MUST be idempotent.</b> The relay delivers <b>at-least-once</b>: a crash between
 * "{@link #handle} returned" and "the outbox row is marked PROCESSED" redelivers the same envelope
 * (ADR-0014 §3). Achieve exactly-once <i>effect</i> by either:</p>
 * <ul>
 *   <li>recording {@code (event.eventId(), handlerName)} in your module and no-opping if already present
 *       (per-handler, since one event fans to several handlers); or</li>
 *   <li>performing a naturally-idempotent write — an upsert/conditional-insert keyed on a business key
 *       (e.g. a {@code (recipient, source_event_id)} unique constraint; the existing single-OWNER
 *       {@code ResponderAssignment} guard) — preferred where the schema already enforces uniqueness.</li>
 * </ul>
 *
 * <p><b>Boundary rules (ADR-0013):</b> a handler MUST NOT read PII from the payload (there is none) and
 * MUST NOT call back synchronously into the <i>producing</i> module's domain — cross-module reads go
 * through the callee's published {@code *QueryApi}. A thrown exception signals failure: the relay retries
 * with backoff and, after the attempt cap, moves the row to {@link OutboxStatus#FAILED} (the DLQ).</p>
 */
public interface DomainEventHandler {

    /**
     * The set of {@code eventType} taxonomy keys this handler consumes; the dispatcher routes by exact
     * string match. Returning more than one lets a single handler serve a small family of related events.
     *
     * @return the non-null (possibly multi-element) set of handled event types.
     */
    Set<String> handledEventTypes();

    /**
     * Applies the side-effect for one delivered event — <b>idempotently</b> (see the type Javadoc): it
     * MUST be safe to invoke more than once for the same {@code event.eventId()}.
     *
     * <p>The envelope's {@code payload} carries ids/codes/enums only; resolve any further detail by
     * re-reading the aggregate via the owner's {@code *QueryApi} (never expect PII here). Throwing from
     * this method causes the relay to retry the event (backoff) and eventually FAIL the outbox row.</p>
     *
     * @param event the delivered envelope; {@code event.eventId()} is the idempotency/dedup key.
     */
    void handle(EventEnvelope<?> event);
}
