-- =============================================================================
-- V73__seed_geography_kilimanjaro_councils.sql  —  Councils (Halmashauri), KILIMANJARO.
--
-- Seeds the NET-NEW COUNCIL/LGA tier (D6) for Kilimanjaro — the level that legacy data
-- lacks and that services/officials and report routing actually sit at. Per the F1
-- parent-type matrix a COUNCIL's only legal parent is a DISTRICT; parent_id resolves
-- from the V72 district by code.
--
-- Kilimanjaro has 7 LGAs: 6 District Councils + 1 Municipal Council (Moshi MC). We model
-- one council per district (the 1:1 case that holds across Kilimanjaro). CODE format
-- "<district_code>-C01"; type COUNCIL.
--
-- created_by = SYSTEM sentinel; status ACTIVE; version 0. Idempotent. Forward-only.
-- =============================================================================

INSERT INTO location (public_id, version, created_at, created_by, type, parent_id, code, name, status)
SELECT seed_uuid('COUNCIL', v.code), 0, now(), '00000000-0000-0000-0000-000000000000'::uuid,
       'COUNCIL',
       (SELECT id FROM location d WHERE d.code = v.district_code AND d.type = 'DISTRICT'),
       v.code, v.name, 'ACTIVE'
FROM (VALUES
    ('TZ-09-D01','TZ-09-D01-C01','Hai District Council'),
    ('TZ-09-D02','TZ-09-D02-C01','Moshi Municipal Council'),
    ('TZ-09-D03','TZ-09-D03-C01','Moshi District Council'),
    ('TZ-09-D04','TZ-09-D04-C01','Mwanga District Council'),
    ('TZ-09-D05','TZ-09-D05-C01','Rombo District Council'),
    ('TZ-09-D06','TZ-09-D06-C01','Same District Council'),
    ('TZ-09-D07','TZ-09-D07-C01','Siha District Council')
) AS v(district_code, code, name)
ON CONFLICT (public_id) DO NOTHING;
