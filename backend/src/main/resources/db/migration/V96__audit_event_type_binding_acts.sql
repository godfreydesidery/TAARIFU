-- =============================================================================
-- V96__audit_event_type_binding_acts.sql
--   Widen the audit_event.event_type domain (ck_audit_event_type) to admit the
--   binding-civic-act SUCCESS events PETITION_SIGNED and RATING_SUBMITTED (R-4),
--   completing the immutable trail for the most sensitive civic acts.
--
-- WHY (R-4, security review wiring-increment-review.md): denials on the binding
-- paths were already audited (AUTHZ_SCOPE_DENIED / AUTHZ_SELF_ACTION_BLOCKED), but
-- a successful petition-sign / rating-submit emitted no audit row — the trail for
-- the highest-integrity actions was incomplete. The AuditEventType enum now carries
-- PETITION_SIGNED + RATING_SUBMITTED; this migration lets the database accept them.
-- References/public-ids only on those events — never PII (PRD §18, PDPA, L-1).
--
-- WHY drop-and-recreate one constraint named ck_audit_event_type (the V6 pattern,
-- not ck_audit_event_type_2/_3): keeping ONE constraint as the single source of
-- truth for the allowed set is clearer than accumulating numbered CHECKs (KISS).
-- The set below is the FULL current AuditEventType catalogue — the V5/V6 set PLUS
-- the moderation events (MODERATION_ACTION_TAKEN / MODERATION_APPEAL_RESOLVED, which
-- the enum and the moderation module already use) PLUS the two new binding-act
-- events. The catalogue is APPEND-ONLY: never remove or repurpose a code (L-1).
-- Drop-then-add is metadata-only; the recreated constraint validates instantly
-- because every existing row already holds a value from this (strictly larger) set,
-- and the brief ACCESS EXCLUSIVE lock only blocks writes for the instant of the
-- catalog update (audit_event is INSERT-only, low contention).
--
-- Forward-only; never edit once applied — add a new migration (CLAUDE.md §12).
-- =============================================================================

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_type;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_type CHECK (event_type IN (
    -- ---- original V5 catalogue ----
    'AUTH_OTP_REQUESTED','AUTH_OTP_VERIFIED','AUTH_OTP_FAILED','AUTH_SIGNUP_COMPLETED',
    'AUTH_LOGIN_SUCCEEDED','AUTH_LOGIN_FAILED','AUTH_LOGIN_LOCKED','AUTH_TOKEN_REFRESHED',
    'AUTH_REFRESH_REJECTED','AUTH_REFRESH_EXPIRED','AUTH_REFRESH_REUSE_DETECTED','AUTH_FAMILY_REVOKED',
    'AUTH_LOGOUT','AUTH_LOGOUT_ALL','AUTH_TIER_CHANGED','AUTH_VERIFICATION_REQUESTED',
    'AUTHZ_TIER_DENIED','AUTHZ_SCOPE_DENIED','AUTHZ_SELF_ACTION_BLOCKED','IDENTITY_ERASED',
    -- ---- added by V6 (verification / electoral / MFA) ----
    'AUTH_VERIFICATION_APPROVED','AUTH_VERIFICATION_REJECTED','ELECTORAL_CHANGED',
    'AUTH_LOGIN_MFA','AUTH_MFA_ENROLLED','AUTH_MFA_CHALLENGE_FAILED',
    -- ---- moderation decision trail (AuditEventType already carries these; kept in the single
    --      source-of-truth set so the recreated constraint never narrows the catalogue) ----
    'MODERATION_ACTION_TAKEN','MODERATION_APPEAL_RESOLVED',
    -- ---- added by THIS migration (R-4): binding-civic-act SUCCESS events ----
    'PETITION_SIGNED',              -- a petition was signed (actor=signer, subject=petition; reason=target type)
    'RATING_SUBMITTED'));           -- a binding rating was submitted/revised (actor=rater, subject=rated; reason=<subjectType>:<period>)

COMMENT ON COLUMN audit_event.event_type IS 'AuditEventType from the §11.2 catalogue; stable machine token SOC tooling branches on. The ck_audit_event_type guard is APPEND-ONLY — widened by V6 (verification/electoral/MFA) and V96 (binding-act successes PETITION_SIGNED/RATING_SUBMITTED, R-4). Never repurpose a code (L-1).';
