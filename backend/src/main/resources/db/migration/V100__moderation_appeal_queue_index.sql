-- =============================================================================
-- V100__moderation_appeal_queue_index.sql  —  Appeals-queue read index (M12, §25.8).
--
-- Responsibility: support the moderator appeals queue read
-- `GET /api/v1/moderation/appeals?status=&page=&size=` (UC-H03), which filters by
-- `status` and orders by `created_at DESC` (newest first — AppealController default
-- sort `createdAt,desc`). The V42 single-column `ix_mod_appeal_status` covers the
-- status predicate but not the ordering, so a status-filtered page still sorts in
-- memory. This composite (status, created_at DESC) lets the planner satisfy BOTH the
-- WHERE and the ORDER BY from one index — an index-ordered scan, no sort step — which
-- matters once the appeal volume grows (national scale, §15).
--
-- WHY a migration-only index (not added to the Appeal @Index set): Hibernate
-- `ddl-auto=validate` validates tables/columns/types, NOT indexes — so a read-path
-- index may live purely in Flyway without an entity change, keeping the index a
-- database concern (ARCHITECTURE.md §4.1). The existing V42 indexes remain; this is
-- purely additive.
--
-- WHY IF NOT EXISTS: idempotent/defensive re-run safety on a fresh forward-only
-- migration; it never conflicts with the V42 single-column status index (different
-- name, different column set).
--
-- Forward-only; never edit once applied — add a new migration (CLAUDE.md §12).
-- =============================================================================

CREATE INDEX IF NOT EXISTS ix_mod_appeal_status_created
    ON moderation_appeal (status, created_at DESC);

COMMENT ON INDEX ix_mod_appeal_status_created IS 'Backs GET /moderation/appeals (UC-H03): status filter + created_at DESC ordering served from one index-ordered scan (§25.8).';
