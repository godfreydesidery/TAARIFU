-- =============================================================================
-- V170__communications_digest_notification_type.sql
--   Add the DIGEST NotificationType to the communications enum-guard CHECKs.
--
-- Responsibility: extend the two enum-domain CHECK constraints that mirror
-- Hibernate's generated CHECKs for com.taarifu.communications.domain.model.enums.
-- NotificationType so the newly-appended DIGEST value is accepted on both the
-- `notification` and `notification_preference` tables. The scheduled area-activity
-- digest (com.taarifu.communications.application.service.DigestService) dispatches a
-- NotificationType.DIGEST notification (PRD §13 "digest", EI-6, M5), and a citizen may
-- opt that type in/out per channel — so DIGEST must be a legal value in both columns.
--
-- WHY a forward-only ALTER (not an edit of V29/V30): migrations are forward-only and
-- never edited once applied (CLAUDE.md §12). V29 (`ck_notification_type`) and V30
-- (`ck_notification_preference_type`) are already applied; this migration DROPs and
-- re-adds each CHECK with DIGEST included. Idempotent by IF EXISTS so a re-run is safe.
--
-- DESIGN NOTES (the "why", per CLAUDE.md §8):
--   * NotificationType is APPEND-ONLY (clients/preferences depend on the names); DIGEST
--     is appended, never repurposing an existing value.
--   * DIGEST is NOT an always-on type — a citizen can silence it (it is convenience, not
--     security); the always-on guard (SYSTEM/MODERATION_OUTCOME) lives in the service,
--     not the schema, and is unchanged here.
--   * No data backfill needed: no existing row carries DIGEST; this only widens the
--     accepted domain so future inserts succeed.
--
-- Reserved Flyway block: V170–V173 (communications wave-2). Forward-only.
-- =============================================================================


-- notification.type: allow DIGEST.
ALTER TABLE notification DROP CONSTRAINT IF EXISTS ck_notification_type;
ALTER TABLE notification
    ADD CONSTRAINT ck_notification_type
    CHECK (type IN ('NEW_ANNOUNCEMENT','REPORT_STATUS','MODERATION_OUTCOME','SYSTEM','DIGEST'));

-- notification_preference.type: allow DIGEST (a citizen can opt the digest in/out per channel).
ALTER TABLE notification_preference DROP CONSTRAINT IF EXISTS ck_notification_preference_type;
ALTER TABLE notification_preference
    ADD CONSTRAINT ck_notification_preference_type
    CHECK (type IN ('NEW_ANNOUNCEMENT','REPORT_STATUS','MODERATION_OUTCOME','SYSTEM','DIGEST'));

COMMENT ON CONSTRAINT ck_notification_type ON notification
    IS 'Enum-domain guard for NotificationType (mirrors Hibernate CHECK); includes DIGEST (V170, PRD §13).';
COMMENT ON CONSTRAINT ck_notification_preference_type ON notification_preference
    IS 'Enum-domain guard for NotificationType (mirrors Hibernate CHECK); includes DIGEST — opt-out-able (V170, PRD §13).';
