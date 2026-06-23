-- =============================================================================
-- V6__identity_mfa.sql  —  Staff TOTP (MFA) secret columns on app_user +
--                          audit_event type-catalogue widening (identity / common).
--
-- Responsibility: two changes the profile/identity-verification increment needs so
-- the database matches the entities and the running code:
--   (1) add the two nullable, field-encrypted TOTP-secret columns the staff-MFA
--       enforcement (review item N-4) introduces on the JPA entity
--       `com.taarifu.identity.domain.model.User`; and
--   (2) widen the audit_event.event_type CHECK constraint to admit the six new
--       AuditEventType values this increment EMITS (verification approve/reject,
--       electoral change, staff MFA login/enrol/challenge-fail) — without this, the
--       INSERT of those audit rows fails the V5 CHECK at runtime.
-- Every OTHER entity this increment touches (Profile, ProfileLocation,
-- VerificationRequest, OtpChallenge) maps solely to columns/indexes/constraints
-- already created in V3/V4 (VERIFICATION-DESIGN §10). MUST keep ddl-auto=validate
-- passing (ADR-0005, ARCHITECTURE.md §4.1).
--
-- WHY (2) is not caught by ddl-auto=validate (and why it must still ship here):
--   Hibernate's `validate` checks tables/columns/types, NOT the membership of a
--   CHECK constraint. So boot/validate would PASS while any attempt to write an
--   audit row of a new type (e.g. AUTH_VERIFICATION_APPROVED) would be rejected by
--   ck_audit_event_type at INSERT time — silently breaking the approve/reject,
--   electoral-change and staff-MFA audit trail. The enum (AuditEventType) already
--   declares all six; this migration makes the DB guard agree (codes are
--   append-only — the catalogue only ever grows, never repurposes; L-1, §8).
--
-- WHY this lands now (N-4, blocking): this increment exposes the first scoped staff
-- endpoint (the Moderator verification queue), so no account holding
-- MODERATOR/ADMIN/ROOT may authenticate or act without a TOTP second factor. The
-- secret backing that factor must be persisted; `mfa_enabled` already exists (V3),
-- only the secret storage is new (docs/reviews/auth-increment-review.md N-4).
--
-- WHY two columns, both ENCRYPTED, both NULLABLE (security/PDPA, S-4 / PRD §18):
--   * mfa_pending_secret — the provisional Base32 secret written at `setup`, BEFORE
--     activation. Kept separate so an un-activated secret can NEVER satisfy a login;
--     `activate` promotes it to the active secret and clears this slot.
--   * mfa_totp_secret    — the ACTIVE secret used to verify the staff second factor,
--     set only on `activate`.
--   Both are field-encrypted at rest via EncryptedStringConverter (the DB stores
--   ciphertext only — randomised GCM, hence no uniqueness/index on them) and are
--   NEVER logged or serialised (S-4). NULLABLE because the vast majority of accounts
--   (citizens) never enrol MFA; non-staff rows simply leave both NULL.
--
-- Type mapping contract (entity -> column) — identical rules to V3/V4:
--   @Convert(EncryptedStringConverter) String -> varchar(<declared length=512>),
--   nullable (no NOT NULL, no DEFAULT).
--
-- LOCKING / SAFETY: each ADD COLUMN adds a NULLABLE column with NO default, which in
-- PostgreSQL is a catalog-only change (no table rewrite, no per-row work). It takes a
-- brief ACCESS EXCLUSIVE lock on app_user for metadata update only — effectively
-- instant; safe on a busy table. No backfill is needed (both default to NULL).
--
-- ROLLBACK (forward-only Flyway; manual contract step if ever required). NOTE the
-- CHECK must be reverted FIRST only if any new-type rows were already written — and
-- since they would then violate the narrower V5 set, a true rollback also requires
-- deleting/relabelling those rows (audit is append-only, so in practice DO NOT roll
-- the CHECK back; leave the wider, append-only catalogue in place):
--   ALTER TABLE app_user DROP COLUMN IF EXISTS mfa_pending_secret;
--   ALTER TABLE app_user DROP COLUMN IF EXISTS mfa_totp_secret;
--   -- (CHECK widening is forward-safe and should NOT be reverted; see note above.)
--   Dropping the columns is metadata-only. Any enrolled secrets are discarded — staff
--   would re-enrol; no other data depends on these columns.
--
-- Forward-only; never edit once applied — add a new migration (CLAUDE.md §12).
-- =============================================================================


