package com.taarifu.privacy.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.privacy.domain.model.enums.DsrStatus;
import com.taarifu.privacy.domain.model.enums.DsrType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

/**
 * A tracked <b>data-subject request</b> — an ACCESS (export) or ERASURE (right-to-be-forgotten) demand —
 * with the PDPA SLA and legal-hold state (PRD §18, §25.1, UC-A17/UC-S09; ADR-0016 §3).
 *
 * <p>Responsibility: the auditable proof that the controller honoured a data-subject right within SLA
 * (acknowledge ≤72h, complete ≤30 days — §25.1). The request is persisted because fulfilment — especially
 * erasure — is <b>asynchronous</b> (it fans out across modules via the {@code ERASURE_REQUESTED} outbox
 * event) and the controller must be able to demonstrate completion. A <b>legal hold</b> ({@link #legalHold})
 * parks an erasure at {@link DsrStatus#ON_HOLD}: an item under investigation is exempt from erasure until
 * released (§25.1).</p>
 *
 * <p><b>🔒 No PII (PRD §18):</b> the subject is identified by their <b>authenticated account public id</b>
 * ({@link #subjectPublicId} — an opaque cross-module reference to {@code identity}, never an FK), never by a
 * re-submitted name/phone/ID. {@link #reasonCode} is a machine token (e.g. {@code SUBJECT_UNVERIFIABLE}),
 * never free text or PII.</p>
 */
@Entity
@Table(name = "data_subject_request", indexes = {
        @Index(name = "ix_dsr_subject", columnList = "subject_public_id"),
        // The operator queue: open requests by status, oldest-due first.
        @Index(name = "ix_dsr_status_due", columnList = "status, due_at")
})
@SQLRestriction("deleted = false")
public class DataSubjectRequest extends BaseEntity {

    /**
     * The requesting account's public id (the JWT-subject grain — self-asserted, never a body id). A bare
     * cross-module reference to {@code identity}, deliberately not an FK (ADR-0013 §3.2).
     */
    @Column(name = "subject_public_id", nullable = false)
    private UUID subjectPublicId;

    /** Which data-subject right is being exercised (ACCESS or ERASURE). */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private DsrType type;

    /** The request lifecycle state (drives the SLA workflow and the operator queue). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DsrStatus status = DsrStatus.RECEIVED;

    /** When the request was received (UTC); the SLA clock origin. */
    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    /** When the request was acknowledged to the subject (UTC), or {@code null} (PRD §25.1 ≤72h). */
    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    /** When the request was fully fulfilled or terminally closed (UTC), or {@code null}. */
    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * The computed completion deadline (UTC) — {@code requestedAt + 30 days} (PRD §25.1) — surfaced to the
     * operator dashboard so an at-risk request is visible. Not PII.
     */
    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    /**
     * {@code true} while a legal hold suspends fulfilment (an item under investigation is exempt from
     * erasure — §25.1). Set/cleared by an ADMIN/ROOT operator; the erasure handler must honour it.
     */
    @Column(name = "legal_hold", nullable = false)
    private boolean legalHold = false;

    /** Machine reason code (e.g. on rejection or hold); never free text or PII. */
    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    /** JPA requires a no-arg constructor; application code uses {@link #open}. */
    protected DataSubjectRequest() {
    }

    /**
     * Opens a new request in {@link DsrStatus#RECEIVED} with the SLA deadline computed.
     *
     * @param subjectPublicId the requesting account's public id.
     * @param type            ACCESS or ERASURE.
     * @param now             the receipt instant (UTC, from the injected clock).
     * @param completeWithin  the completion SLA window (PRD §25.1 ≤30 days); {@code dueAt = now + this}.
     * @return the populated, transient request.
     */
    public static DataSubjectRequest open(UUID subjectPublicId, DsrType type, Instant now,
                                          java.time.Duration completeWithin) {
        DataSubjectRequest r = new DataSubjectRequest();
        r.subjectPublicId = subjectPublicId;
        r.type = type;
        r.status = DsrStatus.RECEIVED;
        r.requestedAt = now;
        r.dueAt = now.plus(completeWithin);
        return r;
    }

    /** Acknowledges the request to the subject (≤72h obligation — §25.1). */
    public void acknowledge(Instant now) {
        this.status = DsrStatus.ACKNOWLEDGED;
        this.acknowledgedAt = now;
    }

    /** Marks fulfilment underway (export assembling / erasure fan-out dispatched). */
    public void markInProgress() {
        this.status = DsrStatus.IN_PROGRESS;
    }

    /** Places the request under legal hold (suspends erasure until released — §25.1). */
    public void placeOnHold(String reasonCode) {
        this.legalHold = true;
        this.status = DsrStatus.ON_HOLD;
        this.reasonCode = reasonCode;
    }

    /** Releases a legal hold, returning the request to {@link DsrStatus#IN_PROGRESS} for fulfilment. */
    public void releaseHold() {
        this.legalHold = false;
        this.status = DsrStatus.IN_PROGRESS;
    }

    /** Marks the request fully fulfilled / terminally closed (UTC). */
    public void complete(Instant now) {
        this.status = DsrStatus.COMPLETED;
        this.completedAt = now;
    }

    /** Rejects the request with a machine reason code (terminal). */
    public void reject(String reasonCode, Instant now) {
        this.status = DsrStatus.REJECTED;
        this.reasonCode = reasonCode;
        this.completedAt = now;
    }

    /** @return the requesting account's public id. */
    public UUID getSubjectPublicId() {
        return subjectPublicId;
    }

    /** @return ACCESS or ERASURE. */
    public DsrType getType() {
        return type;
    }

    /** @return the request lifecycle state. */
    public DsrStatus getStatus() {
        return status;
    }

    /** @return when the request was received (UTC). */
    public Instant getRequestedAt() {
        return requestedAt;
    }

    /** @return when acknowledged (UTC), or {@code null}. */
    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    /** @return when completed/closed (UTC), or {@code null}. */
    public Instant getCompletedAt() {
        return completedAt;
    }

    /** @return the completion SLA deadline (UTC). */
    public Instant getDueAt() {
        return dueAt;
    }

    /** @return whether a legal hold currently suspends fulfilment. */
    public boolean isLegalHold() {
        return legalHold;
    }

    /** @return the machine reason code, or {@code null}. */
    public String getReasonCode() {
        return reasonCode;
    }
}
