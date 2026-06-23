-- =============================================================================
-- V78__seed_constituencies.sql  —  Electoral constituencies (Majimbo).
--
-- Seeds the Union-Parliament constituencies for Kilimanjaro (9) and Dar es Salaam (8)
-- (Wikipedia: List of constituencies of Tanzania). A Constituency (Jimbo) is the seat an
-- MP (Mbunge) is elected to; it OVERLAYS the admin chain and is its own entity.
--
-- district_id is a HOMING/DISPLAY anchor only (F2): it points at the DISTRICT a jimbo is
-- principally associated with, but a constituency's actual member wards are driven SOLELY
-- by the effective-dated ward_constituency bridge (V79) — never derived from this anchor.
-- Per F2, district_id MUST point at a type=DISTRICT row (asserted by the subselect).
--
-- CODE format "JIMBO-<slug>"; the unique idempotent match key.
-- created_by = SYSTEM sentinel; version 0. Idempotent. Forward-only.
-- =============================================================================

INSERT INTO constituency (public_id, version, created_at, created_by, code, name, district_id)
SELECT seed_uuid('CONSTITUENCY', v.code), 0, now(), '00000000-0000-0000-0000-000000000000'::uuid,
       v.code, v.name,
       (SELECT id FROM location d WHERE d.code = v.district_code AND d.type = 'DISTRICT')
FROM (VALUES
    -- Kilimanjaro (TZ-09) constituencies, homed in their principal district.
    ('JIMBO-HAI',         'Hai',         'TZ-09-D01'),
    ('JIMBO-VUNJO',       'Vunjo',       'TZ-09-D03'),  -- Moshi Rural
    ('JIMBO-MOSHI-RURAL', 'Moshi Rural', 'TZ-09-D03'),
    ('JIMBO-MOSHI-URBAN', 'Moshi Urban', 'TZ-09-D02'),  -- Moshi Municipal
    ('JIMBO-MWANGA',      'Mwanga',      'TZ-09-D04'),
    ('JIMBO-ROMBO',       'Rombo',       'TZ-09-D05'),
    ('JIMBO-SAME-EAST',   'Same East',   'TZ-09-D06'),
    ('JIMBO-SAME-WEST',   'Same West',   'TZ-09-D06'),
    ('JIMBO-SIHA',        'Siha',        'TZ-09-D07'),
    -- Dar es Salaam (TZ-02) constituencies, homed in their principal district.
    ('JIMBO-ILALA',       'Ilala',       'TZ-02-D01'),
    ('JIMBO-SEGEREA',     'Segerea',     'TZ-02-D01'),
    ('JIMBO-UKONGA',      'Ukonga',      'TZ-02-D01'),
    ('JIMBO-KIGAMBONI',   'Kigamboni',   'TZ-02-D02'),
    ('JIMBO-KINONDONI',   'Kinondoni',   'TZ-02-D03'),
    ('JIMBO-KAWE',        'Kawe',        'TZ-02-D03'),
    ('JIMBO-TEMEKE',      'Temeke',      'TZ-02-D04'),
    ('JIMBO-UBUNGO',      'Ubungo',      'TZ-02-D05')
) AS v(code, name, district_code)
ON CONFLICT (public_id) DO NOTHING;
