package com.taarifu.common.audit;

/**
 * The outcome dimension of an {@link AuditEvent} (AUTH-DESIGN §11.1).
 *
 * <p>Responsibility: lets a single {@link AuditEventType} (e.g. an OTP verify) record whether the
 * decision succeeded, failed on the user's input, or was denied by an authorization gate — without a
 * separate event type per outcome. SOC tooling filters on {@code (event_type, outcome)}.</p>
 */
public enum AuditOutcome {

    /** The action completed successfully. */
    SUCCESS,

    /** The action failed (bad input, expired challenge, wrong credential). */
    FAILURE,

    /** The action was denied by an authorization/integrity gate (tier/scope/conflict/lockout). */
    DENIED
}
