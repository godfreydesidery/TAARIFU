-- =============================================================================
-- V103__audit_event_type_user_admin.sql
--   Widen the audit_event.event_type domain (ck_audit_event_type) to admit the
--   back-office user-administration events ROLE_GRANTED, ROLE_REVOKED,
--   USER_SUSPENDED, USER_REINSTATED (M14, US-14.1, UC-H06).
--
-- WHY (M14 admin user-management + additive role granting): the admin console can
-- now grant/revoke roles (D15 additive) and suspend/reinstate accounts via the
-- identity module (UserAdminApi). Each of those state changes must leave a canonical,
-- immutable trail — "who granted/revoked which role for whom", "who suspended whom"
-- (PRD §18, L-1). The AuditEventType enum now carries the four new codes; this
-- migration lets the database accept them. References/public-ids only on those events
-- (actor = the acting admin, subject = the target account, reason_code = the RoleName
-- or the suspension reason) — never PII.
--
-- WHY drop-and-recreate the single constraint ck_audit_event_type (the V6/V96 pattern,
-- not ck_audit_event_type_2/_3): keeping ONE constraint as the single source of truth
-- for the allowed set is clearer than accumulating numbered CHECKs (KISS). The set
-- below is the FULL current AuditEventType catalogue — the V5/V6/V96 set PLUS the four
-- new user-admin events. The catalogue is APPEND-ONLY: never remove or repurpose a
-- code (L-1). Drop-then-add is metadata-only; the recreated constraint validates
-- instantly because every existing row already holds a value from this (strictly
-- larger) set, and the brief ACCESS EXCLUSIVE lock only blocks writes for the instant
-- of the catalog update (audit_event is INSERT-only, low contention).
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
    -- ---- moderation decision trail ----
    'MODERATION_ACTION_TAKEN','MODERATION_APPEAL_RESOLVED',
    -- ---- added by V96 (binding-civic-act SUCCESS events) ----
    'PETITION_SIGNED','RATING_SUBMITTED',
    -- ---- added by THIS migration (M14): back-office user-administration events ----
    'ROLE_GRANTED',        -- admin granted a role (actor=admin, subject=account, reason=RoleName)
    'ROLE_REVOKED',        -- admin revoked (end-dated) a role grant (actor=admin, subject=account, reason=RoleName)
    'USER_SUSPENDED',      -- admin suspended an account (actor=admin, subject=account, reason=optional code)
    'USER_REINSTATED'));   -- admin reinstated a suspended account (actor=admin, subject=account)

COMMENT ON COLUMN audit_event.event_type IS 'AuditEventType from the §11.2 catalogue; stable machine token SOC tooling branches on. The ck_audit_event_type guard is APPEND-ONLY — widened by V6 (verification/electoral/MFA), V96 (binding-act successes), and V103 (user-admin ROLE_GRANTED/ROLE_REVOKED/USER_SUSPENDED/USER_REINSTATED, M14). Never repurpose a code (L-1).';
