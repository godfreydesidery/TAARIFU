package com.taarifu.common.outbox;

import com.taarifu.common.outbox.domain.model.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

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
}
