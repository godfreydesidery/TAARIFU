package com.taarifu.identity.domain.model.enums;

/**
 * The subject kind of a {@code VerificationRequest} (PRD §9.1).
 *
 * <p>Responsibility: distinguishes the three verification flows that share one queue: verifying a
 * citizen's government ID (the path to T3), validating a representative's claim to an office, and
 * confirming an organisation's legitimacy.</p>
 */
public enum VerificationType {

    /** Government-ID verification (NIDA/voter) → trust-tier T3 (PRD §7.3, D13). */
    ID,

    /** A representative's claim to an office (MP/Councillor/exec) — PRD §9.1. */
    REP_CLAIM,

    /** Organisation legitimacy verification. */
    ORG
}
