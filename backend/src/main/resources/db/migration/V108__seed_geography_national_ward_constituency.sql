-- =============================================================================
-- V108__seed_geography_national_ward_constituency.sql
--   Effective-dated Ward(Kata) -> Constituency(Jimbo) mappings, NATIONAL anchor set.
--
-- Completes "find-my-rep" nationwide by mapping the V107 representative anchor wards to
-- their constituency, through the effective-dated ward_constituency bridge — the single
-- most important temporal-correctness table (EI-14). The pilot regions (Rombo, Dar
-- self-anchors) are already mapped in V79; this covers the other 24 regions.
--
-- TEMPORAL CONTRACT (the "why", per CLAUDE.md §8) — the DB owns two invariants this seed
-- must respect, so each ward appears here AT MOST ONCE with effective_to NULL:
--   * ux_ward_constituency_current  — at most ONE current (effective_to IS NULL) per ward;
--   * ex_ward_constituency_no_overlap — no overlapping date ranges per ward.
-- effective_from = 2020-10-28 (the 2020 general-election delimitation, current as of seed);
-- effective_to = NULL (currently in effect). Re-delimitation will later CLOSE a row and
-- INSERT a new one, never rewrite history. public_id is deterministic from
-- "<ward_code>@<constituency_code>" so re-runs match the same row (idempotent).
--
-- MAPPING RULE — VERIFIED vs UNVERIFIED (no fabricated splits):
--   * SINGLE-CONSTITUENCY DISTRICT (the whole district/LGA is one jimbo): EVERY ward in it
--     maps unambiguously to that one constituency — authoritative. Most rural anchor wards
--     below are this case (e.g. Karatu ward -> Karatu, Liwale ward -> Liwale).            [VERIFIED at district granularity]
--   * MULTI-CONSTITUENCY DISTRICT (a district split across 2+ majimbo, e.g. Iringa Rural =
--     Kalenga + Isimani; Kilwa = Kilwa North + South): only the SELF-NAMED anchor ward is
--     mapped to its same-named constituency. The intra-district ward split for the OTHER
--     constituencies is NOT obtainable from accessible NEC sources and is LEFT UNMAPPED on
--     purpose — a fabricated split would corrupt electoral attribution. Those wards are the
--     known enrichment gap (owner: per-region R4/R5 geography gate, bulk import UC-B08) and
--     must be filled from the official NEC kata->jimbo delimitation before that region's
--     go-live.                                                                            [GAP, intentional]
--   * Where an anchor ward's name does not itself match a constituency but its district has
--     a single jimbo, it still maps to that jimbo (district-level certainty). Pairings that
--     home an anchor to one of several same-district majimbo carry a trailing [PICK] note.
--
-- Resolution is by CODE via subselects; a pair whose ward or constituency code is absent
-- yields NULL and the row is skipped by the NOT NULL columns — so partial coverage never
-- aborts the migration. created_by = SYSTEM; version 0. Idempotent. Forward-only.
-- =============================================================================

INSERT INTO ward_constituency
    (public_id, version, created_at, created_by, ward_id, constituency_id, effective_from, effective_to)
SELECT seed_uuid('WARDCONST', v.ward_code || '@' || v.constituency_code), 0, now(),
       '00000000-0000-0000-0000-000000000000'::uuid,
       (SELECT id FROM location w     WHERE w.code = v.ward_code AND w.type = 'WARD'),
       (SELECT id FROM constituency c WHERE c.code = v.constituency_code),
       DATE '2020-10-28', NULL
