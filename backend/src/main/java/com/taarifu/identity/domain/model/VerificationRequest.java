package com.taarifu.identity.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.identity.domain.model.enums.VerificationStatus;
import com.taarifu.identity.domain.model.enums.VerificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

/**
 * A request to verify a subject's identity, representative claim, or organisation (PRD §9.1, §18;
 * ARCHITECTURE.md §7).
 *
 * <p>Responsibility: the record driving the operator-assisted verification queue that backs the
 * {@code IdentityVerificationProvider} port (the MVP path / degradation mode — EI-1/EI-2). A reviewer
 * (a {@link User}, by {@code publicId}) moves it through {@link VerificationStatus}. Approving an
 * {@link VerificationType#ID} request is what lifts a citizen to trust-tier T3 (PRD §7.3).</p>
 *
 * <p>WHY {@code evidenceRef} is a reference, not the document itself: identity documents are sensitive
 * PII held in the object store behind signed URLs and a virus-scan hook (EI-8); only a reference is
 * stored here, never the raw document, keeping PII out of the core table (PRD §18).</p>
 */
@Entity
@Table(name = "verification_request", indexes = {
        @Index(name = "ix_verification_subject", columnList = "subject_user_id"),
        @Index(name = "ix_verification_status", columnList = "status"),
        @Index(name = "ix_verification_type", columnList = "type")
})
@SQLRestriction("deleted = false")
public class VerificationRequest extends BaseEntity {

    /** The account being verified (FK). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_user_id", nullable = false)
    private User subject;

    /** What is being verified (ID, representative claim, organisation). */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private VerificationType type;

    /** Current review status. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private VerificationStatus status = VerificationStatus.PENDING;

    /**
     * Reference (object-store key) to the submitted evidence — never the document bytes (PRD §18).
     */
    @Column(name = "evidence_ref", length = 512)
    private String evidenceRef;

    /** Public id of the reviewing actor, or {@code null} while unassigned/pending. */
    @Column(name = "reviewer_public_id")
    private UUID reviewerPublicId;

    /** Reviewer's decision note / rejection reason; localised-key or free text (operator-facing). */
    @Column(name = "review_note", length = 1000)
    private String reviewNote;

    /** When the request was decided (UTC), or {@code null} while pending. */
    @Column(name = "decided_at")
    private Instant decidedAt;

    /** JPA requires a no-arg constructor; not for application use. */
    protected VerificationRequest() {
    }

    /**
     * Creates a new {@link VerificationStatus#PENDING} request routed to the operator-assisted queue
     * (Flow 2). The actual ID PII lives encrypted on the {@link Profile}; this request holds only the
     * subject, the kind, and an object-store {@code evidenceRef} — never document bytes (PRD §18).
     *
     * @param subject     the account being verified.
     * @param type        what is being verified (this increment wires {@link VerificationType#ID}).
     * @param evidenceRef object-store key to the submitted evidence, or {@code null} if out-of-band.
     * @return the populated, transient {@code PENDING} request.
     */
    public static VerificationRequest submit(User subject, VerificationType type, String evidenceRef) {
        VerificationRequest r = new VerificationRequest();
        r.subject = subject;
        r.type = type;
        r.status = VerificationStatus.PENDING;
        r.evidenceRef = evidenceRef;
        return r;
    }

    /**
     * Approves this request (Moderator decision — Flow 3). Records the reviewer, the optional note, and
     * the decision instant, and moves the status to {@link VerificationStatus#APPROVED}. The caller (the
     * review service) is responsible for the side effects (flip {@code idVerified}, set authoritative
     * electoral, audit) — this method only transitions the request's own state.
     *
     * @param reviewerPublicId the deciding Moderator's public id (multi-hat audit, D16).
     * @param note             an optional operator note.
     * @param now              the decision instant (UTC, from the injected clock).
     * @throws IllegalStateException if the request is not currently {@code PENDING} (no re-deciding).
     */
    public void approve(UUID reviewerPublicId, String note, Instant now) {
        requirePending();
        this.status = VerificationStatus.APPROVED;
        this.reviewerPublicId = reviewerPublicId;
        this.reviewNote = note;
        this.decidedAt = now;
    }

    /**
     * Rejects this request (Moderator decision — Flow 3) with a reason code. The subject's tier is left
     * untouched (a rejection never grants nor removes tier; {@code idVerified} stays false).
     *
     * @param reviewerPublicId the deciding Moderator's public id.
     * @param reasonCode       the machine rejection reason (stored in {@link #reviewNote}'s lead).
     * @param note             an optional operator note appended after the reason code.
     * @param now              the decision instant (UTC).
     * @throws IllegalStateException if the request is not currently {@code PENDING}.
     */
    public void reject(UUID reviewerPublicId, String reasonCode, String note, Instant now) {
        requirePending();
        this.status = VerificationStatus.REJECTED;
        this.reviewerPublicId = reviewerPublicId;
        // Store the machine reason first so it is greppable; the free-text note (if any) follows.
        this.reviewNote = note == null ? reasonCode : reasonCode + ": " + note;
        this.decidedAt = now;
    }

    /** Guards the single legal transition source: a decision may only be made on a PENDING request. */
    private void requirePending() {
        if (this.status != VerificationStatus.PENDING) {
            throw new IllegalStateException("Verification request is not PENDING: " + this.status);
        }
    }

    /** @return the account being verified. */
    public User getSubject() {
        return subject;
    }

    /** @return the verification kind. */
    public VerificationType getType() {
        return type;
    }

    /** @return the review status. */
    public VerificationStatus getStatus() {
        return status;
    }

    /** @return the object-store reference to the evidence, or {@code null}. */
    public String getEvidenceRef() {
        return evidenceRef;
    }

    /** @return the reviewer's public id, or {@code null}. */
    public UUID getReviewerPublicId() {
        return reviewerPublicId;
    }

    /** @return the reviewer's note, or {@code null}. */
    public String getReviewNote() {
        return reviewNote;
    }

    /** @return when the request was decided, or {@code null}. */
    public Instant getDecidedAt() {
        return decidedAt;
    }
}
