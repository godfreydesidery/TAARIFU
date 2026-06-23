package com.taarifu.common.audit;

/**
 * The catalogue of append-only security/authz/identity events (AUTH-DESIGN §11.2, ADR-0011 §8, L-1).
 *
 * <p>Responsibility: enumerates exactly which security-relevant decisions are recorded to the immutable
 * {@code audit_event} store. Every value is a stable machine token (clients/SOC tooling branch on it);
 * codes are <b>append-only</b> — never repurpose a value's meaning. No raw PII is ever attached to an
 * event of any type — only references/hashes (PRD §18, PDPA).</p>
 *
 * <p>WHY an enum here (a {@code common} concern, not {@code identity}): every module audits through one
 * writer, so the catalogue lives in the shared kernel alongside {@link AuditEventService} (AUTH-DESIGN
 * §2 placement rationale). Verification-decision and role-lifecycle events are <i>defined</i> here so
 * the moderation/admin increments emit them consistently, even though those increments write them.</p>
 */
public enum AuditEventType {

    /** An OTP send was issued (ref = phone/email hash; never the code or raw target). */
    AUTH_OTP_REQUESTED,

    /** An OTP verify succeeded. */
    AUTH_OTP_VERIFIED,

    /** An OTP verify failed (reason-coded; never the code). */
    AUTH_OTP_FAILED,

    /** Signup completed → account active at T1. */
    AUTH_SIGNUP_COMPLETED,

    /** A login succeeded (password or OTP). */
    AUTH_LOGIN_SUCCEEDED,

    /** A login failed (reason-coded: bad credentials / unknown / disabled — uniform to the client). */
    AUTH_LOGIN_FAILED,

    /** A login was blocked by lockout/backoff (anti-automation signal, S-2). */
    AUTH_LOGIN_LOCKED,

    /** A refresh token was successfully rotated (single-use; new token minted). */
    AUTH_TOKEN_REFRESHED,

    /** A refresh token was rejected (unknown/forged/revoked). */
    AUTH_REFRESH_REJECTED,

    /** A refresh token was rejected because it had expired. */
    AUTH_REFRESH_EXPIRED,

    /** A consumed refresh token was re-presented — stolen-token signal (S-3, high priority). */
    AUTH_REFRESH_REUSE_DETECTED,

    /** A whole refresh-token family was revoked (consequence of reuse-detection / logout-all). */
    AUTH_FAMILY_REVOKED,

    /** A single-session logout (the presented refresh token revoked). */
    AUTH_LOGOUT,

    /** An all-sessions logout (every live refresh family for the account revoked). */
    AUTH_LOGOUT_ALL,

    /** A trust-tier transition (T0↔T1↔T2↔T3); from/to in the reason code (promotion + downgrade, §25.5). */
    AUTH_TIER_CHANGED,

    /** A citizen submitted ID/representative/organisation verification evidence (object-store ref). */
    AUTH_VERIFICATION_REQUESTED,

    /** A {@code @RequiresTier} gate denied an action (required vs live tier — MF-2 evidence). */
    AUTHZ_TIER_DENIED,

    /** A scope guard denied an action (area/category/constituency mismatch — MF-3). */
    AUTHZ_SCOPE_DENIED,

    /** A conflict-of-interest self-action was blocked (D13/D16). */
    AUTHZ_SELF_ACTION_BLOCKED,

    /** An identity was anonymised on erasure — a tombstone event; history is never mutated (§25.1). */
    IDENTITY_ERASED
}