FROM (VALUES
    -- ===== Arusha =====
    ('TZ-01-D05-C01-W01','JIMBO-ARUMERU-EAST'),   -- Meru (single jimbo: Arumeru East)  [PICK]
    ('TZ-01-D02-C01-W01','JIMBO-ARUMERU-WEST'),   -- Arusha Rural -> Arumeru West        [PICK]
    ('TZ-01-D01-C01-W01','JIMBO-ARUSHA-URBAN'),   -- Arusha City (single jimbo)
    ('TZ-01-D03-C01-W01','JIMBO-KARATU'),         -- Karatu (single jimbo) VERIFIED
    ('TZ-01-D04-C01-W01','JIMBO-LONGIDO'),        -- Longido (single jimbo) VERIFIED
    ('TZ-01-D06-C01-W01','JIMBO-MONDULI'),        -- Monduli (single jimbo) VERIFIED
    ('TZ-01-D07-C01-W01','JIMBO-NGORONGORO'),     -- Ngorongoro (single jimbo) VERIFIED
    -- ===== Dodoma =====
    ('TZ-03-D01-C01-W01','JIMBO-BAHI'),           -- Bahi (single jimbo) VERIFIED
    ('TZ-03-D02-C01-W01','JIMBO-CHILONWA'),       -- Chamwino = Chilonwa+Mtera; anchor -> Chilonwa  [PICK]
    ('TZ-03-D03-C01-W01','JIMBO-CHEMBA'),         -- Chemba (single jimbo) VERIFIED
    ('TZ-03-D04-C01-W01','JIMBO-DODOMA-URBAN'),   -- Dodoma City (single jimbo)
    ('TZ-03-D06-C01-W01','JIMBO-KONDOA'),         -- Kondoa Town -> Kondoa
    ('TZ-03-D07-C01-W01','JIMBO-KONGWA'),         -- Kongwa (single jimbo) VERIFIED
    ('TZ-03-D08-C01-W01','JIMBO-MPWAPWA'),        -- Mpwapwa = Mpwapwa+Kibakwe; anchor -> Mpwapwa  [PICK]
    -- ===== Geita =====
    ('TZ-27-D01-C01-W01','JIMBO-BUKOMBE'),        -- Bukombe (single jimbo) VERIFIED
    ('TZ-27-D02-C01-W01','JIMBO-CHATO'),          -- Chato (single jimbo) VERIFIED
    ('TZ-27-D03-C01-W01','JIMBO-GEITA'),          -- Geita Rural -> Geita
    ('TZ-27-D05-C01-W01','JIMBO-MBOGWE'),         -- Mbogwe (single jimbo) VERIFIED
    ('TZ-27-D06-C01-W01','JIMBO-NYANGHWALE'),     -- Nyang'hwale (single jimbo) VERIFIED
    -- ===== Iringa ===== (Iringa Rural is multi: Kalenga + Isimani)
    ('TZ-04-D01-C01-W01','JIMBO-IRINGA-URBAN'),   -- Iringa Municipal (single jimbo)
    ('TZ-04-D02-C01-W01','JIMBO-KALENGA'),        -- Iringa Rural anchor Kalenga -> Kalenga  [PICK; Isimani wards GAP]
    ('TZ-04-D03-C01-W01','JIMBO-KILOLO'),         -- Kilolo (single jimbo) VERIFIED
    ('TZ-04-D05-C01-W01','JIMBO-MUFINDI-NORTH'),  -- Mufindi = North+South; anchor -> North  [PICK]
    -- ===== Kagera =====
    ('TZ-05-D01-C01-W01','JIMBO-BIHARAMULO-WEST'),-- Biharamulo -> Biharamulo West
    ('TZ-05-D03-C01-W01','JIMBO-BUKOBA-RURAL'),   -- Bukoba Rural (single jimbo) VERIFIED
    ('TZ-05-D02-C01-W01','JIMBO-BUKOBA-URBAN'),   -- Bukoba Municipal (single jimbo)
    ('TZ-05-D04-C01-W01','JIMBO-KARAGWE'),        -- Karagwe (single jimbo) VERIFIED
    ('TZ-05-D05-C01-W01','JIMBO-KYERWA'),         -- Kyerwa (single jimbo) VERIFIED
    ('TZ-05-D06-C01-W01','JIMBO-NKENGE'),         -- Missenyi -> Nkenge
    ('TZ-05-D07-C01-W01','JIMBO-MULEBA-NORTH'),   -- Muleba = North+South; anchor -> North  [PICK]
    ('TZ-05-D08-C01-W01','JIMBO-NGARA'),          -- Ngara (single jimbo) VERIFIED
    -- ===== Katavi =====
    ('TZ-28-D02-C01-W01','JIMBO-MPANDA-URBAN'),   -- Mpanda Municipal (single jimbo)
    ('TZ-28-D03-C01-W01','JIMBO-KAVUU'),          -- Mpimbwe -> Kavuu  [UNVERIFIED]
    ('TZ-28-D01-C01-W01','JIMBO-MLELE'),          -- Mlele (single jimbo) VERIFIED
    ('TZ-28-D04-C01-W01','JIMBO-NSIMBO'),         -- Nsimbo (single jimbo) VERIFIED
    ('TZ-28-D05-C01-W01','JIMBO-KATAVI'),         -- Tanganyika -> Katavi  [UNVERIFIED]
    -- ===== Kigoma =====
    ('TZ-08-D01-C01-W01','JIMBO-BUHIGWE'),        -- Buhigwe (single jimbo) VERIFIED
    ('TZ-08-D02-C01-W01','JIMBO-BUYUNGU'),        -- Kakonko -> Buyungu
    ('TZ-08-D03-C01-W01','JIMBO-KASULU-RURAL'),   -- Kasulu Rural (single jimbo)
    ('TZ-08-D04-C01-W01','JIMBO-KASULU-URBAN'),   -- Kasulu Town -> Kasulu Urban
    ('TZ-08-D05-C01-W01','JIMBO-KIBONDO'),        -- Kibondo = Kibondo+Muhambwe; anchor -> Kibondo  [PICK]
    ('TZ-08-D06-C01-W01','JIMBO-KIGOMA-URBAN'),   -- Kigoma Municipal -> Kigoma Urban
    ('TZ-08-D07-C01-W01','JIMBO-KIGOMA-NORTH'),   -- Kigoma Rural -> Kigoma North
    ('TZ-08-D08-C01-W01','JIMBO-KIGOMA-SOUTH'),   -- Uvinza -> Kigoma South
    -- ===== Lindi ===== (Kilwa is multi: North + South)
    ('TZ-12-D01-C01-W01','JIMBO-KILWA-NORTH'),    -- Kilwa = North+South; anchor -> North  [PICK; South wards GAP]
    ('TZ-12-D02-C01-W01','JIMBO-LINDI-URBAN'),    -- Lindi Municipal (single jimbo)
    ('TZ-12-D03-C01-W01','JIMBO-LIWALE'),         -- Liwale (single jimbo) VERIFIED
    ('TZ-12-D04-C01-W01','JIMBO-MTAMA'),          -- Mtama (single jimbo) VERIFIED
    ('TZ-12-D05-C01-W01','JIMBO-NACHINGWEA'),     -- Nachingwea (single jimbo) VERIFIED
    ('TZ-12-D06-C01-W01','JIMBO-RUANGWA'),        -- Ruangwa (single jimbo) VERIFIED
    -- ===== Manyara =====
    ('TZ-26-D01-C01-W01','JIMBO-BABATI-RURAL'),   -- Babati Rural (single jimbo)
    ('TZ-26-D02-C01-W01','JIMBO-BABATI-URBAN'),   -- Babati Town -> Babati Urban
    ('TZ-26-D03-C01-W01','JIMBO-HANANG'),         -- Hanang (single jimbo) VERIFIED
    ('TZ-26-D05-C01-W01','JIMBO-MBULU'),          -- Mbulu Rural -> Mbulu
    ('TZ-26-D04-C01-W01','JIMBO-KITETO'),         -- Kiteto (single jimbo) VERIFIED
    ('TZ-26-D07-C01-W01','JIMBO-SIMANJIRO'),      -- Simanjiro (single jimbo) VERIFIED
    -- ===== Mara ===== (Bunda is multi: Bunda + Mwibara)
    ('TZ-13-D01-C01-W01','JIMBO-BUNDA'),          -- Bunda Rural anchor -> Bunda  [PICK; Mwibara wards GAP]
    ('TZ-13-D05-C01-W01','JIMBO-MUSOMA-RURAL'),   -- Musoma Rural (single jimbo) VERIFIED
    ('TZ-13-D04-C01-W01','JIMBO-MUSOMA-URBAN'),   -- Musoma Municipal -> Musoma Urban
    ('TZ-13-D06-C01-W01','JIMBO-RORYA'),          -- Rorya (single jimbo) VERIFIED
    ('TZ-13-D07-C01-W01','JIMBO-SERENGETI'),      -- Serengeti (single jimbo) VERIFIED
    ('TZ-13-D08-C01-W01','JIMBO-TARIME-RURAL'),   -- Tarime Rural (single jimbo)
    ('TZ-13-D09-C01-W01','JIMBO-TARIME-URBAN'),   -- Tarime Town -> Tarime Urban
    -- ===== Mbeya =====
    ('TZ-14-D05-C01-W01','JIMBO-MBEYA-URBAN'),    -- Mbeya City -> Mbeya Urban
    ('TZ-14-D02-C01-W01','JIMBO-LUPA'),           -- Chunya -> Lupa
    ('TZ-14-D04-C01-W01','JIMBO-MBARALI'),        -- Mbarali (single jimbo) VERIFIED
    ('TZ-14-D03-C01-W01','JIMBO-KYELA'),          -- Kyela (single jimbo) VERIFIED
    ('TZ-14-D07-C01-W01','JIMBO-RUNGWE'),         -- Rungwe (single jimbo) VERIFIED
    ('TZ-14-D01-C01-W01','JIMBO-BUSEKELO'),       -- Busekelo (single jimbo) VERIFIED
    -- ===== Morogoro ===== (Kilosa is multi: Kilosa + Mikumi)
    ('TZ-16-D01-C01-W01','JIMBO-GAIRO'),          -- Gairo (single jimbo) VERIFIED
    ('TZ-16-D02-C01-W01','JIMBO-KILOMBERO'),      -- Ifakara Town -> Kilombero
    ('TZ-16-D03-C01-W01','JIMBO-KILOSA'),         -- Kilosa anchor -> Kilosa  [PICK; Mikumi wards GAP]
    ('TZ-16-D04-C01-W01','JIMBO-MALINYI'),        -- Malinyi (single jimbo) VERIFIED
    ('TZ-16-D05-C01-W01','JIMBO-MLIMBA'),         -- Mlimba (single jimbo) VERIFIED
    ('TZ-16-D06-C01-W01','JIMBO-MOROGORO-URBAN'), -- Morogoro Municipal -> Morogoro Urban
    ('TZ-16-D07-C01-W01','JIMBO-MOROGORO-SOUTH'), -- Morogoro Rural -> Morogoro South  [PICK]
    ('TZ-16-D08-C01-W01','JIMBO-MVOMERO'),        -- Mvomero (single jimbo) VERIFIED
    ('TZ-16-D09-C01-W01','JIMBO-ULANGA'),         -- Ulanga (single jimbo) VERIFIED
    -- ===== Mtwara =====
    ('TZ-17-D02-C01-W01','JIMBO-MASASI'),         -- Masasi Town -> Masasi
    ('TZ-17-D01-C01-W01','JIMBO-LULINDI'),        -- Masasi Rural -> Lulindi
    ('TZ-17-D03-C01-W01','JIMBO-MTWARA-URBAN'),   -- Mtwara Municipal -> Mtwara Urban
    ('TZ-17-D04-C01-W01','JIMBO-MTWARA-RURAL'),   -- Mtwara Rural (single jimbo)
    ('TZ-17-D06-C01-W01','JIMBO-NANYUMBU'),       -- Nanyumbu (single jimbo) VERIFIED
    ('TZ-17-D08-C01-W01','JIMBO-NEWALA-URBAN'),   -- Newala Town -> Newala Urban
    ('TZ-17-D07-C01-W01','JIMBO-NEWALA-RURAL'),   -- Newala Rural (single jimbo)
    ('TZ-17-D09-C01-W01','JIMBO-TANDAHIMBA'),     -- Tandahimba (single jimbo) VERIFIED
    -- ===== Mwanza =====
    ('TZ-18-D06-C01-W01','JIMBO-NYAMAGANA'),      -- Mwanza City -> Nyamagana
    ('TZ-18-D02-C01-W01','JIMBO-ILEMELA'),        -- Ilemela Municipal -> Ilemela
    ('TZ-18-D01-C01-W01','JIMBO-BUCHOSA'),        -- Buchosa (single jimbo) VERIFIED
    ('TZ-18-D03-C01-W01','JIMBO-SUMVE'),          -- Kwimba = Kwimba+Sumve; anchor Sumve -> Sumve  [PICK]
    ('TZ-18-D04-C01-W01','JIMBO-MAGU'),           -- Magu (single jimbo) VERIFIED
    ('TZ-18-D05-C01-W01','JIMBO-MISUNGWI'),       -- Misungwi (single jimbo) VERIFIED
    ('TZ-18-D07-C01-W01','JIMBO-SENGEREMA'),      -- Sengerema (single jimbo) VERIFIED
    ('TZ-18-D08-C01-W01','JIMBO-UKEREWE'),        -- Ukerewe (single jimbo) VERIFIED
    -- ===== Njombe =====
    ('TZ-29-D01-C01-W01','JIMBO-LUDEWA'),         -- Ludewa (single jimbo) VERIFIED
    ('TZ-29-D02-C01-W01','JIMBO-MAKAMBAKO'),      -- Makambako Town -> Makambako
    ('TZ-29-D03-C01-W01','JIMBO-MAKETE'),         -- Makete (single jimbo) VERIFIED
    ('TZ-29-D04-C01-W01','JIMBO-NJOMBE-NORTH'),   -- Njombe Rural -> Njombe North
    ('TZ-29-D05-C01-W01','JIMBO-NJOMBE-URBAN'),   -- Njombe Town -> Njombe Urban
    ('TZ-29-D06-C01-W01','JIMBO-WANGINGOMBE'),    -- Wanging'ombe (single jimbo) VERIFIED
    -- ===== Pwani =====
    ('TZ-19-D01-C01-W01','JIMBO-BAGAMOYO'),       -- Bagamoyo (single jimbo) VERIFIED
    ('TZ-19-D02-C01-W01','JIMBO-CHALINZE'),       -- Chalinze (single jimbo) VERIFIED
    ('TZ-19-D03-C01-W01','JIMBO-KIBAHA'),         -- Kibaha (rural) -> Kibaha
    ('TZ-19-D04-C01-W01','JIMBO-KIBAHA-URBAN'),   -- Kibaha Town -> Kibaha Urban
    ('TZ-19-D05-C01-W01','JIMBO-KIBITI'),         -- Kibiti (single jimbo) VERIFIED
    ('TZ-19-D06-C01-W01','JIMBO-KISARAWE'),       -- Kisarawe (single jimbo) VERIFIED
    ('TZ-19-D07-C01-W01','JIMBO-MAFIA'),          -- Mafia (single jimbo) VERIFIED
    ('TZ-19-D08-C01-W01','JIMBO-MKURANGA'),       -- Mkuranga (single jimbo) VERIFIED
    ('TZ-19-D09-C01-W01','JIMBO-RUFIJI'),         -- Rufiji (single jimbo) VERIFIED
    -- ===== Rukwa ===== (Nkasi is multi: North + South)
    ('TZ-20-D01-C01-W01','JIMBO-KALAMBO'),        -- Kalambo (single jimbo) VERIFIED
    ('TZ-20-D02-C01-W01','JIMBO-NKASI-NORTH'),    -- Nkasi = North+South; anchor -> North  [PICK; South wards GAP]
    ('TZ-20-D03-C01-W01','JIMBO-SUMBAWANGA-URBAN'),-- Sumbawanga Municipal -> Sumbawanga Urban
    ('TZ-20-D04-C01-W01','JIMBO-KWELA'),          -- Sumbawanga Rural -> Kwela
    -- ===== Ruvuma ===== (Mbinga Rural is multi: East+West; Tunduru is multi: North+South)
    ('TZ-21-D02-C01-W01','JIMBO-MBINGA-EAST'),    -- Mbinga Rural anchor -> Mbinga East  [PICK; West wards GAP]
    ('TZ-21-D04-C01-W01','JIMBO-NAMTUMBO'),       -- Namtumbo (single jimbo) VERIFIED
    ('TZ-21-D07-C01-W01','JIMBO-PERAMIHO'),       -- Songea Rural -> Peramiho
    ('TZ-21-D06-C01-W01','JIMBO-SONGEA-URBAN'),   -- Songea Municipal -> Songea Urban
    ('TZ-21-D05-C01-W01','JIMBO-NYASA'),          -- Nyasa (single jimbo) VERIFIED
    ('TZ-21-D08-C01-W01','JIMBO-TUNDURU-NORTH'),  -- Tunduru anchor -> Tunduru North  [PICK; South wards GAP]
    ('TZ-21-D01-C01-W01','JIMBO-MADABA'),         -- Madaba (single jimbo) VERIFIED
    -- ===== Shinyanga =====
    ('TZ-22-D01-C01-W01','JIMBO-KAHAMA-URBAN'),   -- Kahama Municipal -> Kahama Urban
    ('TZ-22-D03-C01-W01','JIMBO-MSALALA'),        -- Msalala (single jimbo) VERIFIED
    ('TZ-22-D06-C01-W01','JIMBO-USHETU'),         -- Ushetu (single jimbo) VERIFIED
    ('TZ-22-D02-C01-W01','JIMBO-KISHAPU'),        -- Kishapu (single jimbo) VERIFIED
    ('TZ-22-D04-C01-W01','JIMBO-SHINYANGA-URBAN'),-- Shinyanga Municipal -> Shinyanga Urban
    ('TZ-22-D05-C01-W01','JIMBO-SOLWA'),          -- Shinyanga Rural -> Solwa
    -- ===== Simiyu ===== (Maswa is multi: East+West)
    ('TZ-30-D01-C01-W01','JIMBO-BARIADI'),        -- Bariadi Rural -> Bariadi
    ('TZ-30-D02-C01-W01','JIMBO-BARIADI-URBAN'),  -- Bariadi Town -> Bariadi Urban
    ('TZ-30-D03-C01-W01','JIMBO-BUSEGA'),         -- Busega (single jimbo) VERIFIED
    ('TZ-30-D04-C01-W01','JIMBO-ITILIMA'),        -- Itilima (single jimbo) VERIFIED
    ('TZ-30-D05-C01-W01','JIMBO-MASWA-EAST'),     -- Maswa anchor -> Maswa East  [PICK; West wards GAP]
    ('TZ-30-D06-C01-W01','JIMBO-MEATU'),          -- Meatu (single jimbo) VERIFIED
    -- ===== Singida ===== (Iramba multi: E+W; Manyoni multi: E+W; Ikungi -> Singida N+W)
    ('TZ-23-D02-C01-W01','JIMBO-IRAMBA-EAST'),    -- Iramba anchor -> Iramba East  [PICK; West wards GAP]
    ('TZ-23-D05-C01-W01','JIMBO-MKALAMA'),        -- Mkalama (single jimbo) VERIFIED
    ('TZ-23-D04-C01-W01','JIMBO-MANYONI-EAST'),   -- Manyoni anchor -> Manyoni East  [PICK; West wards GAP]
    ('TZ-23-D03-C01-W01','JIMBO-ITIGI'),          -- Itigi (single jimbo) VERIFIED
    ('TZ-23-D07-C01-W01','JIMBO-SINGIDA-EAST'),   -- Singida Rural -> Singida East
    ('TZ-23-D01-C01-W01','JIMBO-SINGIDA-NORTH'),  -- Ikungi anchor -> Singida North  [PICK; Singida West wards GAP]
    ('TZ-23-D06-C01-W01','JIMBO-SINGIDA-URBAN'),  -- Singida Municipal -> Singida Urban
    -- ===== Tabora ===== (Uyui multi: Igalula + Tabora North)
    ('TZ-24-D01-C01-W01','JIMBO-IGUNGA'),         -- Igunga (single jimbo) VERIFIED
    ('TZ-24-D03-C01-W01','JIMBO-BUKENE'),         -- Nzega Rural -> Bukene
    ('TZ-24-D04-C01-W01','JIMBO-NZEGA-URBAN'),    -- Nzega Town -> Nzega Urban
    ('TZ-24-D08-C01-W01','JIMBO-IGALULA'),        -- Uyui anchor -> Igalula  [PICK; Tabora North wards GAP]
    ('TZ-24-D06-C01-W01','JIMBO-TABORA-URBAN'),   -- Tabora Municipal -> Tabora Urban
    ('TZ-24-D05-C01-W01','JIMBO-SIKONGE'),        -- Sikonge (single jimbo) VERIFIED
    ('TZ-24-D02-C01-W01','JIMBO-KALIUA'),         -- Kaliua (single jimbo) VERIFIED
    ('TZ-24-D07-C01-W01','JIMBO-URAMBO'),         -- Urambo (single jimbo) VERIFIED
    -- ===== Songwe ===== (Mbozi is multi: Mbozi + Vwawa)
    ('TZ-31-D01-C01-W01','JIMBO-ILEJE'),          -- Ileje (single jimbo) VERIFIED
    ('TZ-31-D02-C01-W01','JIMBO-VWAWA'),          -- Mbozi anchor Vwawa -> Vwawa  [PICK; Mbozi-jimbo wards GAP]
    ('TZ-31-D03-C01-W01','JIMBO-MOMBA'),          -- Momba (single jimbo) VERIFIED
    ('TZ-31-D04-C01-W01','JIMBO-SONGWE'),         -- Songwe (single jimbo) VERIFIED
    ('TZ-31-D05-C01-W01','JIMBO-TUNDUMA'),        -- Tunduma Town -> Tunduma
    -- ===== Tanga ===== (Lushoto multi: Lushoto + Mlalo)
    ('TZ-25-D01-C01-W01','JIMBO-BUMBULI'),        -- Bumbuli (single jimbo) VERIFIED
    ('TZ-25-D02-C01-W01','JIMBO-HANDENI'),        -- Handeni Rural -> Handeni
    ('TZ-25-D03-C01-W01','JIMBO-HANDENI-URBAN'),  -- Handeni Town -> Handeni Urban
    ('TZ-25-D04-C01-W01','JIMBO-KILINDI'),        -- Kilindi (single jimbo) VERIFIED
    ('TZ-25-D05-C01-W01','JIMBO-KOROGWE-RURAL'),  -- Korogwe Rural (single jimbo)
    ('TZ-25-D06-C01-W01','JIMBO-KOROGWE-URBAN'),  -- Korogwe Town -> Korogwe Urban
    ('TZ-25-D07-C01-W01','JIMBO-LUSHOTO'),        -- Lushoto anchor -> Lushoto  [PICK; Mlalo wards GAP]
    ('TZ-25-D08-C01-W01','JIMBO-MKINGA'),         -- Mkinga (single jimbo) VERIFIED
    ('TZ-25-D09-C01-W01','JIMBO-MUHEZA'),         -- Muheza (single jimbo) VERIFIED
    ('TZ-25-D10-C01-W01','JIMBO-PANGANI'),        -- Pangani (single jimbo) VERIFIED
    ('TZ-25-D11-C01-W01','JIMBO-TANGA-URBAN')     -- Tanga City -> Tanga Urban
) AS v(ward_code, constituency_code)
ON CONFLICT (public_id) DO NOTHING;
