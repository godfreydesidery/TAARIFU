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

    /** A Moderator approved an ID/rep/org verification request (actor=reviewer, subject=citizen, D16). */
    AUTH_VERIFICATION_APPROVED,

    /** A Moderator rejected a verification request ({@code reason_code} = rejection reason). */
    AUTH_VERIFICATION_REJECTED,

    /**
     * The single {@code isElectoral} location was set/changed (manual or voter-ID-authoritative); the
     * reason code is one of {@code MANUAL}, {@code VOTER_ID_AUTHORITATIVE}, {@code REDELIMITATION}, or
     * (on a denied attempt) {@code COOLDOWN}/{@code AUTHORITATIVE_LOCKED} — voter-ID-authoritative,
     * cooldown-guarded (D13, §25.4).
     */
    ELECTORAL_CHANGED,

    /** A staff account completed the TOTP second factor at login ({@code reason_code="TOTP"}, N-4). */
    AUTH_LOGIN_MFA,

    /** TOTP MFA was activated for an account ({@code mfa_enabled=true}) — staff second factor enrolled. */
    AUTH_MFA_ENROLLED,

    /** A TOTP code was wrong at login/activate (anti-automation signal, S-2/N-4). */
    AUTH_MFA_CHALLENGE_FAILED,

    /** A {@code @RequiresTier} gate denied an action (required vs live tier — MF-2 evidence). */
    AUTHZ_TIER_DENIED,

    /** A scope guard denied an action (area/category/constituency mismatch — MF-3). */
    AUTHZ_SCOPE_DENIED,

    /** A conflict-of-interest self-action was blocked (D13/D16). */
    AUTHZ_SELF_ACTION_BLOCKED,

    /**
     * A petition was successfully signed — a binding civic act (engagement; R-4, PRD §23.5/§12.2).
     * actor = signer account publicId, subject = petition publicId, {@code reason_code} = the petition
     * target type (e.g. {@code REPRESENTATIVE}/{@code OFFICE}). The most sensitive civic acts carry a
     * complete immutable success trail (not only denials). No comment, signature content, or PII is attached
     * (PRD §18, PDPA).
     */
    PETITION_SIGNED,

    /**
     * A binding rating was successfully submitted/revised — a binding civic act (accountability; R-4, PRD
     * §23.5/§10 US-6.2). actor = rater account publicId, subject = rated subject publicId,
     * {@code reason_code} = {@code <subjectType>:<period>}. No score, comment, or PII is attached
     * (PRD §18, PDPA).
     */
    RATING_SUBMITTED,

    /**
     * A moderator took an append-only moderation action on a queue item (actor=moderator,
     * subject=content author; {@code reason_code} = the moderation action taken,
     * e.g. {@code REMOVE}/{@code HIDE}/{@code APPROVE}). The immutable {@code moderation_action} row is the
     * primary decision trail; this event mirrors it into the unified audit store so SOC tooling sees every
     * state-changing moderation decision in one stream (PRD §18, §25.8). No content body or PII is attached.
     */
    MODERATION_ACTION_TAKEN,

    /**
     * A moderation appeal was resolved (actor=deciding moderator, subject=appellant; {@code reason_code} =
     * the appeal outcome, e.g. {@code UPHELD}/{@code OVERTURNED}). Decided by a <b>different</b> moderator
     * than the one who took the original action (appeal independence, D16/§25.8). An {@code OVERTURNED}
     * outcome is the signal for a new reversing action — history is never mutated (append-only).
     */
    MODERATION_APPEAL_RESOLVED,

    /** An identity was anonymised on erasure — a tombstone event; history is never mutated (§25.1). */
    IDENTITY_ERASED,

    /**
     * A feature module severed/de-identified its share of a data subject's footprint on an ERASURE fan-out
     * (PRD §18, §25.1; ADR-0016 §5/§7 — the cross-module DSR fan-out). actor = SYSTEM (or the erasing
     * account), subject = the erased account; {@code reason_code} names the module + the severing and counts,
     * e.g. {@code reporting:reports=3,events=2}, {@code engagement:signatures=1,petitions=0,...},
     * {@code media:objects=4}, {@code accountability:ratings=2}, plus the originating {@code DSR:<id>}.
     *
     * <p>WHY one shared per-module code (not one per module): each owning module appends exactly this row when
     * its erasure handler runs, so the audit log proves the whole subject footprint was covered, module by
     * module — the auditable companion to the single {@link #IDENTITY_ERASED} identity tombstone. The
     * hash-chain is EXTENDED by the append, never broken (§25.1, L-1). References/counts only — never PII.
     * Append-only; never repurpose.</p>
     */
    SUBJECT_DATA_ERASED,

    /**
     * A citizen recorded a consent decision (grant or withdrawal) for a processing purpose (PRD §18 PDPA,
     * UC-A16; ADR-0016 §2/§7). actor = subject = the deciding account; {@code reason_code} =
     * {@code <purpose>:<state>} (e.g. {@code BEHAVIOURAL_ANALYTICS:WITHDRAWN}). References/codes only —
     * never PII. Append-only; never repurpose.
     */
    PRIVACY_CONSENT_CHANGED,

    /**
     * A data-subject request (ACCESS or ERASURE) was received (PRD §18 PDPA, UC-A17/UC-S09; ADR-0016 §3/§7).
     * actor = subject = the requesting account; {@code reason_code} = the {@code DsrType}. The auditable proof
     * the right was exercised. References only — never PII.
     */
    PRIVACY_DSR_RECEIVED,

    /**
     * A data-subject ACCESS export was generated/delivered (PRD §18 PDPA right of access; ADR-0016 §4/§7).
     * actor = the principal that ran it (the subject self-service, or an ADMIN/ROOT on a tracked DSR),
     * subject = the account exported. References only — never the exported PII itself.
     */
    PRIVACY_DSR_EXPORTED,

    /**
     * An ERASURE was requested and the {@code ERASURE_REQUESTED} fan-out event was published (PRD §18, §25.1;
     * ADR-0016 §5/§7). actor = subject = the erasing account. Precedes the per-module severing
     * ({@link #IDENTITY_ERASED} et al.). References only — never PII.
     */
    PRIVACY_ERASURE_REQUESTED,

    /**
     * An admin granted a role to an account additively (M14, US-14.1, D15; actor = the granting admin,
     * subject = the target account, {@code reason_code} = the granted {@code RoleName}). The canonical
     * "who granted what to whom" trail for back-office RBAC changes. References/public-ids only — never PII
     * (PRD §18, PDPA, L-1). Append-only; never repurpose.
     */
    ROLE_GRANTED,

    /**
     * An admin revoked (end-dated) an account's role grant (M14, US-14.1; actor = the revoking admin,
     * subject = the target account, {@code reason_code} = the revoked {@code RoleName}). The grant is set
     * {@code FORMER} (never hard-deleted, §6.4/§18). References only — never PII.
     */
    ROLE_REVOKED,

    /**
     * An admin suspended an account (M14, US-14.1; actor = the acting admin, subject = the suspended
     * account, {@code reason_code} = the optional machine suspension reason, or {@code null}). The account
     * can no longer authenticate/act until reinstated (recoverable). References only — never PII.
     */
    USER_SUSPENDED,

    /**
     * An admin reinstated a suspended account back to {@code ACTIVE} (M14, US-14.1; actor = the acting
     * admin, subject = the reinstated account). References only — never PII.
     */
    USER_REINSTATED,

    /**
     * An admin re-queued terminally FAILED transactional-outbox event(s) from the dead-letter queue back to
     * PENDING for reprocessing (M14, P3-1; ADR-0014 revisit trigger (c)). Actor = the acting admin; subject =
     * the target event public id for a single-id replay, or {@code null} for a bounded-window replay;
     * {@code reason_code} names the mode + reference/scope and the count actually re-queued — e.g.
     * {@code BY_ID:<uuid>:n=1}, {@code BY_WINDOW:REPORT_ROUTED:n=12}, {@code BY_WINDOW:ALL:n=0}. References +
     * counts only — never an event payload or any PII (PRD §18, L-1). Append-only; never repurpose.
     */
    OUTBOX_DLQ_REPLAYED,

    /**
     * A representative posted (or an authorised curator posted on their behalf) their <b>right-of-reply</b> to
     * a rating about them - the D-rated-fairness rule (accountability; PRD &sect;10 US-6.2). actor = the
     * replying principal (the representative's linked account, or an {@code ADMIN}/{@code ROOT} curator acting
     * on-behalf), subject = the rated representative's public id; {@code reason_code} = the reply mode
     * ({@code SELF} or {@code CURATED}). At most one reply per rating (the one-per-rating fairness cap,
     * enforced by a DB unique). No reply text, score, comment, or PII is attached - references/codes only
     * (PRD &sect;18, PDPA, L-1). Append-only; never repurpose.
     */
    RATING_REPLY_POSTED
}
