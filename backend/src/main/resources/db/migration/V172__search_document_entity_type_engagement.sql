-- =============================================================================
-- V172__search_document_entity_type_engagement.sql
--   Widen ck_search_document_entity_type to admit the engagement content types.
--
-- Responsibility: extend the @Enumerated(STRING) domain CHECK on
-- search_document.entity_type so the cross-entity discovery index can hold the
-- newly-indexable ENGAGEMENT content — PETITION, POLL (surveys AND polls), and
-- QUESTION — alongside the original five (representative / organisation /
-- announcement / issue-category / public report). Mirrors the values appended to
-- com.taarifu.search.domain.model.enums.SearchEntityType in this same change.
--
-- WHY this migration is needed: V146 created the CHECK listing only the original
-- five values. The engagement search wiring (PetitionService / SurveyService /
-- QuestionService now push public projections) inserts rows with the NEW values;
-- without widening the CHECK those inserts would be rejected by Postgres. An
-- isolated engagement agent could not edit search's enum or this constraint
-- (Phase-2 wave-3 verify gap), so the search owner closes it here.
--
-- WHY drop + re-add (not ALTER … ADD): Postgres has no "extend an existing CHECK"
-- DDL; a CHECK is replaced wholesale. We drop the V146 constraint and re-add it
-- with the FULL list (original five + the three new), matching the V146 style.
-- The widened list is a strict SUPERSET, so every existing row still satisfies it
-- and the re-validating ADD never fails on live data (forward-compatible).
--
-- ddl-auto=validate: this changes only a CHECK constraint (which Hibernate
-- validate does not inspect — it checks tables/columns/types, not CHECK bodies),
-- so the schema still matches the entity. The entity_type column type/length is
-- unchanged (still VARCHAR(32)); the enum stays @Enumerated(STRING). validate
-- passes (CLAUDE.md §5, ARCHITECTURE §4.1).
--
-- Forward-only; never edit once applied (CLAUDE.md §12).
-- =============================================================================

ALTER TABLE search_document
    DROP CONSTRAINT ck_search_document_entity_type;

ALTER TABLE search_document
    ADD CONSTRAINT ck_search_document_entity_type CHECK (entity_type IN (
        'REPRESENTATIVE',
        'ORGANISATION',
        'ANNOUNCEMENT',
        'ISSUE_CATEGORY',
        'PUBLIC_REPORT',
        -- Engagement content (V172): public petitions, surveys/polls (POLL), and Q&A questions.
        'PETITION',
        'POLL',
        'QUESTION'));

COMMENT ON COLUMN search_document.entity_type IS
    'SearchEntityType: REPRESENTATIVE | ORGANISATION | ANNOUNCEMENT | ISSUE_CATEGORY | PUBLIC_REPORT | PETITION | POLL | QUESTION.';
