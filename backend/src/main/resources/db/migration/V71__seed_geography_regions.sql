-- =============================================================================
-- V71__seed_geography_regions.sql  —  TZ MAINLAND regions (Mkoa), level REGION.
--
-- Seeds all 26 MAINLAND regions of the United Republic of Tanzania (the 5 Zanzibar
-- regions are Phase 2, D17 — deliberately omitted). REGION is the hierarchy root:
-- parent_id IS NULL (legal per the F1 parent-type matrix).
--
-- CODE = official region code, ISO 3166-2:TZ numeric form "TZ-NN" which mirrors the
-- NBS 2022 PHC region code (F3: adopt the official numeric code as Location.code, the
-- idempotent EI-14 match key). Songwe (split from Mbeya, 2016) carries TZ-31.
--
-- public_id is deterministic via seed_uuid('REGION', code) so re-runs are idempotent
-- and downstream migrations resolve a region by code without a lookup.
-- created_by = all-zero SYSTEM sentinel; status ACTIVE; version 0.
--
-- COVERAGE NOTE (UNVERIFIED precision): the PRD task brief says "~31 mainland Regions"
-- but Tanzania mainland has 26 regions; 31 is the ALL-Tanzania total (26 mainland + 5
-- Zanzibar). This migration seeds the 26 mainland regions — the factual mainland-MVP set.
--
-- Idempotent (ON CONFLICT (public_id) DO NOTHING). Forward-only; never edit once applied.
-- =============================================================================

INSERT INTO location (public_id, version, created_at, created_by, type, parent_id, code, name, status)
SELECT seed_uuid('REGION', v.code), 0, now(), '00000000-0000-0000-0000-000000000000'::uuid,
       'REGION', NULL, v.code, v.name, 'ACTIVE'
FROM (VALUES
    ('TZ-01', 'Arusha'),
    ('TZ-02', 'Dar es Salaam'),
    ('TZ-03', 'Dodoma'),
    ('TZ-27', 'Geita'),
    ('TZ-04', 'Iringa'),
    ('TZ-05', 'Kagera'),
    ('TZ-28', 'Katavi'),
    ('TZ-08', 'Kigoma'),
    ('TZ-09', 'Kilimanjaro'),
    ('TZ-12', 'Lindi'),
    ('TZ-26', 'Manyara'),
    ('TZ-13', 'Mara'),
    ('TZ-14', 'Mbeya'),
    ('TZ-16', 'Morogoro'),
    ('TZ-17', 'Mtwara'),
    ('TZ-18', 'Mwanza'),
    ('TZ-29', 'Njombe'),
    ('TZ-19', 'Pwani'),
    ('TZ-20', 'Rukwa'),
    ('TZ-21', 'Ruvuma'),
    ('TZ-22', 'Shinyanga'),
    ('TZ-30', 'Simiyu'),
    ('TZ-23', 'Singida'),
    ('TZ-31', 'Songwe'),
    ('TZ-24', 'Tabora'),
    ('TZ-25', 'Tanga')
) AS v(code, name)
ON CONFLICT (public_id) DO NOTHING;
