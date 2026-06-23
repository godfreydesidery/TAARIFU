package com.taarifu.common.outbox;

/**
 * Lifecycle state of an {@link com.taarifu.common.outbox.domain.model.OutboxEvent} row
 * (ADR-0014 §1, §3).
 *
 * <p>Responsibility: the three-state machine the {@code OutboxRelay} drives. The relay claims
 * {@link #PENDING} rows whose {@code next_attempt_at} is due, dispatches them in-process, and on
 * success marks them {@link #PROCESSED}; on a handler exception it increments the attempt count and
 * either keeps the row {@link #PENDING} (with a backed-off {@code next_attempt_at}) or, once the
 * attempt cap is reached, marks it terminally {@link #FAILED}.</p>
 *
 * <p>WHY only three states (no IN_FLIGHT/CLAIMED): the relay claims rows inside a single transaction
 * with {@code FOR UPDATE SKIP LOCKED}, so the row lock — not a status column — guards against two
 * instances dispatching the same row concurrently (ADR-0014 §3). Keeping the status set minimal is
 * KISS (CLAUDE.md §3) and means the partial PENDING index covers every row the relay must see.</p>
 */
public enum OutboxStatus {

    /** Written by the producer, awaiting dispatch; the relay's only claim target (when due). */
    PENDING,

    /** Every registered handler succeeded for this event; the relay set {@code processed_at}. Terminal. */
    PROCESSED,

    /**
     * A handler kept failing until the attempt cap was reached. Terminal — this is the DLQ:
     * FAILED rows are queryable and alertable, and a future ops action may re-queue them to
     * {@link #PENDING} (ADR-0014 revisit trigger (c)).
     */
    FAILED
}
