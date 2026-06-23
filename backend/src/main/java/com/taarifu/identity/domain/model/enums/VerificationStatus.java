package com.taarifu.identity.domain.model.enums;

/**
 * Status of a {@code VerificationRequest} (PRD §9.1, §18; ARCHITECTURE.md §7).
 *
 * <p>Responsibility: tracks an identity/representative/organisation verification through the
 * operator-assisted review queue (the MVP {@code IdentityVerificationProvider} degradation path —
 * EI-1/EI-2). A pending review must <b>not</b> cause loss of an already-earned tier (PRD §21).</p>
 */
public enum VerificationStatus {

    /** Submitted, awaiting reviewer action. */
    PENDING,

    /** Approved by a reviewer; the subject is verified. */
    APPROVED,

    /** Rejected by a reviewer (with reason). */
    REJECTED,

    /** Reviewer needs more information before deciding. */
    MORE_INFO
}
