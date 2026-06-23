-- =============================================================================
-- V77__seed_geography_closure.sql  —  Build location_closure for all seeded nodes.
--
-- The closure table holds one row per (ancestor, descendant, depth) INCLUDING the
-- self-pair (depth 0), giving O(1) indexed subtree/ancestry reads (ARCHITECTURE.md §4.3)
-- that "all wards in this region" and "full chain of this ward" rely on. This migration
-- derives EVERY pair transitively from location.parent_id with a recursive CTE, so it
-- stays correct no matter how deep the seeded chain goes (Region→District→Council→Ward).
--
-- DESIGN NOTES (the "why"):
--   * Derived from parent_id, not hand-listed: the single source of truth is the tree in
--     `location`; the closure is a pure projection of it. Re-running recomputes the same
--     set, and ON CONFLICT (ancestor_id, descendant_id) DO NOTHING makes it idempotent.
--   * public_id is deterministic from the two endpoint CODES (seed_uuid over
--     "<ancestor_code>>><descendant_code>") so a re-run matches the same closure row.
--   * Scoped to live (deleted = FALSE) location rows.
--
-- created_by = SYSTEM sentinel; version 0. Forward-only; never edit once applied.
-- =============================================================================

WITH RECURSIVE chain AS (
    -- Depth 0: every node is its own ancestor.
    SELECT l.id AS ancestor_id, l.id AS descendant_id, 0 AS depth,
           l.code AS ancestor_code, l.code AS descendant_code
    FROM location l
    WHERE l.deleted = FALSE
    UNION ALL
    -- Walk down: extend each (ancestor -> descendant) by the descendant's children.
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
