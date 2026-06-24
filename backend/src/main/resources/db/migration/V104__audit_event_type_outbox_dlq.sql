-- =============================================================================
-- V104 — widen ck_audit_event_type to the COMPLETE AuditEventType set.
--
-- WHY: wave-3 added `OUTBOX_DLQ_REPLAYED` (admin DLQ replay) to the
-- com.taarifu.common.audit.AuditEventType enum but no migration widened the
-- DB CHECK, so emitting that audit would fail the constraint at runtime (500 on
-- an admin replay). To stop this drift recurring, this drops and recreates the
-- CHECK as the single source of truth listing EVERY current enum value (the V96
-- pattern). DDL only — the append-only DML guard on audit_event is unaffected.
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
    'OUTBOX_DLQ_REPLAYED'
));
