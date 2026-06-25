-- =============================================================================
-- V142__audit_event_type_privacy.sql
--   Widen the audit_event.event_type domain (ck_audit_event_type) to admit the new
--   PDPA privacy/DSR audit events (ADR-0016 §7): PRIVACY_CONSENT_CHANGED,
--   PRIVACY_DSR_RECEIVED, PRIVACY_DSR_EXPORTED, PRIVACY_ERASURE_REQUESTED.
--
-- WHY (ADR-0016 PDPA consent + DSR): the privacy module now records every consent
-- decision, every data-subject request, every export, and every erasure-request as an
-- immutable, append-only audit row (PRD §18, L-1) — references/codes only, never PII.
-- The com.taarifu.common.audit.AuditEventType enum carries the four new codes; this
-- migration lets the database accept them, so an emitted privacy audit never fails the
-- CHECK at runtime (the V104 drift it explicitly guards against). IDENTITY_ERASED — the
-- per-row identity tombstone the erasure handler appends — already exists in the catalogue
-- (added in V5); the audit hash-chain is EXTENDED by that append, never broken (§25.1).
--
-- WHY drop-and-recreate the single constraint (the V96/V103/V104 pattern): keeping ONE
-- ck_audit_event_type as the single source of truth for the allowed set is clearer than
-- accumulating numbered CHECKs (KISS). The set below is the FULL current AuditEventType
-- catalogue — the V104 set PLUS the four new privacy events. APPEND-ONLY: never remove or
-- repurpose a code (L-1). Drop-then-add is metadata-only and validates instantly (every
-- existing row already holds a value from this strictly-larger set); audit_event is
-- INSERT-only, so the brief lock blocks nothing in practice.
--
-- Forward-only; never edit once applied — add a new migration (CLAUDE.md §12).
-- =============================================================================

ALTER TABLE audit_event DROP CONSTRAINT IF EXISTS ck_audit_event_type;

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
    -- Moderation
    'MODERATION_ACTION_TAKEN', 'MODERATION_APPEAL_RESOLVED',
    -- Operations
    'OUTBOX_DLQ_REPLAYED',
    -- Privacy / PDPA data-subject rights (ADR-0016 §7) — references/codes only, never PII
    'PRIVACY_CONSENT_CHANGED',     -- a consent grant/withdrawal (reason = <purpose>:<state>)
    'PRIVACY_DSR_RECEIVED',        -- an ACCESS/ERASURE request received (reason = DsrType)
    'PRIVACY_DSR_EXPORTED',        -- a data-subject export generated/delivered
    'PRIVACY_ERASURE_REQUESTED'    -- an erasure requested + ERASURE_REQUESTED fan-out published
));

COMMENT ON COLUMN audit_event.event_type IS 'AuditEventType from the §11.2 catalogue; stable machine token SOC tooling branches on. The ck_audit_event_type guard is APPEND-ONLY — widened by V6 (verification/electoral/MFA), V96 (binding-act successes), V103 (user-admin), V104 (outbox DLQ), and V142 (PDPA privacy/DSR events). Never repurpose a code (L-1).';
