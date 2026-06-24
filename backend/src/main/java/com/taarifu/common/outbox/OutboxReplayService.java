package com.taarifu.common.outbox;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.outbox.domain.model.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Operator-facing DLQ replay for the transactional outbox: re-queues terminally
 * {@link OutboxStatus#FAILED} rows back to {@link OutboxStatus#PENDING} for reprocessing
 * (ADR-0014 revisit trigger (c); outbox-review P4-4).
 *
 * <p>Responsibility: turn a row in the dead-letter queue (the {@link OutboxStatus#FAILED} set surfaced by
 * {@link OutboxMaintenance}) back into a clean pending event the {@link OutboxRelay} will re-dispatch on
 * its next poll. Used after the underlying cause of a failure has been fixed (a downstream dependency
 * recovered, a handler bug deployed) to drain the DLQ — by a single event id, or in a bounded bulk
 * optionally scoped to one {@code eventType} and/or a {@code processed_at} (FAILED-time) window.</p>
 *
 * <p><b>🔒 STRICTLY FAILED → PENDING.</b> Every requeue path pins {@code WHERE status='FAILED'} in SQL
 * ({@link OutboxEventRepository#requeueFailedById}, {@link OutboxEventRepository#requeueFailedBatch}), so a
 * {@link OutboxStatus#PROCESSED} row is <b>never</b> reset (which would re-fire an already-delivered
 * effect — a correctness/duplication hazard) and a still-in-flight {@link OutboxStatus#PENDING} row is
 * never disturbed. A re-queued row is reset to a clean slate: {@code attempts=0}, {@code last_error=NULL},
 * {@code processed_at=NULL}, and {@code next_attempt_at=now()} so it is due immediately.</p>
 *
 * <p><b>Idempotent.</b> Because the source state is pinned to FAILED, replaying the same id (or the same
 * bulk window) twice is safe: the first call moves the matching rows to PENDING, so the second call matches
 * 0 FAILED rows and re-queues nothing. The returned count is therefore "rows actually moved", never an
 * over-count.</p>
 *
 * <p><b>At-least-once still applies.</b> Replay only resets delivery state; it does not bypass the relay or
 * its idempotency contract. A re-queued event flows through the same {@link OutboxRelay} → idempotent
 * {@link DomainEventHandler} path, so a handler that already partially applied the effect before the
 * original failure must still dedup on {@code eventId} (ADR-0014 §3) — replay does not weaken that.</p>
 *
 * <p><b>🔒 Privacy:</b> all logging is by event id / event type / counts only — never the payload, the
 * {@code last_error} text, or any aggregate detail (PRD §18, CLAUDE.md §12).</p>
 *
 * <p><b>Read seam (review P3-1):</b> {@link #listFailed(Pageable)} pages the DLQ as a <b>PII-free</b>
 * {@link FailedOutboxView} projection (id / eventType / attempts / age only — no payload, no {@code last_error}
 * text), so the admin DLQ-list surface can see what is in the queue before deciding what to replay without ever
 * reaching into the kernel repository or touching an {@link OutboxEvent} entity.</p>
 *
 * <p><b>No controller here.</b> The admin/ops HTTP surface that triggers list/replay belongs to the admin
 * module (out of scope for {@code common.outbox}); this class exposes the service bean only — the admin module
 * consumes it as a shared-kernel collaborator (M14, review P3-1).</p>
 */
@Service
public class OutboxReplayService {

    private static final Logger log = LoggerFactory.getLogger(OutboxReplayService.class);

    private final OutboxEventRepository repository;
    private final ClockPort clock;
    private final OutboxProperties properties;

    /**
     * @param repository the outbox store (provides the FAILED-pinned requeue statements).
     * @param clock      the clock port, so {@code next_attempt_at} is deterministic and testable.
     * @param properties supplies the default bulk cap ({@link OutboxProperties#purgeBatchSize}) when the
     *                   caller does not specify one — bounded so a mass replay never re-queues an unbounded
     *                   backlog in one statement.
     */
    public OutboxReplayService(OutboxEventRepository repository, ClockPort clock,
                               OutboxProperties properties) {
        this.repository = repository;
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * Re-queues a single {@link OutboxStatus#FAILED} row, identified by its public id, back to
     * {@link OutboxStatus#PENDING}. A no-op (returns 0) if the id is unknown or the row is not FAILED
     * (already PENDING/PROCESSED) — making the call idempotent and safe to retry.
     *
     * @param eventId the public id of the FAILED outbox row to re-queue; never {@code null}.
     * @return {@code 1} if a FAILED row was re-queued, {@code 0} otherwise.
     * @throws NullPointerException if {@code eventId} is {@code null}.
     */
    @Transactional
    public int replayById(UUID eventId) {
        if (eventId == null) {
            throw new NullPointerException("eventId must not be null");
        }
        int requeued = repository.requeueFailedById(eventId, clock.now());
        if (requeued > 0) {
            log.info("Outbox DLQ replay re-queued FAILED event to PENDING: eventId={}", eventId);
        } else {
            // Idempotent / not-found path: nothing matched the FAILED predicate.
            log.info("Outbox DLQ replay matched no FAILED row (already re-queued or unknown): eventId={}", eventId);
        }
        return requeued;
    }

    /**
     * Re-queues a bounded batch of {@link OutboxStatus#FAILED} rows back to {@link OutboxStatus#PENDING},
     * optionally scoped by {@code eventType} and/or a {@code processed_at} (FAILED-time) window.
     *
     * <p>All filter fields are optional ({@code null} widens the match — see {@link ReplayFilter}). The
     * batch is capped: an explicit {@link ReplayFilter#limit()} if positive, otherwise
     * {@link OutboxProperties#purgeBatchSize}. To drain a large DLQ the caller invokes this repeatedly until
     * it returns 0; this method itself re-queues at most one bounded batch so a single call can never trigger
     * an unbounded relay surge.</p>
     *
     * @param filter the bulk replay scope (event type, processed-at window, batch cap); never {@code null}.
     *               Use {@link ReplayFilter#all()} to replay every FAILED row up to the default cap.
     * @return the number of FAILED rows actually re-queued this call (0 when none match).
     * @throws NullPointerException if {@code filter} is {@code null}.
     */
    @Transactional
    public int replayBatch(ReplayFilter filter) {
        if (filter == null) {
            throw new NullPointerException("filter must not be null");
        }
        int cap = (filter.limit() != null && filter.limit() > 0)
                ? filter.limit()
                : properties.purgeBatchSize();
        int requeued = repository.requeueFailedBatch(
                filter.eventType(), filter.processedFrom(), filter.processedTo(), clock.now(), cap);
        log.info("Outbox DLQ bulk replay re-queued {} FAILED row(s) to PENDING [eventType={} from={} to={} cap={}]",
                requeued, filter.eventType(), filter.processedFrom(), filter.processedTo(), cap);
        return requeued;
    }

    /**
     * Pages the dead-letter queue (terminal {@link OutboxStatus#FAILED} rows) as a <b>PII-free</b>
     * {@link FailedOutboxView} projection, oldest FAILED-time first — the operator's read view of the DLQ
     * before deciding what to replay (review P3-1; backs {@code GET /api/v1/admin/outbox/failed}).
     *
     * <p>This is the shared-kernel seam the admin module consumes instead of reaching into
     * {@link OutboxEventRepository} directly: it maps each managed {@link OutboxEvent} to a view that exposes
     * only the public id, the event type, the attempt count, the FAILED-time, and a derived <i>age</i> — never
     * the {@code payload} (an internal serialised body) nor the redacted {@code last_error} text. The entity
     * itself never crosses the kernel boundary.</p>
     *
     * <p><b>🔒 Privacy (PRD §18, CLAUDE.md §12):</b> the outbox payload holds ids/codes/enums only, but it is
     * still an internal body and is deliberately <b>excluded</b> from the projection. The {@code last_error}
     * is redacted at write time, yet still excluded here — the operator list shows diagnostics dimensions, not
     * any free text. Age is computed against {@code clock.now()} so the view is testable.</p>
     *
     * @param pageable the (bounded) page request; the admin controller caps the size via
     *                 {@code PageRequestFactory} before calling.
     * @return a page of PII-free FAILED views, oldest FAILED-time first; an empty page when the DLQ is empty.
     * @throws NullPointerException if {@code pageable} is {@code null}.
     */
    @Transactional(readOnly = true)
    public Page<FailedOutboxView> listFailed(Pageable pageable) {
        if (pageable == null) {
            throw new NullPointerException("pageable must not be null");
        }
        Instant now = clock.now();
        return repository.findByStatusOrderByProcessedAtAsc(OutboxStatus.FAILED, pageable)
                .map(e -> FailedOutboxView.of(e, now));
    }

    /**
     * A <b>PII-free</b> read projection of one terminal {@link OutboxStatus#FAILED} outbox row for the admin
     * DLQ list (review P3-1).
     *
     * <p>Responsibility: carry exactly the operator-facing diagnostics dimensions — public id, event type,
     * attempt count, FAILED-time, and a derived age — and <b>nothing else</b>. It deliberately omits the
     * {@code payload} (an internal serialised event body) and the redacted {@code last_error} text, so the
     * cross-module list surface can never leak an internal body or a stack-trace fragment (PRD §18). Carries no
     * PII by construction: an outbox row holds ids/codes/enums only, and this view drops even those that are
     * internal-only.</p>
     *
     * @param eventId     the outbox row's public id (addresses a single-id replay).
     * @param eventType   the taxonomy key (e.g. {@code REPORT_ROUTED}) — lets an operator scope a bulk replay.
     * @param attempts    how many dispatch attempts were made before the row was terminally FAILED.
     * @param failedAt    when the row reached the terminal FAILED state ({@code processed_at}).
     * @param ageSeconds  age of the failure in whole seconds at read time ({@code now - failedAt}); {@code 0}
     *                    if {@code failedAt} is somehow null/future — a non-negative diagnostics hint only.
     */
    public record FailedOutboxView(UUID eventId, String eventType, int attempts,
                                   Instant failedAt, long ageSeconds) {

        /**
         * Maps a managed {@link OutboxEvent} to its PII-free view, computing the age against {@code now}.
         *
         * @param e   the FAILED outbox row (caller guarantees {@link OutboxStatus#FAILED}); never {@code null}.
         * @param now the read-time instant (from the service clock) used to derive {@link #ageSeconds()}.
         * @return the projection; never exposes the payload or {@code last_error}.
         */
        static FailedOutboxView of(OutboxEvent e, Instant now) {
            Instant failedAt = e.getProcessedAt();
            long age = (failedAt == null || failedAt.isAfter(now))
                    ? 0L
                    : now.getEpochSecond() - failedAt.getEpochSecond();
            return new FailedOutboxView(e.getPublicId(), e.getEventType(), e.getAttempts(), failedAt, age);
        }
    }

    /**
     * Bounded bulk-replay scope for {@link #replayBatch(ReplayFilter)} — all fields optional.
     *
     * <p>Responsibility: carry the operator's replay window without leaking the repository's parameter list
     * to callers. A {@code null} field is a "no constraint" wildcard (the SQL null-guards each predicate), so
     * {@link #all()} replays every FAILED row up to the default cap, while a populated field narrows the set.
     * Carries <b>no PII</b> — only a taxonomy key, timestamps, and a numeric cap (PRD §18).</p>
     *
     * @param eventType     restrict to this {@code event_type}, or {@code null} for any taxonomy key.
     * @param processedFrom inclusive lower bound on the FAILED-time ({@code processed_at}), or {@code null}.
     * @param processedTo   inclusive upper bound on the FAILED-time ({@code processed_at}), or {@code null}.
     * @param limit         max rows to re-queue this call; {@code null} or non-positive uses the default cap
     *                      ({@link OutboxProperties#purgeBatchSize}).
     */
    public record ReplayFilter(String eventType, Instant processedFrom, Instant processedTo, Integer limit) {

        /**
         * @return a filter that matches every FAILED row, using the default batch cap — replay the whole DLQ
         *         one bounded batch at a time.
         */
        public static ReplayFilter all() {
            return new ReplayFilter(null, null, null, null);
        }

        /**
         * @param eventType the taxonomy key to replay (e.g. {@code REPORT_ROUTED}); never blank.
         * @return a filter scoped to one event type, using the default batch cap.
         */
        public static ReplayFilter ofEventType(String eventType) {
            return new ReplayFilter(eventType, null, null, null);
        }
    }
}