-- -----------------------------------------------------------------------------
-- app_user — add the staff TOTP secret storage (N-4). Nullable + no default =
-- metadata-only ADD COLUMN (no rewrite, no long lock).
-- -----------------------------------------------------------------------------
ALTER TABLE app_user
    ADD COLUMN mfa_totp_secret   VARCHAR(512),   -- ENCRYPTED active Base32 TOTP secret; set on activate. Never plaintext/logged (S-4)
    ADD COLUMN mfa_pending_secret VARCHAR(512);  -- ENCRYPTED provisional secret set at setup; promoted on activate. Never plaintext/logged (S-4)

COMMENT ON COLUMN app_user.mfa_totp_secret    IS 'ENCRYPTED (EncryptedStringConverter) active Base32 TOTP secret verifying the staff second factor (N-4); set on `activate`. NULL until MFA is activated. Never log/expose (S-4, PRD §18).';
COMMENT ON COLUMN app_user.mfa_pending_secret IS 'ENCRYPTED provisional Base32 TOTP secret written at `setup`, BEFORE activation; promoted to mfa_totp_secret on `activate`. Separation means an un-activated secret can never satisfy login (N-4, §2.3). Never log/expose (S-4).';


-- -----------------------------------------------------------------------------
-- audit_event — widen ck_audit_event_type to admit this increment's six new
-- AuditEventType values (VERIFICATION-DESIGN §8). The set below is the FULL current
-- catalogue = the V5 set + the six additions; the catalogue is append-only (never
-- remove/repurpose a code — L-1). Drop-then-add is a metadata-only operation; the
-- ACCESS EXCLUSIVE lock it briefly takes only blocks concurrent writes for the
-- instant of the catalog update (audit_event is INSERT-only, low contention).
--
-- WHY drop-and-recreate rather than a second CHECK: keeping ONE constraint named
-- ck_audit_event_type as the single source of truth for the allowed set is clearer
-- than accumulating ck_audit_event_type_2/_3 across increments (KISS); the recreated
-- constraint validates instantly because every existing row already holds a value
-- from the (strictly larger) new set.
-- -----------------------------------------------------------------------------
ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_type;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_type CHECK (event_type IN (
    -- ---- original V5 catalogue (unchanged) ----
    'AUTH_OTP_REQUESTED','AUTH_OTP_VERIFIED','AUTH_OTP_FAILED','AUTH_SIGNUP_COMPLETED',
    'AUTH_LOGIN_SUCCEEDED','AUTH_LOGIN_FAILED','AUTH_LOGIN_LOCKED','AUTH_TOKEN_REFRESHED',
    'AUTH_REFRESH_REJECTED','AUTH_REFRESH_EXPIRED','AUTH_REFRESH_REUSE_DETECTED','AUTH_FAMILY_REVOKED',
    'AUTH_LOGOUT','AUTH_LOGOUT_ALL','AUTH_TIER_CHANGED','AUTH_VERIFICATION_REQUESTED',
    'AUTHZ_TIER_DENIED','AUTHZ_SCOPE_DENIED','AUTHZ_SELF_ACTION_BLOCKED','IDENTITY_ERASED',
    -- ---- added by this increment (VERIFICATION-DESIGN §8; N-4 / D13 / D16) ----
    'AUTH_VERIFICATION_APPROVED',   -- Moderator approved an ID/rep/org request (D16 multi-hat audit)
    'AUTH_VERIFICATION_REJECTED',   -- Moderator rejected a request (reason_code = rejection reason)
    'ELECTORAL_CHANGED',            -- single isElectoral set/changed (MANUAL/VOTER_ID_AUTHORITATIVE/REDELIMITATION; denied=COOLDOWN/AUTHORITATIVE_LOCKED) (D13/§25.4)
    'AUTH_LOGIN_MFA',               -- staff completed the TOTP second factor at login (reason_code=TOTP, N-4)
    'AUTH_MFA_ENROLLED',            -- TOTP MFA activated for an account (staff second factor enrolled)
    'AUTH_MFA_CHALLENGE_FAILED'));  -- wrong TOTP at login/activate (anti-automation signal, S-2/N-4)

COMMENT ON COLUMN audit_event.event_type IS 'AuditEventType from the §11.2 catalogue; stable machine token SOC tooling branches on. The ck_audit_event_type guard is APPEND-ONLY — widened by V6 for the verification/electoral/MFA events (VERIFICATION-DESIGN §8). Never repurpose a code (L-1).';
