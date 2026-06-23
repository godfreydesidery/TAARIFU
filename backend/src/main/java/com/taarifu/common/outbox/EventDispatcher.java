package com.taarifu.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The in-process event bus: routes one delivered {@link EventEnvelope} to every registered
 * {@link DomainEventHandler} for its {@code eventType} (ADR-0014 §4).
 *
 * <p>Responsibility: at startup it builds an immutable {@code Map<eventType, handlers>} from all
 * {@link DomainEventHandler} beans Spring injects (each self-registers by returning its
 * {@link DomainEventHandler#handledEventTypes()}); {@link #dispatch} looks up the handlers for the
 * envelope's type and invokes each in turn. It is the <b>single seam</b> the relay calls — swapping the
 * in-process bus for a real broker later (ADR-0014 revisit trigger (a)) touches only this class, leaving
 * {@link OutboxWriter}, {@link EventEnvelope}, and every producer/handler unchanged (ARCHITECTURE §10).</p>
 *
 * <p><b>No-consumer events are valid.</b> An event with no registered handler is dispatched to zero
 * handlers and the relay marks its row {@link OutboxStatus#PROCESSED} — a published event need not have a
 * consumer yet (additive, decoupled).</p>
 *
 * <p><b>Failure semantics:</b> {@link #dispatch} invokes handlers sequentially and lets the first
 * handler exception propagate to the relay, which retries the whole event (backoff) and eventually fails
 * the row. Because delivery is at-least-once and handlers are idempotent (their contract), a retry that
 * re-runs an already-succeeded sibling handler is safe (ADR-0014 §3).</p>
 */
@Component
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    /**
     * Immutable routing table {@code eventType -> handlers}, built once from the injected handler beans.
     * Lookups are read-only after construction, so no synchronisation is needed under concurrent relays.
     */
    private final Map<String, List<DomainEventHandler>> handlersByType;

    /**
     * Builds the routing table from every {@link DomainEventHandler} bean in the context.
     *
     * @param handlers all handler beans Spring discovers (possibly empty — the bus still constructs).
     */
    public EventDispatcher(List<DomainEventHandler> handlers) {
        Map<String, List<DomainEventHandler>> map = new HashMap<>();
        for (DomainEventHandler handler : handlers) {
            for (String eventType : handler.handledEventTypes()) {
                map.computeIfAbsent(eventType, k -> new java.util.ArrayList<>()).add(handler);
            }
        }
        // Freeze into unmodifiable lists so the table cannot be mutated after wiring (thread-safe reads).
        Map<String, List<DomainEventHandler>> frozen = new HashMap<>();
        map.forEach((type, list) -> frozen.put(type, List.copyOf(list)));
        this.handlersByType = Map.copyOf(frozen);
        log.info("EventDispatcher wired: {} event type(s) across {} handler bean(s)",
                handlersByType.size(), handlers.size());
    }

    /**
     * Fans the envelope to every handler registered for its {@code eventType}, in registration order.
     *
     * <p>Invoked by the {@code OutboxRelay} only. If a handler throws, the exception propagates so the
     * relay can retry/fail the event; remaining handlers for this event are not invoked on this attempt
     * and will be re-attempted (idempotently) on the next delivery.</p>
     *
     * @param event the delivered envelope (its {@code eventId} is the idempotency key for handlers).
     */
    public void dispatch(EventEnvelope<?> event) {
        List<DomainEventHandler> handlers = handlersByType.get(event.eventType());
        if (handlers == null || handlers.isEmpty()) {
            // A published event with no consumer is legitimate — the relay will mark it PROCESSED.
            log.debug("No handler registered for eventType={} (eventId={}); dispatched to zero handlers",
                    event.eventType(), event.eventId());
            return;
        }
        for (DomainEventHandler handler : handlers) {
            handler.handle(event);
        }
    }
}
