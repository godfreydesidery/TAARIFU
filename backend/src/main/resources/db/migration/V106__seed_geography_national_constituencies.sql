-- =============================================================================
-- V106__seed_geography_national_constituencies.sql  —  Constituencies (Majimbo), NATIONAL.
--
-- Extends the electoral overlay (constituency / Jimbo) from the two pilot regions
-- (Kilimanjaro + Dar, V78) to ALL 26 mainland regions, so "find-my-rep" resolves a
-- Constituency (and thus an MP seat) anywhere on the mainland. A Constituency (Jimbo) is
-- the seat an MP (Mbunge) is elected to; it OVERLAYS the admin chain and is its own
-- entity (PRD §9.0), never an admin level.
--
-- SOURCE (the "why" / provenance, per CLAUDE.md §8):
--   * Constituency NAMES per region are taken from Wikipedia "List of constituencies of
--     Tanzania" (the public enumeration of mainland Bunge constituencies for the term
--     current as of seed). Where that list still groups a jimbo under its PRE-2012 parent
--     region (Njombe/Geita/Simiyu/Katavi/Songwe were gazetted out of Iringa/Mwanza/
--     Shinyanga/Rukwa/Mbeya in 2012/2016), it is RE-HOMED here to its CURRENT region and
--     district so the F2 anchor is region-current. Those re-homings are marked [RE-HOMED].
--   * district_id is a HOMING/DISPLAY anchor ONLY (F2): it names the DISTRICT a jimbo is
--     principally associated with. A constituency's ACTUAL member wards are driven SOLELY
--     by the effective-dated ward_constituency bridge (V108), never derived from this
--     anchor. Per F2 the subselect asserts district_id points at a type=DISTRICT row.
--   * Pilot regions (Kilimanjaro TZ-09, Dar TZ-02) are NOT re-seeded here — V78 already
--     owns them; this migration covers the other 24 regions. Idempotent regardless
--     (ON CONFLICT (public_id) DO NOTHING) so an accidental overlap is harmless.
--
-- VERIFICATION STATUS:
--   * Constituency-to-DISTRICT homing is a best-effort principal-district match by name
--     and is [UNVERIFIED] at the precise-district level for multi-district jimbo and for
--     the re-homed newer regions — it is correct at REGION level and sufficient for
--     find-my-rep, but the exact home district must be confirmed against the official
--     NEC/PO-RALG register at the per-region R4/R5 geography gate before that region's
--     go-live. The CODE (JIMBO-<slug>) is the stable idempotent match key and is final.
--
-- CODE format "JIMBO-<slug>"; public_id deterministic via seed_uuid('CONSTITUENCY', code).
-- created_by = SYSTEM sentinel; version 0. Idempotent. Forward-only; never edit applied.
-- =============================================================================

INSERT INTO constituency (public_id, version, created_at, created_by, code, name, district_id)
SELECT seed_uuid('CONSTITUENCY', v.code), 0, now(), '00000000-0000-0000-0000-000000000000'::uuid,
       v.code, v.name,
       (SELECT id FROM location d WHERE d.code = v.district_code AND d.type = 'DISTRICT')
