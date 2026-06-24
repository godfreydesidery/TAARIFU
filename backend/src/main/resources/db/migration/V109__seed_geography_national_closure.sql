-- =============================================================================
-- V109__seed_geography_national_closure.sql  —  Extend location_closure for national fill.
--
-- V77 built the closure for the pilot tree. V105 added a national COUNCIL per district and
-- V107 added national WARDs; those new nodes need their (ancestor, descendant, depth) rows
-- — including their self-pair (depth 0) — or "all wards in region X" / "full chain of this
-- ward" reads (ARCHITECTURE.md §4.3) would miss them and find-my-rep/route-a-report would
-- not resolve for the new regions.
--
-- DESIGN (identical projection to V77, the "why" per CLAUDE.md §8):
--   * The closure is a PURE projection of location.parent_id — never hand-listed. This
--     recursive CTE recomputes EVERY (ancestor->descendant) pair from the live tree, so it
--     is correct no matter how the national rows interleave with the pilot rows, and re-runs
--     reproduce the same set. ON CONFLICT (ancestor_id, descendant_id) DO NOTHING makes it
--     idempotent and means the pilot pairs already present from V77 are simply skipped —
--     only the genuinely new pairs (new councils, new wards, and the new region->...->ward
--     paths through them) are inserted.
--   * public_id is deterministic from the two endpoint CODES (seed_uuid over
--     "<ancestor_code>>><descendant_code>"), matching V77 exactly, so a given closure pair
--     has the SAME public_id whether V77 or this migration inserts it.
--   * Scoped to live (deleted = FALSE) location rows.
--
-- created_by = SYSTEM sentinel; version 0. Forward-only; never edit once applied.
-- =============================================================================

WITH RECURSIVE chain AS (
    -- Depth 0: every live node is its own ancestor.
    SELECT l.id AS ancestor_id, l.id AS descendant_id, 0 AS depth,
           l.code AS ancestor_code, l.code AS descendant_code
    FROM location l
    WHERE l.deleted = FALSE
    UNION ALL
    -- Walk down: extend each (ancestor -> descendant) by the descendant's live children.
    SELECT c.ancestor_id, child.id, c.depth + 1,
           c.ancestor_code, child.code
    FROM chain c
    JOIN location child ON child.parent_id = c.descendant_id AND child.deleted = FALSE
)
INSERT INTO location_closure (public_id, version, created_at, created_by, ancestor_id, descendant_id, depth)
SELECT seed_uuid('CLOSURE', ancestor_code || '>>' || descendant_code), 0, now(),
       '00000000-0000-0000-0000-000000000000'::uuid,
       ancestor_id, descendant_id, depth
FROM chain
ON CONFLICT (ancestor_id, descendant_id) DO NOTHING;
