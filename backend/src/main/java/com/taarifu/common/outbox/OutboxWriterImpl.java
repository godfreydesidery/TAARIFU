package com.taarifu.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.outbox.domain.model.OutboxEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Default {@link OutboxWriter}: serialises the envelope and persists one PENDING row in the caller's
 * transaction (ADR-0014 §2).
 *
 * <p>Responsibility: turn an {@link EventEnvelope} into a durable {@link OutboxEvent} row so the event
 * commits atomically with the producer's domain change. It declares <b>no transaction of its own</b> and
 * runs with {@link Propagation#MANDATORY}: if a producer carelessly calls {@link #append} outside an
 * active transaction, Spring throws — making the atomicity contract mechanically enforced rather than
 * documented hope (ADR-0008; PRD §15 DI3).</p>
 *
 * <p>WHY {@code MANDATORY} (not {@code REQUIRED}): {@code REQUIRED} would silently <i>open a new</i>
 * transaction for the writer when none exists, committing the event independently of the (absent) domain
 * change — the exact non-atomicity we must forbid. {@code MANDATORY} fails loudly instead.</p>
 */
@Service
public class OutboxWriterImpl implements OutboxWriter {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final ClockPort clock;

    /**
     * @param repository   the outbox store.
     * @param objectMapper the shared Jackson mapper (UTC/ISO config from {@code JacksonConfig}).
     * @param clock        the clock port, so {@code nextAttemptAt}/{@code occurredAt} are testable.
     */
    public OutboxWriterImpl(OutboxEventRepository repository, ObjectMapper objectMapper, ClockPort clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Serialises {@code event.payload()} to JSON via the shared mapper, saves a PENDING row due now,
     * and returns the saved {@code public_id} — which is the event's idempotency key on dispatch. The
     * producer's thread does no dispatch; the relay handles delivery asynchronously.</p>
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(EventEnvelope<?> event) {
        String payloadJson = serialise(event.payload());
        OutboxEvent row = OutboxEvent.pending(
                event.aggregateType(),
                event.aggregateId(),
                event.eventType(),
                payloadJson,
                event.occurredAt(),
                clock.now());
        OutboxEvent saved = repository.save(row);
        return saved.getPublicId();
    }

    /**
     * Serialises the payload to JSON, wrapping the (checked) Jackson failure as an unchecked exception so
     * a non-serialisable payload fails the producer's transaction loudly (a programming error caught in
     * tests, never a silent dropped event).
     *
     * @param payload the {@code api.event} record (ids/codes/enums only — never PII).
     * @return the JSON string stored in the {@code jsonb} payload column.
     */
    private String serialise(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Do NOT include the payload in the message — it could (by mistake) carry sensitive ids.
            throw new IllegalArgumentException(
                    "Outbox payload is not JSON-serialisable: " + payload.getClass().getName(), e);
        }
    }
}
