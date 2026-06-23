package com.taarifu.common.outbox;

import java.util.UUID;

/**
 * The producer-facing port for appending a domain event to the transactional outbox (ADR-0014 §2).
 *
 * <p>Responsibility: the single, small public entry point a feature module depends on to emit an event.
 * A producer — <b>already inside its application-service {@code @Transactional}</b> — calls
 * {@link #append} right after the domain mutation; the outbox row and the domain row then commit (or
 * roll back) together, which is the atomicity guarantee ADR-0008 promised (a crash can never leave the
 * domain change committed but the fan-out/routing intent lost — PRD §15 DI3).</p>
 *
 * <p>WHY a writer port and not exposing the {@code OutboxEvent} entity: it keeps the table shape and the
 * PENDING invariant in one place (DRY) and keeps {@code OutboxEvent} out of feature modules' imports —
 * they depend only on {@link OutboxWriter} + {@link EventEnvelope} from {@code common.outbox}
 * (ARCHITECTURE §3.2 — internals stay encapsulated).</p>
 */
public interface OutboxWriter {

    /**
     * Persists one {@link OutboxStatus#PENDING} outbox row in the <b>caller's current transaction</b>.
     *
     * <p>The implementation joins the caller's transaction with {@code Propagation.MANDATORY}: invoking
     * this <i>outside</i> an active transaction fails loudly rather than silently writing a non-atomic
     * event, so the atomicity contract is mechanically enforced, not merely documented. The producer's
     * thread does <b>no dispatch</b> — the {@code OutboxRelay} relays asynchronously, keeping the citizen
     * write fast (PRD §15).</p>
     *
     * <p>The {@code event.payload()} is serialised to JSON via the shared {@code ObjectMapper} and stored
     * in the {@code jsonb} {@code payload} column. <b>🔒 It MUST contain ids/codes/enums only — never
     * PII</b> (PRD §18); the outbox is queryable, replayable, and dumped in support.</p>
     *
     * @param event the envelope to persist; its {@code eventId} may be {@code null} (it is assigned from
     *              the saved row's {@code public_id} — see the returned value).
     * @return the persisted outbox row's {@code public_id}, which becomes the event's idempotency key
     *         ({@code EventEnvelope.eventId} on dispatch).
     * @throws IllegalStateException if called outside an active transaction (no atomicity guarantee).
     */
    UUID append(EventEnvelope<?> event);
}
