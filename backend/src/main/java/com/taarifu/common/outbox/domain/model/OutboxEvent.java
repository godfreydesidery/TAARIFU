package com.taarifu.common.outbox.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.common.outbox.OutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * The durable record of "something happened" — one transactional-outbox row (ADR-0014 §1, ADR-0008).
 *
 * <p>Responsibility: written by the producer inside its own {@code @Transactional} (via
 * {@link com.taarifu.common.outbox.OutboxWriter}) so the domain mutation and this event commit
 * atomically — a crash can never leave the domain row committed but the fan-out/routing intent lost
 * (PRD §15 DI3). An {@code @Scheduled} {@code OutboxRelay} later claims due {@link OutboxStatus#PENDING}
 * rows, dispatches them in-process to the registered {@code DomainEventHandler}s, and marks them
 * {@link OutboxStatus#PROCESSED} (or, after the attempt cap, {@link OutboxStatus#FAILED}).</p>
 *
 * <p>This entity extends {@link BaseEntity} because the outbox is shared-kernel <i>infrastructure</i>
 * (it lives in {@code common.outbox}, not any feature module's domain) and benefits from the standard
 * id + audit + optimistic-lock shape. The relay never soft-deletes a row; a separate retention job
 * hard-purges old {@link OutboxStatus#PROCESSED} rows (ADR-0014 operability).</p>
 *
 * <p><b>🔒 Privacy invariant (PRD §18, §12; ADR-0014 §1):</b> {@link #payload} holds <b>ids/codes/enums
 * ONLY — never PII</b>. The outbox is queryable, replayable, and dumped in support; no name, phone,
 * national/voter ID, OTP, free-text body, or raw GPS may ever be serialised into it. Consumers re-read
 * the aggregate by {@link #aggregateId} through the owner's {@code *QueryApi} (ADR-0013). {@link #lastError}
 * is likewise redacted — a truncated reason, never a stack trace or PII (ADR-0008 §5.2).</p>
 *
 * <p><b>Cross-module reference (ADR-0013 §3.2):</b> {@link #aggregateId} is a bare <i>public</i> UUID of
 * another module's aggregate — intentionally <b>not</b> a foreign key. The outbox is a leaf: nothing
 * FK-references it either.</p>
 *
 * <p>WHY no Lombok / a package-private factory: this is load-bearing reliability infrastructure; the
 * static {@link #pending} factory makes every newly-created row satisfy the PENDING invariant by
 * construction (status, attempts=0, next_attempt_at=now), and the state-transition methods
 * ({@link #markProcessed}, {@link #scheduleRetry}, {@link #markFailed}) are the only mutation surface so
 * the relay cannot leave a row in an inconsistent state (CLAUDE.md §8 — Lombok sparingly).</p>
 */
@Entity
@Table(name = "outbox_event", indexes = {
        @Index(name = "ix_outbox_event_aggregate", columnList = "aggregate_type, aggregate_id, occurred_at")
        // The hot partial index ix_outbox_event_due (WHERE status='PENDING') is created in V97 —
        // JPA @Index cannot express a partial index, so it is declared in the migration only.
})
public class OutboxEvent extends BaseEntity {

    /** The producing aggregate's type, e.g. {@code ANNOUNCEMENT}, {@code REPORT} (routing/diagnostics). */
    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    /** The producing aggregate's <b>public</b> id (UUID). Bare cross-module reference — never a FK (ADR-0013). */
    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    /** The taxonomy key handlers register on, e.g. {@code ANNOUNCEMENT_PUBLISHED} (dispatcher routes by exact match). */
    @Column(name = "event_type", nullable = false, length = 96)
    private String eventType;

    /**
     * The serialised {@code EventEnvelope} body as JSON. <b>🔒 ids/codes/enums ONLY — never PII</b>
     * (PRD §18). Mapped to a Postgres {@code jsonb} column via {@link SqlTypes#JSON} while remaining a
     * {@code String} in Java, so the kernel needs no JSON-object type binding and {@code ddl-auto=validate}
     * stays simple (the writer/relay own (de)serialisation through the shared {@code ObjectMapper}).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    /** Domain-time the thing happened (UTC), set by the producer. Best-effort ordering key (no global order). */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** Lifecycle state PENDING -> PROCESSED | FAILED. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OutboxStatus status;

    /** Dispatch attempts so far; drives the backoff schedule and the FAILED cutoff. */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** Earliest instant the relay may (re-)pick this row; the poller filters {@code next_attempt_at <= now()}. */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    /** Truncated, <b>redacted</b> last-failure reason for diagnostics — no PII, no stack trace. */
    @Column(name = "last_error", length = 1024)
    private String lastError;

    /** When the row reached {@link OutboxStatus#PROCESSED} or terminal {@link OutboxStatus#FAILED}. */
    @Column(name = "processed_at")
    private Instant processedAt;

    /** Maximum length persisted for {@link #lastError}, matching the column; longer reasons are truncated. */
    private static final int MAX_ERROR_LENGTH = 1024;

    /** JPA requires a no-arg constructor; application code uses {@link #pending}. */
    protected OutboxEvent() {
    }

    /**
     * Creates a fresh {@link OutboxStatus#PENDING} event due immediately, satisfying the PENDING
     * invariant by construction (attempts = 0, {@code nextAttemptAt} = {@code now}).
     *
     * @param aggregateType the producing aggregate type (e.g. {@code ANNOUNCEMENT}); never blank.
     * @param aggregateId   the producing aggregate's public id; never {@code null}.
     * @param eventType     the taxonomy key handlers register on; never blank.
     * @param payload       the serialised envelope JSON — <b>ids/codes/enums only, never PII</b>.
     * @param occurredAt    domain-time the event happened (UTC); never {@code null}.
     * @param now           the current instant (from the caller's clock), used as {@code nextAttemptAt}.
     * @return a transient, ready-to-persist PENDING outbox row.
     */
    public static OutboxEvent pending(String aggregateType, UUID aggregateId, String eventType,
                                      String payload, Instant occurredAt, Instant now) {
        OutboxEvent e = new OutboxEvent();
        e.aggregateType = aggregateType;
        e.aggregateId = aggregateId;
        e.eventType = eventType;
        e.payload = payload;
        e.occurredAt = occurredAt;
        e.status = OutboxStatus.PENDING;
        e.attempts = 0;
        e.nextAttemptAt = now;
        return e;
    }

    /**
     * Marks the row dispatched successfully: {@link OutboxStatus#PROCESSED} with {@code processedAt} set.
     * Idempotent at the row level — calling it again is harmless.
     *
     * @param now the completion instant (UTC).
     */
    public void markProcessed(Instant now) {
        this.status = OutboxStatus.PROCESSED;
        this.processedAt = now;
    }

    /**
     * Records a failed dispatch and schedules a retry: increments {@link #attempts}, stores the
     * (redacted, truncated) reason, keeps the row {@link OutboxStatus#PENDING}, and pushes
     * {@code nextAttemptAt} out by the supplied backoff.
     *
     * @param nextAttemptAt the next earliest dispatch instant (caller computes backoff-with-jitter).
     * @param redactedError a short, PII-free failure reason; truncated to the column length.
     */
    public void scheduleRetry(Instant nextAttemptAt, String redactedError) {
        this.attempts++;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = truncate(redactedError);
        // status stays PENDING — the relay will re-pick this row once nextAttemptAt is due.
    }

    /**
     * Marks the row terminally {@link OutboxStatus#FAILED} (the DLQ) after the attempt cap: increments
     * {@link #attempts} for the final failed try, stores the reason, and sets {@code processedAt}.
     *
     * @param now           the failure instant (UTC).
     * @param redactedError a short, PII-free failure reason; truncated to the column length.
     */
    public void markFailed(Instant now, String redactedError) {
        this.attempts++;
        this.status = OutboxStatus.FAILED;
        this.lastError = truncate(redactedError);
        this.processedAt = now;
    }

    /** Truncates a reason to {@link #MAX_ERROR_LENGTH} so it never overflows the column. */
    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    /** @return the producing aggregate type. */
    public String getAggregateType() {
        return aggregateType;
    }

    /** @return the producing aggregate's public id. */
    public UUID getAggregateId() {
        return aggregateId;
    }

    /** @return the taxonomy key handlers register on. */
    public String getEventType() {
        return eventType;
    }

    /** @return the serialised envelope JSON (ids/codes/enums only — never PII). */
    public String getPayload() {
        return payload;
    }

    /** @return the domain-time the event happened (UTC). */
    public Instant getOccurredAt() {
        return occurredAt;
    }

    /** @return the lifecycle state. */
    public OutboxStatus getStatus() {
        return status;
    }

    /** @return the number of dispatch attempts so far. */
    public int getAttempts() {
        return attempts;
    }

    /** @return the earliest instant the relay may (re-)pick this row. */
    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    /** @return the redacted last-failure reason, or {@code null}. */
    public String getLastError() {
        return lastError;
    }

    /** @return when the row reached a terminal state, or {@code null} while PENDING. */
    public Instant getProcessedAt() {
        return processedAt;
    }
}
