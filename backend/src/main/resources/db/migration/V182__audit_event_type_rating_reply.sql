-- =============================================================================
-- V182__audit_event_type_rating_reply.sql
--   Widen the audit_event.event_type domain (ck_audit_event_type) to admit the
--   representative RIGHT-OF-REPLY success event RATING_REPLY_POSTED (accountability,
--   M6, US-6.2 — the D-rated-fairness rule).
--
-- WHY: posting a representative's right-of-reply (self or curated-on-behalf) is a
-- citizen-visible accountability action that must carry an immutable trail, like the
-- binding-act successes PETITION_SIGNED / RATING_SUBMITTED (R-4). The AuditEventType
-- enum now carries RATING_REPLY_POSTED; this migration lets the database accept it.
-- actor = the replying principal, subject = the rated representative, reason_code =
-- the reply mode (SELF / CURATED). References/codes only — never the reply body, the
-- score/comment, or any PII (PRD §18, PDPA, L-1).
--
-- Drop-and-recreate the ONE constraint named ck_audit_event_type (the V96/V163
-- pattern): keeping a single constraint as the source of truth for the allowed set is
-- clearer than accumulating numbered CHECKs (KISS). The set below is the FULL current
-- AuditEventType catalogue — the V163 set PLUS RATING_REPLY_POSTED. APPEND-ONLY: never
-- remove or repurpose a code (L-1). Drop-then-add is metadata-only; the recreated
-- constraint validates instantly because every existing row already holds a value from
-- this (strictly larger) set, and the brief ACCESS EXCLUSIVE lock blocks writes for
-- only the instant of the catalog update (audit_event is INSERT-only, low contention).
--
-- Forward-only; never edit once applied — add a new migration (CLAUDE.md §12).
-- =============================================================================

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_type;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_type CHECK (event_type IN (
    -- Authentication / session
    'AUTH_OTP_REQUESTED', 'AUTH_OTP_VERIFIED', 'AUTH_OTP_FAILED',
    'AUTH_SIGNUP_COMPLETED', 'AUTH_LOGIN_SUCCEEDED', 'AUTH_LOGIN_FAILED',
    'AUTH_LOGIN_LOCKED', 'AUTH_LOGIN_MFA', 'AUTH_MFA_ENROLLED', 'AUTH_MFA_CHALLENGE_FAILED',
    'AUTH_TOKEN_REFRESHED', 'AUTH_REFRESH_REJECTED', 'AUTH_REFRESH_EXPIRED',
    'AUTH_REFRESH_REUSE_DETECTED', 'AUTH_FAMILY_REVOKED', 'AUTH_LOGOUT', 'AUTH_LOGOUT_ALL',
    'AUTH_TIER_CHANGED',
    -- Verification / identity
    'AUTH_VERIFICATION_REQUESTED', 'AUTH_VERIFICATION_APPROVED', 'AUTH_VERIFICATION_REJECTED',
    'ELECTORAL_CHANGED', 'IDENTITY_ERASED',
    -- Authorization
    'AUTHZ_SCOPE_DENIED', 'AUTHZ_TIER_DENIED', 'AUTHZ_SELF_ACTION_BLOCKED',
    -- Role / user administration
    'ROLE_GRANTED', 'ROLE_REVOKED', 'USER_SUSPENDED', 'USER_REINSTATED',
    -- Civic acts
    'PETITION_SIGNED', 'RATING_SUBMITTED',
    'RATING_REPLY_POSTED',         -- a representative's right-of-reply posted (reason = SELF | CURATED) — THIS migration
    -- Moderation
    'MODERATION_ACTION_TAKEN', 'MODERATION_APPEAL_RESOLVED',
    -- Operations
    'OUTBOX_DLQ_REPLAYED',
    -- Privacy / PDPA data-subject rights (ADR-0016 §7) — references/codes only, never PII
    'PRIVACY_CONSENT_CHANGED',     -- a consent grant/withdrawal (reason = <purpose>:<state>)
    'PRIVACY_DSR_RECEIVED',        -- an ACCESS/ERASURE request received (reason = DsrType)
    'PRIVACY_DSR_EXPORTED',        -- a data-subject export generated/delivered
    'PRIVACY_ERASURE_REQUESTED',   -- an erasure requested + ERASURE_REQUESTED fan-out published
    'SUBJECT_DATA_ERASED'          -- a feature module severed its share on the ERASURE fan-out (reason = <module>:<counts>)
));

COMMENT ON COLUMN audit_event.event_type IS 'AuditEventType from the §11.2 catalogue; stable machine token SOC tooling branches on. The ck_audit_event_type guard is APPEND-ONLY — widened by V6 (verification/electoral/MFA), V96 (binding-act successes), V103 (user-admin), V104 (outbox DLQ), V142 (PDPA privacy/DSR events), V163 (cross-module DSR-fan-out severing), and V182 (representative right-of-reply RATING_REPLY_POSTED). Never repurpose a code (L-1).';
