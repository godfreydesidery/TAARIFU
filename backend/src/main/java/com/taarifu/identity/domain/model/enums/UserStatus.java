package com.taarifu.identity.domain.model.enums;

/**
 * Lifecycle status of a {@code User} account (PRD §9.1).
 *
 * <p>Responsibility: governs whether an account can authenticate and act. Distinct from trust tier
 * (which governs <i>what</i> a verified citizen may do) — status governs whether the account is
 * usable at all.</p>
 */
public enum UserStatus {

    /** Created but not yet activated (e.g. OTP signup not completed). Cannot fully act. */
    PENDING,

    /** Normal, usable account. */
    ACTIVE,

    /** Temporarily blocked (e.g. by moderation/safety action); recoverable. */
    SUSPENDED,

    /** Permanently disabled; cannot authenticate. */
    DISABLED
}
