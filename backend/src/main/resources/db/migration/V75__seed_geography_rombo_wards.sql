-- =============================================================================
-- V75__seed_geography_rombo_wards.sql  —  Wards (Kata) of Rombo, KILIMANJARO.
--
-- Seeds the 24 wards of Rombo District Council (Wikipedia: "Rombo is divided
-- administratively into 24 wards"). WARD is the MINIMUM citizen pin (PRD §9.0): from a
-- ward the system derives the full admin chain AND — via V79's ward_constituency
-- bridge — the constituency, so "find-my-rep" and "route-a-report" resolve.
--
-- Per the F1 parent-type matrix a WARD's legal parent is a COUNCIL or DIVISION (Tarafa
-- optional, not modelled here); parent_id resolves to the Rombo District COUNCIL
-- (TZ-09-D05-C01) seeded in V73.
--
-- CODE format "<council_code>-W<nn>"; type WARD.
-- created_by = SYSTEM sentinel; status ACTIVE; version 0. Idempotent. Forward-only.
-- =============================================================================

INSERT INTO location (public_id, version, created_at, created_by, type, parent_id, code, name, status)
SELECT seed_uuid('WARD', v.code), 0, now(), '00000000-0000-0000-0000-000000000000'::uuid,
       'WARD',
       (SELECT id FROM location c WHERE c.code = 'TZ-09-D05-C01' AND c.type = 'COUNCIL'),
       v.code, v.name, 'ACTIVE'
FROM (VALUES
    ('TZ-09-D05-C01-W01','Aleni'),
    ('TZ-09-D05-C01-W02','Holili'),
    ('TZ-09-D05-C01-W03','Katangara Mrere'),
    ('TZ-09-D05-C01-W04','Kelamfua Mokala'),
    ('TZ-09-D05-C01-W05','Keni Mengeni'),
    ('TZ-09-D05-C01-W06','Kirongo Samanga'),
    ('TZ-09-D05-C01-W07','Kirwa Keni'),
    ('TZ-09-D05-C01-W08','Kisale Msaranga'),
    ('TZ-09-D05-C01-W09','Kitirima Kingachi'),
    ('TZ-09-D05-C01-W10','Mahida'),
    ('TZ-09-D05-C01-W11','Makiidi'),
    ('TZ-09-D05-C01-W12','Mamsera'),
    ('TZ-09-D05-C01-W13','Manda'),
    ('TZ-09-D05-C01-W14','Marangu Kitowo'),
    ('TZ-09-D05-C01-W15','Mengwe'),
    ('TZ-09-D05-C01-W16','Motamburu Kitendeni'),
    ('TZ-09-D05-C01-W17','Mrao Keryo'),
    ('TZ-09-D05-C01-W18','Nanjara Reha'),
    ('TZ-09-D05-C01-W19','Ngoyoni'),
    ('TZ-09-D05-C01-W20','Olele'),
    ('TZ-09-D05-C01-W21','Shimbi'),
    ('TZ-09-D05-C01-W22','Tarakea Motamburu'),
    ('TZ-09-D05-C01-W23','Ubetu Kahe'),
    ('TZ-09-D05-C01-W24','Ushiri Ikuini')
) AS v(code, name)
ON CONFLICT (public_id) DO NOTHING;
