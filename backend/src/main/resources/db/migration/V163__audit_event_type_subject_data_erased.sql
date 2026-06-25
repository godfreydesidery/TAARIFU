-- =============================================================================
-- V163__audit_event_type_subject_data_erased.sql
--   Widen the audit_event.event_type domain (ck_audit_event_type) to admit the new
--   cross-module DSR-fan-out severing audit event (ADR-0016 §5/§7): SUBJECT_DATA_ERASED.
--
-- WHY (ADR-0016 PDPA DSR fan-out): wave-1 shipped the privacy DSR machinery + the
-- IDENTITY erasure tombstone (IDENTITY_ERASED). This increment fans the ERASURE out to the
-- remaining footprint owners — reporting, engagement, media, accountability. Each owning
-- module's erasure handler appends ONE SUBJECT_DATA_ERASED row when it severs/de-identifies
-- its share, so the immutable audit log proves the whole subject footprint was covered,
-- module by module (PRD §18, §25.1, L-1) — references/counts only, never PII. The
-- com.taarifu.common.audit.AuditEventType enum carries the new code; this migration lets the
-- database accept it so an emitted severing audit never fails the CHECK at runtime (the
-- drift V142's comment guards against). The audit hash-chain is EXTENDED by each append,
-- never broken (§25.1).
--
-- WHY drop-and-recreate the single constraint (the V96/V103/V104/V142 pattern): keeping ONE
-- ck_audit_event_type as the single source of truth for the allowed set is clearer than
-- accumulating numbered CHECKs (KISS). The set below is the FULL current AuditEventType
-- catalogue — the V142 set PLUS the one new severing event. APPEND-ONLY: never remove or
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
    'PRIVACY_ERASURE_REQUESTED',   -- an erasure requested + ERASURE_REQUESTED fan-out published
    'SUBJECT_DATA_ERASED'          -- a feature module severed its share on the ERASURE fan-out (reason = <module>:<counts>)
));

COMMENT ON COLUMN audit_event.event_type IS 'AuditEventType from the §11.2 catalogue; stable machine token SOC tooling branches on. The ck_audit_event_type guard is APPEND-ONLY — widened by V6 (verification/electoral/MFA), V96 (binding-act successes), V103 (user-admin), V104 (outbox DLQ), V142 (PDPA privacy/DSR events), and V163 (cross-module DSR-fan-out severing). Never repurpose a code (L-1).';
