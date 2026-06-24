package com.taarifu.common.outbox;

import com.taarifu.common.outbox.domain.model.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Spring Data repository for the {@link OutboxEvent} store (ADR-0014 §3).
 *
 * <p>Responsibility: the relay's claim query and diagnostic reads. The hot path is
 * {@link #claimDue(Instant, Pageable)} — a native {@code FOR UPDATE SKIP LOCKED} batch claim that makes
 * the poller safe to run on multiple app instances (each claims disjoint rows; no double-dispatch from
 * concurrency — ADR-0014 §3).</p>
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Claims a batch of due {@link OutboxStatus#PENDING} rows for this poll cycle, locking them so a
     * concurrent poller instance skips them.
     *
     * <p>WHY native + {@code FOR UPDATE SKIP LOCKED}: it is the multi-instance-safe claim primitive —
     * each instance grabs a disjoint set, and an in-flight (locked) row is invisible to the others, so
     * there is no double-dispatch except the deliberate at-least-once crash window (ADR-0014 §3). Ordering
     * by {@code occurred_at, id} gives best-effort domain order within the batch (delivery order is not
     * globally guaranteed — ADR-0014 ordering caveats). The {@code WHERE status='PENDING'} predicate is
     * served by the partial index {@code ix_outbox_event_due} (V97), keeping the scan tiny as terminal
     * rows accumulate before retention purge.</p>
     *
     * <p>MUST be called inside the relay's transaction so the row locks are held until that transaction
     * commits the status updates.</p>
     *
     * @param now      the current instant; rows with {@code next_attempt_at <= now} are due.
     * @param pageable limits the batch size (the relay passes {@code PageRequest.ofSize(batchSize)}).
     * @return the claimed (row-locked) due events, oldest first; empty if none are due.
     */
    @Query(value = """
            SELECT * FROM outbox_event
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY occurred_at, id
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimDue(@Param("now") Instant now, Pageable pageable);

    /**
     * Hard-deletes one bounded batch of terminally {@link OutboxStatus#PROCESSED} rows whose
     * {@code processed_at} is older than the cutoff — the retention purge (ADR-0014 §1 "operability",
     * review P3-2). Returns the number deleted so the caller can loop until a batch comes back short.
     *
     * <p><b>WHY only {@code status = 'PROCESSED'}:</b> {@link OutboxStatus#FAILED} rows are the DLQ — they
     * must <b>never</b> be purged by retention (they stay queryable, alertable, and replayable). PENDING
     * rows are still in flight. So the predicate pins {@code PROCESSED} explicitly rather than keying off
     * {@code processed_at} alone (which FAILED rows also carry).</p>
     *
     * <p><b>WHY batched ({@code id IN (SELECT … LIMIT)}):</b> deleting an unbounded backlog in one
     * statement would take a long lock and a fat transaction on this hot table. A bounded {@code LIMIT}
     * sub-select keeps each delete small; the caller loops. Native SQL because JPQL has no {@code LIMIT}
     * in a {@code DELETE}.</p>
     *
     * <p>{@code @Modifying(clearAutomatically=true)} flushes the persistence context after the bulk delete
     * so subsequent reads in the same transaction do not see stale managed copies of purged rows.</p>
     *
     * <p><b>WHY {@code @Transactional} on the repository method (not the caller):</b> a {@code @Modifying}
     * JPQL/native write requires an active transaction, and — unlike Spring Data's generated CRUD methods —
     * a custom {@code @Query} method is <b>not</b> wrapped in one by default; without this annotation the
     * bulk delete throws {@link org.springframework.dao.InvalidDataAccessApiUsageException
     * InvalidDataAccessApiUsageException} ("Executing an update/delete query"). The maintenance job
     * ({@link OutboxMaintenance#purgeProcessed}) calls this in a loop with no surrounding transaction, so the
     * boundary lives here. Placing it on the repository method (which the caller reaches through the Spring
     * Data proxy) gives <b>one transaction per batch</b>: each delete commits before the next iteration, so
     * the row locks are short-lived and a large backlog drains incrementally — never one fat, long-lock
     * transaction over the whole loop. (Putting {@code @Transactional} on the loop method instead would wrap
     * every batch in a single long transaction — the regression this design avoids.)</p>
     *
     * @param cutoff    rows with {@code processed_at < cutoff} are eligible (i.e. {@code now - retention}).
     * @param batchSize the maximum number of rows to delete in this statement.
     * @return the number of rows deleted (0 when nothing is eligible; {@code < batchSize} ends the loop).
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(value = """
            DELETE FROM outbox_event
            WHERE id IN (
                SELECT id FROM outbox_event
                WHERE status = 'PROCESSED' AND processed_at < :cutoff
                ORDER BY processed_at
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deleteProcessedOlderThan(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    /**
     * Counts the rows currently in terminal {@link OutboxStatus#FAILED} — the DLQ depth (review P3-2).
     * Backs the {@code taarifu.outbox.failed} Micrometer gauge and the periodic WARN log so a growing DLQ
     * is alertable (ARCHITECTURE §9). Never mutates state.
     *
     * @return the number of FAILED outbox rows (the dead-letter queue depth).
     */
    @Query("SELECT COUNT(e) FROM OutboxEvent e WHERE e.status = com.taarifu.common.outbox.OutboxStatus.FAILED")
    long countFailed();

    /**
     * Re-queues a single terminally {@link OutboxStatus#FAILED} row back to {@link OutboxStatus#PENDING}
     * for reprocessing — the operator DLQ-replay primitive (ADR-0014 revisit trigger (c); review P4-4).
     *
     * <p>Resets the row to a clean pending state: {@code status='PENDING'}, {@code attempts=0},
     * {@code last_error=NULL}, {@code processed_at=NULL}, and {@code next_attempt_at=:now} (due immediately,
     * so the relay re-picks it on the next poll).</p>
     *
     * <p><b>🔒 STRICTLY FAILED → PENDING.</b> The {@code WHERE status='FAILED'} predicate pins the only
     * legal source state, so this can <b>never</b> touch a {@link OutboxStatus#PROCESSED} row (which would
     * re-fire an already-delivered effect) nor a still-in-flight {@link OutboxStatus#PENDING} row. This also
     * makes replay <b>idempotent</b>: a second call for the same id matches 0 rows (the first call already
     * moved it to PENDING), returning 0.</p>
     *
     * <p>Native SQL (not a managed-entity update) so the requeue is a single, lock-free statement that does
     * not load the row into the persistence context. {@code @Modifying(clearAutomatically=true)} flushes the
     * context so a subsequent read in the same transaction does not see a stale managed copy.</p>
     *
     * @param publicId the public id of the FAILED row to re-queue.
     * @param now      the instant to set as {@code next_attempt_at} (the row becomes due immediately).
     * @return {@code 1} if a FAILED row with this id was re-queued, {@code 0} otherwise (already PENDING,
     *         PROCESSED, or no such row — all no-ops).
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE outbox_event
            SET status = 'PENDING', attempts = 0, last_error = NULL,
                processed_at = NULL, next_attempt_at = :now
            WHERE public_id = :publicId AND status = 'FAILED'
            """, nativeQuery = true)
    int requeueFailedById(@Param("publicId") UUID publicId, @Param("now") Instant now);

    /**
     * Re-queues a bounded batch of terminally {@link OutboxStatus#FAILED} rows back to
     * {@link OutboxStatus#PENDING}, optionally filtered by {@code event_type} and by a {@code processed_at}
     * age window (when the row reached the FAILED terminal state) — the bulk operator DLQ-replay primitive
     * (ADR-0014 revisit trigger (c); review P4-4).
     *
     * <p>Each matched row is reset to a clean pending state exactly as {@link #requeueFailedById}:
     * {@code attempts=0}, {@code last_error=NULL}, {@code processed_at=NULL}, {@code next_attempt_at=:now}.</p>
     *
     * <p><b>Optional filters (pass {@code null} to skip each).</b> {@code eventType} restricts replay to one
     * taxonomy key (e.g. retry only {@code REPORT_ROUTED} after a routing fix). {@code processedFrom} /
     * {@code processedTo} bound the window on {@code processed_at} — the time the row was FAILED — so an
     * operator can replay, say, only failures from a known incident window. Each predicate is null-guarded in
     * SQL ({@code :param IS NULL OR column …}), so an unset filter widens the match rather than excluding
     * everything.</p>
     *
     * <p><b>WHY bounded ({@code id IN (SELECT … LIMIT)}):</b> a mass replay must not re-queue an unbounded
     * backlog in one statement (long lock, fat transaction, and a sudden relay surge). The {@code LIMIT}
     * caps each call; the caller may loop. Ordering by {@code processed_at} re-queues the oldest failures
     * first.</p>
     *
     * <p><b>🔒 STRICTLY FAILED → PENDING + idempotent</b> — same guarantee as {@link #requeueFailedById}:
     * the {@code status='FAILED'} predicate is the only source state, so PROCESSED/PENDING rows are never
     * touched, and re-running the same window re-queues progressively fewer rows (already-moved rows no
     * longer match), converging to 0.</p>
     *
     * @param eventType     restrict to this {@code event_type}, or {@code null} for any.
     * @param processedFrom inclusive lower bound on {@code processed_at}, or {@code null} for no lower bound.
     * @param processedTo   inclusive upper bound on {@code processed_at}, or {@code null} for no upper bound.
     * @param now           the instant to set as {@code next_attempt_at} (rows become due immediately).
     * @param batchSize     the maximum number of FAILED rows to re-queue in this statement.
     * @return the number of rows re-queued (0 when none match; may be {@code < batchSize} when the eligible
     *         set is smaller).
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE outbox_event
            SET status = 'PENDING', attempts = 0, last_error = NULL,
                processed_at = NULL, next_attempt_at = :now
            WHERE id IN (
                SELECT id FROM outbox_event
                WHERE status = 'FAILED'
                  AND (:eventType IS NULL OR event_type = :eventType)
                  AND (CAST(:processedFrom AS timestamptz) IS NULL OR processed_at >= :processedFrom)
                  AND (CAST(:processedTo   AS timestamptz) IS NULL OR processed_at <= :processedTo)
                ORDER BY processed_at
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int requeueFailedBatch(@Param("eventType") String eventType,
                           @Param("processedFrom") Instant processedFrom,
                           @Param("processedTo") Instant processedTo,
                           @Param("now") Instant now,
                           @Param("batchSize") int batchSize);
}
