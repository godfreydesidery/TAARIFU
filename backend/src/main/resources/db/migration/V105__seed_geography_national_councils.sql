-- =============================================================================
-- V105__seed_geography_national_councils.sql  —  Councils (Halmashauri), NATIONAL.
--
-- Extends the NET-NEW COUNCIL/LGA tier (D6) — the level legacy data lacks and at which
-- services/officials and report routing actually sit — from the two pilot regions
-- (Kilimanjaro V73, Dar V74) to ALL remaining mainland districts, so a citizen anywhere
-- on the mainland can drill REGION -> DISTRICT -> COUNCIL -> WARD.
--
-- MODEL (matches V73/V74): one COUNCIL per DISTRICT (the 1:1 Halmashauri model the pilot
-- seed established). Per the F1 parent-type matrix a COUNCIL's only legal parent is a
-- DISTRICT, so this derives the council SET-BASED from every DISTRICT that does not yet
-- have its "<district_code>-C01" council. Deriving from the tree (not a hand-typed list of
-- 170+ rows) keeps the single source of truth in `location` and is inherently idempotent.
--
-- CODE  = "<district_code>-C01"  (the same convention as V73/V74; unique EI-14 match key).
-- NAME  = "<district_name> Council" — a STRUCTURAL placeholder. The pilot regions carry the
--         exact legal LGA name ("Moshi Municipal Council", "Ilala City Council"); for the
--         national fill we do NOT have an authoritative City/Municipal/Town/District-Council
--         classification per LGA from an accessible source, so we use the neutral, factual
--         "<District> Council" rather than INVENT a class. This is a KNOWN ENRICHMENT POINT
--         (R4/R5 geography gate): the precise LGA legal name/class must be set from the
--         official PO-RALG/NBS LGA register before that region's go-live.  [UNVERIFIED name]
--
-- public_id is deterministic via seed_uuid('COUNCIL', code) so re-runs match the same row.
-- created_by = SYSTEM sentinel; status ACTIVE; version 0. Idempotent. Forward-only.
-- =============================================================================

INSERT INTO location (public_id, version, created_at, created_by, type, parent_id, code, name, status)
SELECT seed_uuid('COUNCIL', d.code || '-C01'), 0, now(),
       '00000000-0000-0000-0000-000000000000'::uuid,
       'COUNCIL',
       d.id,                                   -- parent is the district itself (legal per F1)
       d.code || '-C01',
       d.name || ' Council',                   -- structural placeholder name (see header) [UNVERIFIED]
       'ACTIVE'
FROM location d
WHERE d.type = 'DISTRICT'
  AND d.deleted = FALSE
  -- Only districts without an existing council (skips the 12 pilot councils from V73/V74).
  AND NOT EXISTS (
      SELECT 1 FROM location c
      WHERE c.type = 'COUNCIL' AND c.code = d.code || '-C01'
  )
ON CONFLICT (public_id) DO NOTHING;
