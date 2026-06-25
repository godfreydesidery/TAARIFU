-- =============================================================================
-- V154__moderation_item_auto_assist.sql  —  Auto-assist columns on the moderation
-- queue (M12, US-12.3, UC-H05; ADR-0018).
--
-- Responsibility: add the content-safety auto-assist marker fields to `moderation_item`
-- behind the JPA entity `com.taarifu.moderation.domain.model.ModerationItem`
-- (PRD §12 US-12.3, EI-18, D-Q8). MUST match the entity so ddl-auto=validate passes
-- (ARCHITECTURE.md §4.1, ADR-0005).
--
-- WHAT this enables: the Swahili-aware auto-assist scorer can HOLD-and-PRIORITISE risky
-- content for human review (assist only, never auto-remove — D-Q8, R21). These columns
-- record THAT the screen raised the item and WITH WHICH signal/confidence — they never
-- record an action/takedown. They drive:
--   * the auto-vs-manual transparency split (§25 transparency report);
--   * `moderation_action_taken.was_auto_assisted` when a moderator later actions the item.
--
-- INTEGRITY note (D-Q8, R21): auto-assist NEVER closes/actions an item. A held item is
-- still decided by a HUMAN moderator through the D16-guarded action path. No column here
-- is an action; the append-only moderation_action log (V41) remains the only decision trail.
--
-- 🔒 NO PII: no content text, no author, no flagger is added — only a label + confidence.
--
-- Type mapping contract (entity -> column): boolean->boolean not null default false;
-- ContentSignal (enum, EnumType.STRING)->varchar(16) with CHECK; Double->double precision.
--
-- Forward-only; never edit once applied — add a new migration (CLAUDE.md §12).
-- Migration number: V154 is in the moderation/late block, the next free number after the
-- applied tail (…V122). V155/V156 reserved for this increment, unused.
-- =============================================================================

ALTER TABLE moderation_item
    ADD COLUMN auto_assisted   BOOLEAN          NOT NULL DEFAULT FALSE,  -- raised/held by auto-assist (US-12.3)
    ADD COLUMN auto_signal     VARCHAR(16),                              -- ContentSignal: top signal; NULL = manual
    ADD COLUMN auto_confidence DOUBLE PRECISION;                         -- scorer confidence [0,1]; NULL = manual

-- ContentSignal vocabulary must mirror the published Appendix E auto_moderation_triaged.signal set.
ALTER TABLE moderation_item
    ADD CONSTRAINT ck_mod_item_auto_signal
        CHECK (auto_signal IS NULL OR auto_signal IN ('PROFANITY','PII','SPAM','IMAGE'));

-- Partial index: cheaply enumerate auto-assisted items (transparency split, auto-queue lane).
CREATE INDEX ix_mod_item_auto_assisted ON moderation_item (auto_assisted) WHERE auto_assisted = TRUE;

COMMENT ON COLUMN moderation_item.auto_assisted   IS 'TRUE when the auto-assist scorer raised/held this item for review (US-12.3, UC-H05). Assist only — NEVER an action/takedown (D-Q8, R21).';
COMMENT ON COLUMN moderation_item.auto_signal     IS 'Top content-safety signal raised by auto-assist (PROFANITY/PII/SPAM/IMAGE); NULL when manual. Label only — no content/PII.';
COMMENT ON COLUMN moderation_item.auto_confidence IS 'Auto-assist scorer confidence [0,1] in auto_signal; NULL when manual. Advisory to the human moderator.';
