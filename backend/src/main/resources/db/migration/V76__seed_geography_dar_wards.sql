-- =============================================================================
-- V76__seed_geography_dar_wards.sql  —  Wards (Kata), DAR ES SALAAM (representative set).
--
-- Seeds the wards of two Dar es Salaam councils as the representative set required to
-- round-trip find-my-rep and route-a-report in the capital:
--   * Kinondoni Municipal Council — 20 wards (Wikipedia: Kinondoni District).
--   * Ilala City Council          — 36 wards (Wikipedia: Ilala District).
-- (Temeke, Ubungo, Kigamboni wards await per-region enrichment, R4/R5 — the known
-- coverage boundary; their councils exist in V74 so wards can be added later cleanly.)
--
-- Per the F1 matrix WARD's parent is a COUNCIL; parent_id resolves to the V74 council.
-- CODE format "<council_code>-W<nn>"; type WARD.
-- created_by = SYSTEM sentinel; status ACTIVE; version 0. Idempotent. Forward-only.
-- =============================================================================

-- ---- Kinondoni Municipal Council (TZ-02-D03-C01) — 20 wards. ----
INSERT INTO location (public_id, version, created_at, created_by, type, parent_id, code, name, status)
SELECT seed_uuid('WARD', v.code), 0, now(), '00000000-0000-0000-0000-000000000000'::uuid,
       'WARD',
       (SELECT id FROM location c WHERE c.code = 'TZ-02-D03-C01' AND c.type = 'COUNCIL'),
       v.code, v.name, 'ACTIVE'
FROM (VALUES
    ('TZ-02-D03-C01-W01','Bunju'),
    ('TZ-02-D03-C01-W02','Hananasif'),
    ('TZ-02-D03-C01-W03','Kawe'),
    ('TZ-02-D03-C01-W04','Kigogo'),
    ('TZ-02-D03-C01-W05','Kijitonyama'),
    ('TZ-02-D03-C01-W06','Kinondoni'),
    ('TZ-02-D03-C01-W07','Kunduchi'),
    ('TZ-02-D03-C01-W08','Mabwepande'),
    ('TZ-02-D03-C01-W09','Magomeni'),
    ('TZ-02-D03-C01-W10','Makongo'),
    ('TZ-02-D03-C01-W11','Makumbusho'),
    ('TZ-02-D03-C01-W12','Mbezi Juu'),
    ('TZ-02-D03-C01-W13','Mbweni'),
    ('TZ-02-D03-C01-W14','Mikocheni'),
    ('TZ-02-D03-C01-W15','Msasani'),
    ('TZ-02-D03-C01-W16','Mwananyamala'),
    ('TZ-02-D03-C01-W17','Mzimuni'),
    ('TZ-02-D03-C01-W18','Ndugumbi'),
    ('TZ-02-D03-C01-W19','Tandale'),
    ('TZ-02-D03-C01-W20','Wazo')
) AS v(code, name)
ON CONFLICT (public_id) DO NOTHING;

-- ---- Ilala City Council (TZ-02-D01-C01) — 36 wards. ----
INSERT INTO location (public_id, version, created_at, created_by, type, parent_id, code, name, status)
SELECT seed_uuid('WARD', v.code), 0, now(), '00000000-0000-0000-0000-000000000000'::uuid,
       'WARD',
       (SELECT id FROM location c WHERE c.code = 'TZ-02-D01-C01' AND c.type = 'COUNCIL'),
       v.code, v.name, 'ACTIVE'
FROM (VALUES
    ('TZ-02-D01-C01-W01','Bonyokwa'),
    ('TZ-02-D01-C01-W02','Buguruni'),
    ('TZ-02-D01-C01-W03','Buyuni'),
    ('TZ-02-D01-C01-W04','Chanika'),
    ('TZ-02-D01-C01-W05','Gerezani'),
    ('TZ-02-D01-C01-W06','Gongolamboto'),
    ('TZ-02-D01-C01-W07','Ilala'),
    ('TZ-02-D01-C01-W08','Jangwani'),
    ('TZ-02-D01-C01-W09','Kariakoo'),
    ('TZ-02-D01-C01-W10','Kimanga'),
    ('TZ-02-D01-C01-W11','Kinyerezi'),
    ('TZ-02-D01-C01-W12','Kipawa'),
    ('TZ-02-D01-C01-W13','Kipunguni'),
    ('TZ-02-D01-C01-W14','Kisukuru'),
    ('TZ-02-D01-C01-W15','Kisutu'),
    ('TZ-02-D01-C01-W16','Kitunda'),
    ('TZ-02-D01-C01-W17','Kivukoni'),
    ('TZ-02-D01-C01-W18','Kivule'),
    ('TZ-02-D01-C01-W19','Kiwalani'),
    ('TZ-02-D01-C01-W20','Liwiti'),
    ('TZ-02-D01-C01-W21','Majohe'),
    ('TZ-02-D01-C01-W22','Mchafukoge'),
    ('TZ-02-D01-C01-W23','Mchikichini'),
    ('TZ-02-D01-C01-W24','Minazi Mirefu'),
    ('TZ-02-D01-C01-W25','Mnyamani'),
    ('TZ-02-D01-C01-W26','Msongola'),
    ('TZ-02-D01-C01-W27','Mzinga'),
    ('TZ-02-D01-C01-W28','Pugu'),
    ('TZ-02-D01-C01-W29','Pugu Station'),
    ('TZ-02-D01-C01-W30','Segerea'),
    ('TZ-02-D01-C01-W31','Tabata'),
    ('TZ-02-D01-C01-W32','Ukonga'),
    ('TZ-02-D01-C01-W33','Upanga East'),
    ('TZ-02-D01-C01-W34','Upanga West'),
    ('TZ-02-D01-C01-W35','Vingunguti'),
    ('TZ-02-D01-C01-W36','Zingiziwa')
) AS v(code, name)
ON CONFLICT (public_id) DO NOTHING;
