package com.taarifu.common.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.outbox.domain.model.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The {@code @Scheduled} outbox poller: claims due {@link OutboxStatus#PENDING} rows, dispatches each
 * in-process, and drives every row to a terminal state (ADR-0014 §3).
 *
 * <p>Responsibility: realise the transactional-outbox <b>relay</b>. One {@link #poll()} cycle, inside a
 * single transaction, (1) claims a batch of due rows with {@code FOR UPDATE SKIP LOCKED} — making the
 * poller safe to run on multiple app instances (each claims a disjoint set, ARCHITECTURE §10) — then
 * (2) dispatches each row to the in-process {@link EventDispatcher} and (3) applies that row's outcome:
 * {@link OutboxStatus#PROCESSED} on success, otherwise {@code attempts++} with an exponential
 * backoff-with-jitter {@code next_attempt_at} (the row stays {@link OutboxStatus#PENDING}), or terminal
 * {@link OutboxStatus#FAILED} (the DLQ) once {@code attempts} reaches the configured cap.</p>
 *
 * <p><b>At-least-once delivery (ADR-0014 §3).</b> A crash between "a handler ran" and "this batch
 * transaction commits the status updates" leaves the row {@link OutboxStatus#PENDING}, so the next poll
 * re-dispatches it — a handler can therefore see the same {@code eventId} twice. Handlers are required to
 * be <b>idempotent</b> ({@link DomainEventHandler}); the relay deliberately does not attempt exactly-once
 * delivery (two-phase commit across the dispatch) — that is the KISS trade this design makes.</p>
 *
 * <p><b>Per-row failure isolation.</b> Each row's dispatch is wrapped in its own try/catch so one row's
 * handler exception is recorded against <i>that</i> row only (retry/FAIL) and never rolls back the
 * sibling rows' {@link OutboxStatus#PROCESSED} marks — all outcome mutations in the batch commit together
 * when {@link #poll()} returns. A caught handler exception is <b>not</b> rethrown, so the batch
 * transaction is never marked rollback-only.</p>
 *
 * <p><b>🔒 Privacy:</b> on failure only a short, redacted reason ({@code exception class + message},
 * truncated) is stored in {@code last_error}; never a stack trace, never PII (ADR-0008 §5.2, PRD §18).
 * The relay logs by {@code eventId}/{@code eventType} only — never the payload.</p>
 *
 * <p><b>WHY a poller and not {@code @TransactionalEventListener(AFTER_COMMIT)}</b>: the after-commit
 * listener loses the effect if the JVM dies in the post-commit window and offers no retry/DLQ. The relay
 * reads <b>committed</b> rows, so it is crash-safe and retryable by construction (ADR-0014 §3). The cost —
 * up to one poll interval of latency before a handler runs — is acceptable for fan-out/routing (PRD §15
 * budgets the citizen write, not the downstream effect).</p>
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository repository;
    private final EventDispatcher dispatcher;
    private final ObjectMapper objectMapper;
    private final ClockPort clock;
    private final OutboxProperties properties;

    /**
     * @param repository   the outbox store (provides the {@code FOR UPDATE SKIP LOCKED} claim query).
     * @param dispatcher   the in-process bus that fans an event to its registered handlers.
     * @param objectMapper the shared Jackson mapper (re-reads the stored {@code jsonb} payload as a tree
     *                     so the relay need not know the concrete payload record type).
     * @param clock        the clock port, so the poll instant and backoff schedule are testable.
     * @param properties   batch size, attempt cap, and backoff schedule (defaulted — works out of the box).
     */
    public OutboxRelay(OutboxEventRepository repository, EventDispatcher dispatcher,
                       ObjectMapper objectMapper, ClockPort clock, OutboxProperties properties) {
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * One poll cycle: claim a batch of due PENDING rows and dispatch each, applying per-row outcomes.
     *
     * <p>Driven by {@code @Scheduled(fixedDelay)} — {@code fixedDelay} (not {@code fixedRate}) so cycles
     * never overlap on a slow batch (one tick starts only after the previous finished). The whole cycle
     * runs in <b>one transaction</b>: the {@code FOR UPDATE SKIP LOCKED} row locks taken by the claim are
     * held until this method commits the status updates, so no other instance can re-claim a row this
     * cycle is mid-dispatch on. The poll interval is read from {@code taarifu.outbox.poll-interval-ms}
     * (default 1000ms) — on the annotation because its attribute must be a constant expression.</p>
     *
     * @return the number of rows dispatched this cycle (0 when none are due) — useful for tests/metrics.
     */
    @Scheduled(fixedDelayString = "${taarifu.outbox.poll-interval-ms:1000}")
    @Transactional
    public int poll() {
        Instant now = clock.now();
        List<OutboxEvent> batch = repository.claimDue(now, PageRequest.ofSize(properties.batchSize()));
        if (batch.isEmpty()) {
            return 0;
        }
        for (OutboxEvent row : batch) {
            process(row, now);
        }
        log.debug("Outbox poll dispatched {} event(s)", batch.size());
        return batch.size();
    }

    /**
     * Dispatches one claimed row and records its outcome on the row (the surrounding {@link #poll()}
     * transaction persists the change). A handler exception is caught here — never rethrown — so it
     * isolates to this row and does not roll back the batch.
     *
     * @param row the claimed (row-locked) PENDING event.
     * @param now the poll instant (shared across the batch for a consistent processed/backoff clock).
     */
    private void process(OutboxEvent row, Instant now) {
        try {
            dispatcher.dispatch(rebuildEnvelope(row));
            row.markProcessed(now);
        } catch (RuntimeException ex) {
            recordFailure(row, now, ex);
        }
    }

    /**
     * Reconstructs the {@link EventEnvelope} the dispatcher delivers from the persisted row. The stored
     * payload is read back as a Jackson {@link JsonNode} (not the concrete record type — the relay is
     * payload-agnostic by design); each handler interprets the tree it needs. {@code eventId} is the row's
     * {@code public_id}, so the delivered idempotency key matches what the producer's
     * {@link OutboxWriter#append} returned.
     *
     * @param row the persisted outbox row.
     * @return the envelope to dispatch in-process.
     */
    private EventEnvelope<JsonNode> rebuildEnvelope(OutboxEvent row) {
        JsonNode payload = readPayload(row);
        return new EventEnvelope<>(
                row.getPublicId(),
                row.getEventType(),
                row.getAggregateType(),
                row.getAggregateId(),
                payload,
                row.getOccurredAt());
    }

    /**
     * Parses the stored {@code jsonb} payload into a tree. A malformed payload is a non-retryable data
     * defect, so it is surfaced as a {@link RuntimeException} that {@link #process} treats like any handler
     * failure (retry then FAIL) — the row never silently disappears.
     *
     * @param row the persisted outbox row.
     * @return the payload as a Jackson tree.
     */
    private JsonNode readPayload(OutboxEvent row) {
        try {
            return objectMapper.readTree(row.getPayload());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // No payload text in the message — it could (by mistake) carry sensitive ids (PRD §18).
            throw new IllegalStateException("Outbox payload is not valid JSON for eventId=" + row.getPublicId(), e);
        }
    }

    /**
     * Records a failed dispatch: either schedule a backed-off retry (row stays PENDING) or, once the
     * attempt cap is reached, move the row to terminal {@link OutboxStatus#FAILED} (the DLQ). The stored
     * reason is redacted to {@code <ExceptionClass>: <message>} — never a stack trace, never PII.
     *
     * @param row the row that failed dispatch.
     * @param now the poll instant.
     * @param ex  the handler/parse exception (only its class + message are persisted).
     */
    private void recordFailure(OutboxEvent row, Instant now, RuntimeException ex) {
        String reason = redact(ex);
        // attempts() is the count BEFORE this failed try; +1 makes it the count INCLUDING it.
        int attemptsIncludingThis = row.getAttempts() + 1;
        if (attemptsIncludingThis >= properties.maxAttempts()) {
            row.markFailed(now, reason);
            log.warn("Outbox event FAILED (DLQ) after {} attempt(s): eventId={} eventType={} reason={}",
                    attemptsIncludingThis, row.getPublicId(), row.getEventType(), reason);
        } else {
            Instant nextAttemptAt = now.plus(backoff(attemptsIncludingThis));
            row.scheduleRetry(nextAttemptAt, reason);
            log.info("Outbox event dispatch failed (attempt {}/{}), retry at {}: eventId={} eventType={} reason={}",
                    attemptsIncludingThis, properties.maxAttempts(), nextAttemptAt,
                    row.getPublicId(), row.getEventType(), reason);
        }
    }

    /**
     * Exponential backoff with jitter: {@code min(base * 2^(attempts-1), cap)} then a random reduction of
     * up to the full delay (full jitter). Jitter prevents a thundering-herd retry of a batch that failed
     * on a shared downstream dependency (PRD §21 DI3).
     *
     * @param attempts the attempt number this failure represents (1-based).
     * @return the delay before the next attempt.
     */
    private Duration backoff(int attempts) {
        long baseMillis = properties.backoffBase().toMillis();
        long capMillis = properties.backoffCap().toMillis();
        // 2^(attempts-1) without overflow: clamp the shift, then clamp the product to the cap.
        int shift = Math.min(attempts - 1, 30);
        long exp = baseMillis << shift;
        long ceiling = (exp <= 0 || exp > capMillis) ? capMillis : exp;
        // Full jitter in [0, ceiling): spreads retries so a shared-dependency batch does not retry in lockstep.
        long jittered = ThreadLocalRandom.current().nextLong(ceiling + 1);
        return Duration.ofMillis(jittered);
    }

    /**
     * Builds a short, PII-free failure reason from an exception — its class name plus message, with the
     * message clipped. Never a stack trace; callers (and the message itself) must not embed PII.
     *
     * @param ex the exception.
     * @return a redacted reason string suitable for {@code last_error}.
     */
    private static String redact(Throwable ex) {
        String message = ex.getMessage();
        String clipped = message == null ? "" : (message.length() > 256 ? message.substring(0, 256) : message);
        return ex.getClass().getSimpleName() + (clipped.isEmpty() ? "" : ": " + clipped);
    }
}
