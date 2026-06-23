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
