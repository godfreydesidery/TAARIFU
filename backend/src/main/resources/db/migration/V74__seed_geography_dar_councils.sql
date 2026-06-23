-- =============================================================================
-- V74__seed_geography_dar_councils.sql  —  Councils (Halmashauri), DAR ES SALAAM.
--
-- Seeds the NET-NEW COUNCIL/LGA tier (D6) for Dar es Salaam's 5 districts. Each Dar
-- district maps 1:1 to a Municipal Council LGA (Ilala became a City Council in the
-- 2016 reorganisation; the other four are Municipal Councils). Per the F1 matrix a
-- COUNCIL's only legal parent is a DISTRICT; parent_id resolves from the V72 district.
--
-- CODE format "<district_code>-C01"; type COUNCIL.
-- created_by = SYSTEM sentinel; status ACTIVE; version 0. Idempotent. Forward-only.
-- =============================================================================

INSERT INTO location (public_id, version, created_at, created_by, type, parent_id, code, name, status)
SELECT seed_uuid('COUNCIL', v.code), 0, now(), '00000000-0000-0000-0000-000000000000'::uuid,
       'COUNCIL',
       (SELECT id FROM location d WHERE d.code = v.district_code AND d.type = 'DISTRICT'),
       v.code, v.name, 'ACTIVE'
FROM (VALUES
    ('TZ-02-D01','TZ-02-D01-C01','Ilala City Council'),
    ('TZ-02-D02','TZ-02-D02-C01','Kigamboni Municipal Council'),
    ('TZ-02-D03','TZ-02-D03-C01','Kinondoni Municipal Council'),
    ('TZ-02-D04','TZ-02-D04-C01','Temeke Municipal Council'),
    ('TZ-02-D05','TZ-02-D05-C01','Ubungo Municipal Council')
) AS v(district_code, code, name)
ON CONFLICT (public_id) DO NOTHING;