FROM (VALUES
    -- ----- Arusha (TZ-01) -----
    ('JIMBO-ARUMERU-EAST',   'Arumeru East',   'TZ-01-D05'),  -- Meru
    ('JIMBO-ARUMERU-WEST',   'Arumeru West',   'TZ-01-D02'),  -- Arusha Rural
    ('JIMBO-ARUSHA-URBAN',   'Arusha Urban',   'TZ-01-D01'),  -- Arusha City
    ('JIMBO-KARATU',         'Karatu',         'TZ-01-D03'),
    ('JIMBO-LONGIDO',        'Longido',        'TZ-01-D04'),
    ('JIMBO-MONDULI',        'Monduli',        'TZ-01-D06'),
    ('JIMBO-NGORONGORO',     'Ngorongoro',     'TZ-01-D07'),
    -- ----- Dodoma (TZ-03) -----
    ('JIMBO-BAHI',           'Bahi',           'TZ-03-D01'),
    ('JIMBO-CHILONWA',       'Chilonwa',       'TZ-03-D02'),  -- Chamwino
    ('JIMBO-CHEMBA',         'Chemba',         'TZ-03-D03'),
    ('JIMBO-DODOMA-URBAN',   'Dodoma Urban',   'TZ-03-D04'),  -- Dodoma City
    ('JIMBO-KIBAKWE',        'Kibakwe',        'TZ-03-D08'),  -- Mpwapwa
    ('JIMBO-KONDOA',         'Kondoa',         'TZ-03-D06'),  -- Kondoa Town  [UNVERIFIED split N/S]
    ('JIMBO-KONGWA',         'Kongwa',         'TZ-03-D07'),
    ('JIMBO-MPWAPWA',        'Mpwapwa',        'TZ-03-D08'),
    ('JIMBO-MTERA',          'Mtera',          'TZ-03-D02'),  -- Chamwino
    -- ----- Geita (TZ-27) [RE-HOMED from Mwanza/Shinyanga groupings] -----
    ('JIMBO-BUKOMBE',        'Bukombe',        'TZ-27-D01'),
    ('JIMBO-CHATO',          'Chato',          'TZ-27-D02'),
    ('JIMBO-GEITA',          'Geita',          'TZ-27-D03'),  -- Geita Rural
    ('JIMBO-MBOGWE',         'Mbogwe',         'TZ-27-D05'),
    ('JIMBO-NYANGHWALE',     'Nyang''hwale',   'TZ-27-D06'),
    -- ----- Iringa (TZ-04) -----
    ('JIMBO-IRINGA-URBAN',   'Iringa Urban',   'TZ-04-D01'),  -- Iringa Municipal
    ('JIMBO-ISIMANI',        'Isimani',        'TZ-04-D02'),  -- Iringa Rural
    ('JIMBO-KALENGA',        'Kalenga',        'TZ-04-D02'),  -- Iringa Rural
    ('JIMBO-KILOLO',         'Kilolo',         'TZ-04-D03'),
    ('JIMBO-MUFINDI-NORTH',  'Mufindi North',  'TZ-04-D05'),  -- Mufindi
    ('JIMBO-MUFINDI-SOUTH',  'Mufindi South',  'TZ-04-D05'),  -- Mufindi
    -- ----- Kagera (TZ-05) -----
    ('JIMBO-BIHARAMULO-WEST','Biharamulo West','TZ-05-D01'),  -- Biharamulo
    ('JIMBO-BUKOBA-RURAL',   'Bukoba Rural',   'TZ-05-D03'),
    ('JIMBO-BUKOBA-URBAN',   'Bukoba Urban',   'TZ-05-D02'),  -- Bukoba Municipal
    ('JIMBO-KARAGWE',        'Karagwe',        'TZ-05-D04'),
    ('JIMBO-KYERWA',         'Kyerwa',         'TZ-05-D05'),
    ('JIMBO-NKENGE',         'Nkenge',         'TZ-05-D06'),  -- Missenyi
    ('JIMBO-MULEBA-NORTH',   'Muleba North',   'TZ-05-D07'),  -- Muleba
    ('JIMBO-MULEBA-SOUTH',   'Muleba South',   'TZ-05-D07'),  -- Muleba
    ('JIMBO-NGARA',          'Ngara',          'TZ-05-D08'),
    -- ----- Katavi (TZ-28) [RE-HOMED from Rukwa grouping] -----
    ('JIMBO-MPANDA-URBAN',   'Mpanda Urban',   'TZ-28-D02'),  -- Mpanda Municipal
    ('JIMBO-KAVUU',          'Kavuu',          'TZ-28-D03'),  -- Mpimbwe  [UNVERIFIED]
    ('JIMBO-MLELE',          'Mlele',          'TZ-28-D01'),
    ('JIMBO-NSIMBO',         'Nsimbo',         'TZ-28-D04'),
    ('JIMBO-KATAVI',         'Katavi',         'TZ-28-D05'),  -- Tanganyika  [UNVERIFIED]
    -- ----- Kigoma (TZ-08) -----
    ('JIMBO-BUYUNGU',        'Buyungu',        'TZ-08-D02'),  -- Kakonko
    ('JIMBO-BUHIGWE',        'Buhigwe',        'TZ-08-D01'),
    ('JIMBO-KASULU-RURAL',   'Kasulu Rural',   'TZ-08-D03'),
    ('JIMBO-KASULU-URBAN',   'Kasulu Urban',   'TZ-08-D04'),  -- Kasulu Town
    ('JIMBO-KIBONDO',        'Kibondo',        'TZ-08-D05'),
    ('JIMBO-KIGOMA-URBAN',   'Kigoma Urban',   'TZ-08-D06'),  -- Kigoma Municipal
    ('JIMBO-KIGOMA-NORTH',   'Kigoma North',   'TZ-08-D07'),  -- Kigoma Rural
    ('JIMBO-KIGOMA-SOUTH',   'Kigoma South',   'TZ-08-D08'),  -- Uvinza
    ('JIMBO-MUHAMBWE',       'Muhambwe',       'TZ-08-D05'),  -- Kibondo
    -- ----- Lindi (TZ-12) -----
    ('JIMBO-KILWA-NORTH',    'Kilwa North',    'TZ-12-D01'),  -- Kilwa
    ('JIMBO-KILWA-SOUTH',    'Kilwa South',    'TZ-12-D01'),  -- Kilwa
    ('JIMBO-LINDI-URBAN',    'Lindi Urban',    'TZ-12-D02'),  -- Lindi Municipal
    ('JIMBO-LIWALE',         'Liwale',         'TZ-12-D03'),
    ('JIMBO-MTAMA',          'Mtama',          'TZ-12-D04'),
    ('JIMBO-NACHINGWEA',     'Nachingwea',     'TZ-12-D05'),
    ('JIMBO-RUANGWA',        'Ruangwa',        'TZ-12-D06'),
    -- ----- Manyara (TZ-26) -----
    ('JIMBO-BABATI-RURAL',   'Babati Rural',   'TZ-26-D01'),
    ('JIMBO-BABATI-URBAN',   'Babati Urban',   'TZ-26-D02'),  -- Babati Town
    ('JIMBO-HANANG',         'Hanang',         'TZ-26-D03'),
    ('JIMBO-MBULU',          'Mbulu',          'TZ-26-D05'),  -- Mbulu Rural
    ('JIMBO-KITETO',         'Kiteto',         'TZ-26-D04'),
    ('JIMBO-SIMANJIRO',      'Simanjiro',      'TZ-26-D07'),
    -- ----- Mara (TZ-13) -----
    ('JIMBO-BUNDA',          'Bunda',          'TZ-13-D01'),  -- Bunda Rural
    ('JIMBO-MWIBARA',        'Mwibara',        'TZ-13-D01'),  -- Bunda Rural
    ('JIMBO-MUSOMA-RURAL',   'Musoma Rural',   'TZ-13-D05'),
    ('JIMBO-MUSOMA-URBAN',   'Musoma Urban',   'TZ-13-D04'),  -- Musoma Municipal
    ('JIMBO-RORYA',          'Rorya',          'TZ-13-D06'),
    ('JIMBO-SERENGETI',      'Serengeti',      'TZ-13-D07'),
    ('JIMBO-TARIME-RURAL',   'Tarime Rural',   'TZ-13-D08'),
    ('JIMBO-TARIME-URBAN',   'Tarime Urban',   'TZ-13-D09'),  -- Tarime Town
    -- ----- Mbeya (TZ-14) -----
    ('JIMBO-MBEYA-URBAN',    'Mbeya Urban',    'TZ-14-D05'),  -- Mbeya City
    ('JIMBO-LUPA',           'Lupa',           'TZ-14-D02'),  -- Chunya
    ('JIMBO-MBARALI',        'Mbarali',        'TZ-14-D04'),
    ('JIMBO-KYELA',          'Kyela',          'TZ-14-D03'),
    ('JIMBO-RUNGWE',         'Rungwe',         'TZ-14-D07'),
    ('JIMBO-BUSEKELO',       'Busekelo',       'TZ-14-D01'),
    -- ----- Morogoro (TZ-16) -----
    ('JIMBO-GAIRO',          'Gairo',          'TZ-16-D01'),
    ('JIMBO-KILOMBERO',      'Kilombero',      'TZ-16-D02'),  -- Ifakara Town
    ('JIMBO-KILOSA',         'Kilosa',         'TZ-16-D03'),
    ('JIMBO-MIKUMI',         'Mikumi',         'TZ-16-D03'),  -- Kilosa
    ('JIMBO-MALINYI',        'Malinyi',        'TZ-16-D04'),
    ('JIMBO-MLIMBA',         'Mlimba',         'TZ-16-D05'),
    ('JIMBO-MOROGORO-URBAN', 'Morogoro Urban', 'TZ-16-D06'),  -- Morogoro Municipal
    ('JIMBO-MOROGORO-SOUTH', 'Morogoro South', 'TZ-16-D07'),  -- Morogoro Rural
    ('JIMBO-MVOMERO',        'Mvomero',        'TZ-16-D08'),
    ('JIMBO-ULANGA',         'Ulanga',         'TZ-16-D09'),
    -- ----- Mtwara (TZ-17) -----
    ('JIMBO-MASASI',         'Masasi',         'TZ-17-D02'),  -- Masasi Town
    ('JIMBO-LULINDI',        'Lulindi',        'TZ-17-D01'),  -- Masasi Rural
    ('JIMBO-MTWARA-URBAN',   'Mtwara Urban',   'TZ-17-D03'),  -- Mtwara Municipal
    ('JIMBO-MTWARA-RURAL',   'Mtwara Rural',   'TZ-17-D04'),
    ('JIMBO-NANYUMBU',       'Nanyumbu',       'TZ-17-D06'),
    ('JIMBO-NEWALA-URBAN',   'Newala Urban',   'TZ-17-D08'),  -- Newala Town
    ('JIMBO-NEWALA-RURAL',   'Newala Rural',   'TZ-17-D07'),
    ('JIMBO-TANDAHIMBA',     'Tandahimba',     'TZ-17-D09'),
    -- ----- Mwanza (TZ-18) -----
    ('JIMBO-NYAMAGANA',      'Nyamagana',      'TZ-18-D06'),  -- Mwanza City
    ('JIMBO-ILEMELA',        'Ilemela',        'TZ-18-D02'),  -- Ilemela Municipal
    ('JIMBO-BUCHOSA',        'Buchosa',        'TZ-18-D01'),
    ('JIMBO-KWIMBA',         'Kwimba',         'TZ-18-D03'),
    ('JIMBO-SUMVE',          'Sumve',          'TZ-18-D03'),  -- Kwimba
    ('JIMBO-MAGU',           'Magu',           'TZ-18-D04'),
    ('JIMBO-MISUNGWI',       'Misungwi',       'TZ-18-D05'),
    ('JIMBO-SENGEREMA',      'Sengerema',      'TZ-18-D07'),
    ('JIMBO-UKEREWE',        'Ukerewe',        'TZ-18-D08'),
    -- ----- Njombe (TZ-29) [RE-HOMED from Iringa grouping] -----
    ('JIMBO-LUDEWA',         'Ludewa',         'TZ-29-D01'),
    ('JIMBO-MAKAMBAKO',      'Makambako',      'TZ-29-D02'),  -- Makambako Town
    ('JIMBO-MAKETE',         'Makete',         'TZ-29-D03'),
    ('JIMBO-NJOMBE-NORTH',   'Njombe North',   'TZ-29-D04'),  -- Njombe Rural
    ('JIMBO-NJOMBE-URBAN',   'Njombe Urban',   'TZ-29-D05'),  -- Njombe Town
    ('JIMBO-WANGINGOMBE',    'Wanging''ombe',  'TZ-29-D06'),
    -- ----- Pwani (TZ-19) -----
    ('JIMBO-BAGAMOYO',       'Bagamoyo',       'TZ-19-D01'),
    ('JIMBO-CHALINZE',       'Chalinze',       'TZ-19-D02'),
    ('JIMBO-KIBAHA',         'Kibaha',         'TZ-19-D03'),
    ('JIMBO-KIBAHA-URBAN',   'Kibaha Urban',   'TZ-19-D04'),  -- Kibaha Town
    ('JIMBO-KIBITI',         'Kibiti',         'TZ-19-D05'),
    ('JIMBO-KISARAWE',       'Kisarawe',       'TZ-19-D06'),
    ('JIMBO-MAFIA',          'Mafia',          'TZ-19-D07'),
    ('JIMBO-MKURANGA',       'Mkuranga',       'TZ-19-D08'),
    ('JIMBO-RUFIJI',         'Rufiji',         'TZ-19-D09'),
    -- ----- Rukwa (TZ-20) -----
    ('JIMBO-KALAMBO',        'Kalambo',        'TZ-20-D01'),
    ('JIMBO-NKASI-NORTH',    'Nkasi North',    'TZ-20-D02'),  -- Nkasi
    ('JIMBO-NKASI-SOUTH',    'Nkasi South',    'TZ-20-D02'),  -- Nkasi
    ('JIMBO-SUMBAWANGA-URBAN','Sumbawanga Urban','TZ-20-D03'), -- Sumbawanga Municipal
    ('JIMBO-KWELA',          'Kwela',          'TZ-20-D04'),  -- Sumbawanga Rural
    -- ----- Ruvuma (TZ-21) -----
    ('JIMBO-MBINGA-EAST',    'Mbinga East',    'TZ-21-D02'),  -- Mbinga Rural
    ('JIMBO-MBINGA-WEST',    'Mbinga West',    'TZ-21-D02'),  -- Mbinga Rural
    ('JIMBO-NAMTUMBO',       'Namtumbo',       'TZ-21-D04'),
    ('JIMBO-PERAMIHO',       'Peramiho',       'TZ-21-D07'),  -- Songea Rural
    ('JIMBO-SONGEA-URBAN',   'Songea Urban',   'TZ-21-D06'),  -- Songea Municipal
    ('JIMBO-NYASA',          'Nyasa',          'TZ-21-D05'),
    ('JIMBO-TUNDURU-NORTH',  'Tunduru North',  'TZ-21-D08'),  -- Tunduru
    ('JIMBO-TUNDURU-SOUTH',  'Tunduru South',  'TZ-21-D08'),  -- Tunduru
    ('JIMBO-MADABA',         'Madaba',         'TZ-21-D01'),  -- Madaba  [UNVERIFIED]
    -- ----- Shinyanga (TZ-22) -----
    ('JIMBO-KAHAMA-URBAN',   'Kahama Urban',   'TZ-22-D01'),  -- Kahama Municipal
    ('JIMBO-MSALALA',        'Msalala',        'TZ-22-D03'),
    ('JIMBO-USHETU',         'Ushetu',         'TZ-22-D06'),
    ('JIMBO-KISHAPU',        'Kishapu',        'TZ-22-D02'),
    ('JIMBO-SHINYANGA-URBAN','Shinyanga Urban','TZ-22-D04'),  -- Shinyanga Municipal
    ('JIMBO-SOLWA',          'Solwa',          'TZ-22-D05'),  -- Shinyanga Rural
    -- ----- Simiyu (TZ-30) [RE-HOMED from Shinyanga grouping] -----
    ('JIMBO-BARIADI',        'Bariadi',        'TZ-30-D01'),  -- Bariadi Rural
    ('JIMBO-BARIADI-URBAN',  'Bariadi Urban',  'TZ-30-D02'),  -- Bariadi Town
    ('JIMBO-BUSEGA',         'Busega',         'TZ-30-D03'),
    ('JIMBO-ITILIMA',        'Itilima',        'TZ-30-D04'),
    ('JIMBO-MASWA-EAST',     'Maswa East',     'TZ-30-D05'),  -- Maswa
    ('JIMBO-MASWA-WEST',     'Maswa West',     'TZ-30-D05'),  -- Maswa
    ('JIMBO-MEATU',          'Meatu',          'TZ-30-D06'),
    -- ----- Singida (TZ-23) -----
    ('JIMBO-IRAMBA-EAST',    'Iramba East',    'TZ-23-D02'),  -- Iramba
    ('JIMBO-IRAMBA-WEST',    'Iramba West',    'TZ-23-D02'),  -- Iramba
    ('JIMBO-MKALAMA',        'Mkalama',        'TZ-23-D05'),
    ('JIMBO-MANYONI-EAST',   'Manyoni East',   'TZ-23-D04'),  -- Manyoni
    ('JIMBO-MANYONI-WEST',   'Manyoni West',   'TZ-23-D04'),  -- Manyoni
    ('JIMBO-ITIGI',          'Itigi',          'TZ-23-D03'),
    ('JIMBO-SINGIDA-EAST',   'Singida East',   'TZ-23-D07'),  -- Singida Rural
    ('JIMBO-SINGIDA-NORTH',  'Singida North',  'TZ-23-D01'),  -- Ikungi  [UNVERIFIED]
    ('JIMBO-SINGIDA-WEST',   'Singida West',   'TZ-23-D01'),  -- Ikungi  [UNVERIFIED]
    ('JIMBO-SINGIDA-URBAN',  'Singida Urban',  'TZ-23-D06'),  -- Singida Municipal
    -- ----- Tabora (TZ-24) -----
    ('JIMBO-IGUNGA',         'Igunga',         'TZ-24-D01'),
    ('JIMBO-BUKENE',         'Bukene',         'TZ-24-D03'),  -- Nzega Rural
    ('JIMBO-NZEGA-URBAN',    'Nzega Urban',    'TZ-24-D04'),  -- Nzega Town
    ('JIMBO-IGALULA',        'Igalula',        'TZ-24-D08'),  -- Uyui
    ('JIMBO-TABORA-NORTH',   'Tabora North',   'TZ-24-D08'),  -- Uyui
    ('JIMBO-TABORA-URBAN',   'Tabora Urban',   'TZ-24-D06'),  -- Tabora Municipal
    ('JIMBO-SIKONGE',        'Sikonge',        'TZ-24-D05'),
    ('JIMBO-KALIUA',         'Kaliua',         'TZ-24-D02'),
    ('JIMBO-URAMBO',         'Urambo',         'TZ-24-D07'),
    -- ----- Songwe (TZ-31) [RE-HOMED from Mbeya grouping; carved out 2016] -----
    ('JIMBO-ILEJE',          'Ileje',          'TZ-31-D01'),
    ('JIMBO-MBOZI',          'Mbozi',          'TZ-31-D02'),  -- Mbozi (multi: Mbozi + Vwawa)
    ('JIMBO-VWAWA',          'Vwawa',          'TZ-31-D02'),  -- Mbozi
    ('JIMBO-MOMBA',          'Momba',          'TZ-31-D03'),
    ('JIMBO-SONGWE',         'Songwe',         'TZ-31-D04'),
    ('JIMBO-TUNDUMA',        'Tunduma',        'TZ-31-D05'),  -- Tunduma Town
    -- ----- Tanga (TZ-25) -----
    ('JIMBO-BUMBULI',        'Bumbuli',        'TZ-25-D01'),
    ('JIMBO-HANDENI',        'Handeni',        'TZ-25-D02'),  -- Handeni Rural
    ('JIMBO-HANDENI-URBAN',  'Handeni Urban',  'TZ-25-D03'),  -- Handeni Town
    ('JIMBO-KILINDI',        'Kilindi',        'TZ-25-D04'),
    ('JIMBO-KOROGWE-RURAL',  'Korogwe Rural',  'TZ-25-D05'),
    ('JIMBO-KOROGWE-URBAN',  'Korogwe Urban',  'TZ-25-D06'),  -- Korogwe Town
    ('JIMBO-LUSHOTO',        'Lushoto',        'TZ-25-D07'),
    ('JIMBO-MLALO',          'Mlalo',          'TZ-25-D07'),  -- Lushoto
    ('JIMBO-MKINGA',         'Mkinga',         'TZ-25-D08'),
    ('JIMBO-MUHEZA',         'Muheza',         'TZ-25-D09'),
    ('JIMBO-PANGANI',        'Pangani',        'TZ-25-D10'),
    ('JIMBO-TANGA-URBAN',    'Tanga Urban',    'TZ-25-D11')   -- Tanga City
) AS v(code, name, district_code)
ON CONFLICT (public_id) DO NOTHING;
