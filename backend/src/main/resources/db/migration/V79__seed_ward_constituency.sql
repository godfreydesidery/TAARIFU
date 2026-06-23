-- =============================================================================
-- V79__seed_ward_constituency.sql  —  Effective-dated Ward(Kata)->Constituency(Jimbo).
--
-- The single most important temporal-correctness table (EI-14). Each mapping is
-- effective-dated; re-delimitation will later CLOSE a row (set effective_to) and INSERT
-- a new one, never rewrite history. The DB owns two invariants this seed must respect:
--   * ux_ward_constituency_current — at most ONE current (effective_to IS NULL) per ward;
--   * ex_ward_constituency_no_overlap — no overlapping date ranges per ward.
-- So a ward appears AT MOST ONCE here with effective_to NULL.
--
-- effective_from = 2020-10-28 (2020 general-election delimitation, carried into the term
-- current as of this seed); effective_to = NULL (currently in effect). public_id is
-- deterministic from "<ward_code>@<constituency_code>".
--
-- COVERAGE / what is VERIFIED vs UNVERIFIED:
--   * ROMBO (VERIFIED): Rombo District is a SINGLE constituency (Jimbo la Rombo). All 24
--     Rombo wards map to JIMBO-ROMBO — a complete, authoritative round trip for
--     find-my-rep and route-a-report in the Kilimanjaro pilot (R4/R5 gate).
--   * DAR ES SALAAM (PARTIAL): Kinondoni MC spans 2 constituencies (Kinondoni, Kawe) and
--     Ilala CC spans 3 (Ilala, Segerea, Ukonga). An authoritative ward->constituency
--     split for these municipalities was NOT obtainable from accessible NEC/NBS sources,
--     so ONLY the name-unambiguous self-anchor wards are mapped here (e.g. Kawe ward ->
--     Kawe jimbo). The remaining Dar wards are LEFT UNMAPPED on purpose — a fabricated
--     split would corrupt electoral attribution. They are the known enrichment gap
--     (owner: geography seed gate, R4/R5) and must be filled from the official NEC
--     constituency-ward delimitation before Dar go-live.
--
-- created_by = SYSTEM sentinel; version 0. Idempotent. Forward-only.
-- =============================================================================

-- ---- Rombo: all 24 wards -> Jimbo la Rombo (single-constituency district). ----
INSERT INTO ward_constituency
    (public_id, version, created_at, created_by, ward_id, constituency_id, effective_from, effective_to)
SELECT seed_uuid('WARDCONST', w.code || '@JIMBO-ROMBO'), 0, now(),
       '00000000-0000-0000-0000-000000000000'::uuid,
       w.id, c.id, DATE '2020-10-28', NULL
FROM location w
CROSS JOIN constituency c
WHERE w.type = 'WARD'
  AND w.code LIKE 'TZ-09-D05-C01-%'        -- Rombo District Council wards
  AND c.code = 'JIMBO-ROMBO'
ON CONFLICT (public_id) DO NOTHING;

-- ---- Dar es Salaam: name-unambiguous self-anchor wards only (VERIFIED subset). ----
INSERT INTO ward_constituency
    (public_id, version, created_at, created_by, ward_id, constituency_id, effective_from, effective_to)
SELECT seed_uuid('WARDCONST', v.ward_code || '@' || v.constituency_code), 0, now(),
       '00000000-0000-0000-0000-000000000000'::uuid,
       (SELECT id FROM location w  WHERE w.code = v.ward_code         AND w.type = 'WARD'),
       (SELECT id FROM constituency c WHERE c.code = v.constituency_code),
       DATE '2020-10-28', NULL
FROM (VALUES
    -- Kinondoni MC: the two same-named anchor wards.
    ('TZ-02-D03-C01-W06', 'JIMBO-KINONDONI'),  -- Kinondoni ward
    ('TZ-02-D03-C01-W03', 'JIMBO-KAWE'),        -- Kawe ward
    -- Ilala CC: the three same-named anchor wards.
    ('TZ-02-D01-C01-W07', 'JIMBO-ILALA'),       -- Ilala ward
    ('TZ-02-D01-C01-W30', 'JIMBO-SEGEREA'),     -- Segerea ward
    ('TZ-02-D01-C01-W32', 'JIMBO-UKONGA')       -- Ukonga ward
) AS v(ward_code, constituency_code)
ON CONFLICT (public_id) DO NOTHING;
