-- =============================================================================
-- V171__analytics_event_type_auto_moderation_triaged.sql
--   Admit the AUTO_MODERATION_TRIAGED analytics event type so moderation's
--   auto-assist triage fact is actually RECORDED (not dropped as a no-op).
--   (analytics module, M15; ADR-0018; PRD Appendix E auto_moderation_triaged; US-12.3.)
--
-- WHY (the bug this fixes): moderation.AutoAssistService emits a CivicActivityRecorded
-- outbox fact whose analyticsEventType string is exactly "AUTO_MODERATION_TRIAGED"
-- (moderation.api.event.ModerationEventTypes.AUTO_MODERATION_TRIAGED). Until the
-- analytics catalogue knew that value, AnalyticsEventHandler.parseEventType returned
-- null and dropped the fact as a forward-compatible no-op (Appendix E.0 additive) —
-- so the auto-vs-manual moderation split KPI (US-12.3) never recorded. The companion
-- enum change (com.taarifu.analytics.domain.model.enums.AnalyticsEventType) adds the
-- value; this migration is the DB side of the same additive catalogue evolution.
--
-- WHY there is NO new CHECK to ADD on analytics_event.event_type (and we do NOT add
-- one): unlike audit_event (whose ck_audit_event_type is widened by V96/V103/V104/
-- V142/V163), V91 deliberately left analytics_event.event_type as a bare VARCHAR(48)
-- with NO domain CHECK. That is load-bearing: the analytics handler is intentionally
-- forward-compatible — an unknown catalogue value is dropped as a no-op rather than
-- failing the insert (V91 design note; AnalyticsEventHandler Javadoc; Appendix E.0).
-- Introducing a CHECK now would REGRESS that contract (a newer producer's value would
-- start failing the insert instead of being safely ignored). So the analytics
-- "pattern used when prior event types were added" (e.g. MODERATION_APPEAL_RESOLVED)
-- is: ADD THE ENUM VALUE, NO DB CHECK. This migration follows exactly that pattern.
--
-- WHAT this migration does:
--   1. Defensively DROP any analytics_event event_type CHECK that some environment may
--      have introduced out-of-band (DROP ... IF EXISTS is a no-op when absent), so the
--      forward-compatible "no CHECK on event_type" invariant is restored/guaranteed and
--      AUTO_MODERATION_TRIAGED (and every future additive value) is admitted.
--   2. Refresh the event_type column COMMENT to document the new catalogue value — the
--      analytics catalogue documentation pattern (mirrors how audit documents its set).
--
-- Metadata-only: drop-if-exists + comment are instant and lock nothing in practice
-- (analytics_event is INSERT-only). Forward-only; never edit once applied — add a new
-- migration (CLAUDE.md §12).
-- =============================================================================

-- 1. Guarantee the forward-compatible invariant: NO domain CHECK constrains event_type,
--    so the additive AUTO_MODERATION_TRIAGED value (and future ones) is admitted and the
--    handler's no-op-on-unknown contract holds. No-op when the constraint is absent
--    (the expected state on every standard deployment — V91 never created it).
ALTER TABLE analytics_event DROP CONSTRAINT IF EXISTS ck_analytics_event_type;

-- 2. Document the catalogue (the new value lives in AnalyticsEventType; the column stays a
--    bare VARCHAR by design — see the header). Keep this comment in sync with the enum.
COMMENT ON COLUMN analytics_event.event_type IS
    'AnalyticsEventType catalogue value (Appendix E noun_verb_pastTense), stored as a bare '
    'VARCHAR with NO domain CHECK by design: the analytics handler drops an unknown value as '
    'a forward-compatible no-op rather than failing the insert (V91 / Appendix E.0 additive). '
    'Append-only — never rename/repurpose a value. V171 added AUTO_MODERATION_TRIAGED (the '
    'auto-assist triage fact behind the auto-vs-manual moderation split KPI, US-12.3 / ADR-0018).';
