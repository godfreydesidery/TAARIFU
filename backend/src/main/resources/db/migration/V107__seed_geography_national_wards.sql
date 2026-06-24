-- =============================================================================
-- V107__seed_geography_national_wards.sql  —  Wards (Kata), NATIONAL representative set.
--
-- WARD (Kata) is the MINIMUM citizen pin (PRD §9.0): from a ward the system derives the
-- full admin chain AND — via the V108 ward_constituency bridge — the constituency, so
-- "ward-pick" and "find-my-rep" resolve. The pilot regions already carry their full ward
-- lists (Rombo 24 wards V75, Dar V76); this migration extends ward coverage to ALL 26
-- mainland regions so ward-pick works nationwide.
--
-- SCOPE & SOURCING (the "why" / provenance, per CLAUDE.md §8):
--   * Tanzania mainland has ~3,960 wards. A COMPLETE, authoritative ward enumeration AND
--     the per-constituency ward split are NOT available from any accessible open source
--     (confirmed: NEC/PO-RALG publish no machine-readable kata->jimbo delimitation table).
--     Fabricating a full per-district ward list — or worse, a ward->constituency split —
--     would corrupt electoral attribution (EI-14). We therefore seed a REPRESENTATIVE,
--     NON-FABRICATED set: for each constituency we seed its ANCHOR WARD — a REAL ward that
--     bears the constituency's name (the self-named kata that exists in virtually every
--     Tanzanian jimbo, e.g. Karatu ward in Karatu, Kibiti ward in Kibiti). Anchor wards
--     are factual and let V108 map each to its same-named constituency authoritatively.
--   * This is the known coverage boundary of the MVP seed: the FULL ward list per district
--     is the per-region R4/R5 geography-gate enrichment (bulk import UC-B08 from the
--     official PO-RALG ward register), NOT this migration. Each region thus has resolvable
--     ward-pick + find-my-rep for its anchor wards on day one; full ward coverage follows.
--
-- PARENT: per the F1 matrix a WARD's legal parent is a COUNCIL (or DIVISION, not modelled).
-- The council is the V105 national "<district_code>-C01" council; parent_id resolves to it.
-- A ward whose council row is absent is skipped by the inner subselect (NULL parent would
-- violate ck/NOT NULL) — so this is safe to run before/after V105 in any order Flyway gives.
--
-- CODE format "<council_code>-W<nn>" (== "<district_code>-C01-W<nn>"); type WARD.
-- public_id deterministic via seed_uuid('WARD', code). created_by = SYSTEM; status ACTIVE;
-- version 0. Idempotent (ON CONFLICT (public_id) DO NOTHING). Forward-only.
--
-- NOTE on numbering: ward ordinals here are LOCAL seed ordinals (W01..), NOT official ward
-- codes — the official ward code is an enrichment field. They are stable per (council, name)
-- so re-runs match the same row via public_id. [ward ordinal = seed-local, not authoritative]
-- =============================================================================

INSERT INTO location (public_id, version, created_at, created_by, type, parent_id, code, name, status)
SELECT seed_uuid('WARD', v.code), 0, now(), '00000000-0000-0000-0000-000000000000'::uuid,
       'WARD',
       (SELECT id FROM location c WHERE c.code = v.council_code AND c.type = 'COUNCIL'),
       v.code, v.name, 'ACTIVE'
FROM (VALUES
    -- ============ Arusha (TZ-01) — anchor wards per constituency ============
    ('TZ-01-D05-C01','TZ-01-D05-C01-W01','Akheri'),        -- Meru / Arumeru East
    ('TZ-01-D02-C01','TZ-01-D02-C01-W01','Mbuguni'),       -- Arusha Rural / Arumeru West
    ('TZ-01-D01-C01','TZ-01-D01-C01-W01','Kati'),          -- Arusha City / Arusha Urban
    ('TZ-01-D03-C01','TZ-01-D03-C01-W01','Karatu'),        -- Karatu (anchor)
    ('TZ-01-D04-C01','TZ-01-D04-C01-W01','Longido'),       -- Longido (anchor)
    ('TZ-01-D06-C01','TZ-01-D06-C01-W01','Monduli Mjini'), -- Monduli
    ('TZ-01-D07-C01','TZ-01-D07-C01-W01','Ngorongoro'),    -- Ngorongoro (anchor)
    -- ============ Dodoma (TZ-03) ============
    ('TZ-03-D01-C01','TZ-03-D01-C01-W01','Bahi'),          -- Bahi (anchor)
    ('TZ-03-D02-C01','TZ-03-D02-C01-W01','Chamwino'),      -- Chamwino / Chilonwa+Mtera
    ('TZ-03-D03-C01','TZ-03-D03-C01-W01','Chemba'),        -- Chemba (anchor)
    ('TZ-03-D04-C01','TZ-03-D04-C01-W01','Makole'),        -- Dodoma City / Dodoma Urban
    ('TZ-03-D06-C01','TZ-03-D06-C01-W01','Kondoa Mjini'),  -- Kondoa Town / Kondoa
    ('TZ-03-D07-C01','TZ-03-D07-C01-W01','Kongwa'),        -- Kongwa (anchor)
    ('TZ-03-D08-C01','TZ-03-D08-C01-W01','Mpwapwa'),       -- Mpwapwa (anchor) / Kibakwe
    -- ============ Geita (TZ-27) ============
    ('TZ-27-D01-C01','TZ-27-D01-C01-W01','Bukombe'),       -- Bukombe (anchor)
    ('TZ-27-D02-C01','TZ-27-D02-C01-W01','Chato'),         -- Chato (anchor)
    ('TZ-27-D03-C01','TZ-27-D03-C01-W01','Nzera'),         -- Geita Rural / Geita
    ('TZ-27-D05-C01','TZ-27-D05-C01-W01','Mbogwe'),        -- Mbogwe (anchor)
    ('TZ-27-D06-C01','TZ-27-D06-C01-W01','Kharumwa'),      -- Nyang'hwale
    -- ============ Iringa (TZ-04) ============
    ('TZ-04-D01-C01','TZ-04-D01-C01-W01','Mwangata'),      -- Iringa Municipal / Iringa Urban
    ('TZ-04-D02-C01','TZ-04-D02-C01-W01','Kalenga'),       -- Iringa Rural / Kalenga
    ('TZ-04-D02-C01','TZ-04-D02-C01-W02','Izazi'),         -- Iringa Rural / Isimani (anchor-ish)
    ('TZ-04-D03-C01','TZ-04-D03-C01-W01','Kilolo'),        -- Kilolo (anchor)
    ('TZ-04-D05-C01','TZ-04-D05-C01-W01','Mafinga'),       -- Mufindi / Mufindi North
    -- ============ Kagera (TZ-05) ============
    ('TZ-05-D01-C01','TZ-05-D01-C01-W01','Biharamulo Mjini'), -- Biharamulo / Biharamulo West
    ('TZ-05-D03-C01','TZ-05-D03-C01-W01','Katoro'),        -- Bukoba Rural
    ('TZ-05-D02-C01','TZ-05-D02-C01-W01','Bakoba'),        -- Bukoba Municipal / Bukoba Urban
    ('TZ-05-D04-C01','TZ-05-D04-C01-W01','Kayanga'),       -- Karagwe
    ('TZ-05-D05-C01','TZ-05-D05-C01-W01','Nkwenda'),       -- Kyerwa
    ('TZ-05-D06-C01','TZ-05-D06-C01-W01','Kyaka'),         -- Missenyi / Nkenge
    ('TZ-05-D07-C01','TZ-05-D07-C01-W01','Muleba'),        -- Muleba (anchor)
    ('TZ-05-D08-C01','TZ-05-D08-C01-W01','Ngara'),         -- Ngara (anchor)
    -- ============ Katavi (TZ-28) ============
    ('TZ-28-D02-C01','TZ-28-D02-C01-W01','Mpanda'),        -- Mpanda Municipal / Mpanda Urban
    ('TZ-28-D03-C01','TZ-28-D03-C01-W01','Mamba'),         -- Mpimbwe / Kavuu
    ('TZ-28-D01-C01','TZ-28-D01-C01-W01','Inyonga'),       -- Mlele (anchor)
    ('TZ-28-D04-C01','TZ-28-D04-C01-W01','Nsimbo'),        -- Nsimbo (anchor)
    ('TZ-28-D05-C01','TZ-28-D05-C01-W01','Mwese'),         -- Tanganyika / Katavi
    -- ============ Kigoma (TZ-08) ============
    ('TZ-08-D01-C01','TZ-08-D01-C01-W01','Buhigwe'),       -- Buhigwe (anchor)
    ('TZ-08-D02-C01','TZ-08-D02-C01-W01','Kakonko'),       -- Kakonko / Buyungu
    ('TZ-08-D03-C01','TZ-08-D03-C01-W01','Kasulu'),        -- Kasulu Rural / Kasulu Rural
    ('TZ-08-D04-C01','TZ-08-D04-C01-W01','Kasulu Mjini'),  -- Kasulu Town / Kasulu Urban
    ('TZ-08-D05-C01','TZ-08-D05-C01-W01','Kibondo'),       -- Kibondo (anchor) / Muhambwe
    ('TZ-08-D06-C01','TZ-08-D06-C01-W01','Kigoma'),        -- Kigoma Municipal / Kigoma Urban
    ('TZ-08-D07-C01','TZ-08-D07-C01-W01','Kagunga'),       -- Kigoma Rural / Kigoma North
    ('TZ-08-D08-C01','TZ-08-D08-C01-W01','Uvinza'),        -- Uvinza / Kigoma South
    -- ============ Lindi (TZ-12) ============
    ('TZ-12-D01-C01','TZ-12-D01-C01-W01','Kilwa Masoko'),  -- Kilwa / Kilwa North+South
    ('TZ-12-D02-C01','TZ-12-D02-C01-W01','Mtanda'),        -- Lindi Municipal / Lindi Urban
    ('TZ-12-D03-C01','TZ-12-D03-C01-W01','Liwale'),        -- Liwale (anchor)
    ('TZ-12-D04-C01','TZ-12-D04-C01-W01','Mtama'),         -- Mtama (anchor)
    ('TZ-12-D05-C01','TZ-12-D05-C01-W01','Nachingwea'),    -- Nachingwea (anchor)
    ('TZ-12-D06-C01','TZ-12-D06-C01-W01','Ruangwa'),       -- Ruangwa (anchor)
    -- ============ Manyara (TZ-26) ============
    ('TZ-26-D01-C01','TZ-26-D01-C01-W01','Bonga'),         -- Babati Rural
    ('TZ-26-D02-C01','TZ-26-D02-C01-W01','Babati'),        -- Babati Town / Babati Urban
    ('TZ-26-D03-C01','TZ-26-D03-C01-W01','Katesh'),        -- Hanang
    ('TZ-26-D05-C01','TZ-26-D05-C01-W01','Mbulu'),         -- Mbulu Rural / Mbulu
    ('TZ-26-D04-C01','TZ-26-D04-C01-W01','Kibaya'),        -- Kiteto
    ('TZ-26-D07-C01','TZ-26-D07-C01-W01','Orkesumet'),     -- Simanjiro
    -- ============ Mara (TZ-13) ============
    ('TZ-13-D01-C01','TZ-13-D01-C01-W01','Bunda Mjini'),   -- Bunda Rural / Bunda+Mwibara
    ('TZ-13-D05-C01','TZ-13-D05-C01-W01','Bukima'),        -- Musoma Rural
    ('TZ-13-D04-C01','TZ-13-D04-C01-W01','Mukendo'),       -- Musoma Municipal / Musoma Urban
    ('TZ-13-D06-C01','TZ-13-D06-C01-W01','Shirati'),       -- Rorya
    ('TZ-13-D07-C01','TZ-13-D07-C01-W01','Mugumu'),        -- Serengeti
    ('TZ-13-D08-C01','TZ-13-D08-C01-W01','Sirari'),        -- Tarime Rural
    ('TZ-13-D09-C01','TZ-13-D09-C01-W01','Tarime'),        -- Tarime Town / Tarime Urban
    -- ============ Mbeya (TZ-14) ============
    ('TZ-14-D05-C01','TZ-14-D05-C01-W01','Sisimba'),       -- Mbeya City / Mbeya Urban
    ('TZ-14-D02-C01','TZ-14-D02-C01-W01','Chunya'),        -- Chunya / Lupa
    ('TZ-14-D04-C01','TZ-14-D04-C01-W01','Rujewa'),        -- Mbarali
    ('TZ-14-D03-C01','TZ-14-D03-C01-W01','Kyela'),         -- Kyela (anchor)
    ('TZ-14-D07-C01','TZ-14-D07-C01-W01','Tukuyu'),        -- Rungwe
    ('TZ-14-D01-C01','TZ-14-D01-C01-W01','Kandete'),       -- Busekelo
    -- ============ Morogoro (TZ-16) ============
    ('TZ-16-D01-C01','TZ-16-D01-C01-W01','Gairo'),         -- Gairo (anchor)
    ('TZ-16-D02-C01','TZ-16-D02-C01-W01','Ifakara'),       -- Ifakara Town / Kilombero
    ('TZ-16-D03-C01','TZ-16-D03-C01-W01','Kilosa'),        -- Kilosa (anchor) / Mikumi
    ('TZ-16-D04-C01','TZ-16-D04-C01-W01','Malinyi'),       -- Malinyi (anchor)
    ('TZ-16-D05-C01','TZ-16-D05-C01-W01','Mlimba'),        -- Mlimba (anchor)
    ('TZ-16-D06-C01','TZ-16-D06-C01-W01','Sabasaba'),      -- Morogoro Municipal / Morogoro Urban
    ('TZ-16-D07-C01','TZ-16-D07-C01-W01','Mkuyuni'),       -- Morogoro Rural / Morogoro South
    ('TZ-16-D08-C01','TZ-16-D08-C01-W01','Mvomero'),       -- Mvomero (anchor)
    ('TZ-16-D09-C01','TZ-16-D09-C01-W01','Lupiro'),        -- Ulanga
    -- ============ Mtwara (TZ-17) ============
    ('TZ-17-D02-C01','TZ-17-D02-C01-W01','Masasi Mjini'),  -- Masasi Town / Masasi
    ('TZ-17-D01-C01','TZ-17-D01-C01-W01','Lulindi'),       -- Masasi Rural / Lulindi
    ('TZ-17-D03-C01','TZ-17-D03-C01-W01','Chikongola'),    -- Mtwara Municipal / Mtwara Urban
    ('TZ-17-D04-C01','TZ-17-D04-C01-W01','Mahurunga'),     -- Mtwara Rural
    ('TZ-17-D06-C01','TZ-17-D06-C01-W01','Nanyumbu'),      -- Nanyumbu (anchor)
    ('TZ-17-D08-C01','TZ-17-D08-C01-W01','Newala Mjini'),  -- Newala Town / Newala Urban
    ('TZ-17-D07-C01','TZ-17-D07-C01-W01','Chitekete'),     -- Newala Rural
    ('TZ-17-D09-C01','TZ-17-D09-C01-W01','Tandahimba'),    -- Tandahimba (anchor)
    -- ============ Mwanza (TZ-18) ============
    ('TZ-18-D06-C01','TZ-18-D06-C01-W01','Mirongo'),       -- Mwanza City / Nyamagana
    ('TZ-18-D02-C01','TZ-18-D02-C01-W01','Pasiansi'),      -- Ilemela Municipal / Ilemela
    ('TZ-18-D01-C01','TZ-18-D01-C01-W01','Nyamatongo'),    -- Buchosa
    ('TZ-18-D03-C01','TZ-18-D03-C01-W01','Sumve'),         -- Kwimba / Sumve
    ('TZ-18-D04-C01','TZ-18-D04-C01-W01','Kisesa'),        -- Magu
    ('TZ-18-D05-C01','TZ-18-D05-C01-W01','Misungwi'),      -- Misungwi (anchor)
    ('TZ-18-D07-C01','TZ-18-D07-C01-W01','Sengerema'),     -- Sengerema (anchor)
    ('TZ-18-D08-C01','TZ-18-D08-C01-W01','Nansio'),        -- Ukerewe
    -- ============ Njombe (TZ-29) ============
    ('TZ-29-D01-C01','TZ-29-D01-C01-W01','Ludewa'),        -- Ludewa (anchor)
    ('TZ-29-D02-C01','TZ-29-D02-C01-W01','Makambako'),     -- Makambako Town / Makambako
    ('TZ-29-D03-C01','TZ-29-D03-C01-W01','Iwawa'),         -- Makete
    ('TZ-29-D04-C01','TZ-29-D04-C01-W01','Kifanya'),       -- Njombe Rural / Njombe North
    ('TZ-29-D05-C01','TZ-29-D05-C01-W01','Njombe Mjini'),  -- Njombe Town / Njombe Urban
    ('TZ-29-D06-C01','TZ-29-D06-C01-W01','Wanging''ombe'), -- Wanging'ombe (anchor)
    -- ============ Pwani (TZ-19) ============
    ('TZ-19-D01-C01','TZ-19-D01-C01-W01','Bagamoyo Mjini'),-- Bagamoyo (anchor)
    ('TZ-19-D02-C01','TZ-19-D02-C01-W01','Chalinze'),      -- Chalinze (anchor)
    ('TZ-19-D03-C01','TZ-19-D03-C01-W01','Mlandizi'),      -- Kibaha
    ('TZ-19-D04-C01','TZ-19-D04-C01-W01','Kibaha'),        -- Kibaha Town / Kibaha Urban
    ('TZ-19-D05-C01','TZ-19-D05-C01-W01','Kibiti'),        -- Kibiti (anchor)
    ('TZ-19-D06-C01','TZ-19-D06-C01-W01','Kisarawe'),      -- Kisarawe (anchor)
    ('TZ-19-D07-C01','TZ-19-D07-C01-W01','Kilindoni'),     -- Mafia
    ('TZ-19-D08-C01','TZ-19-D08-C01-W01','Mkuranga'),      -- Mkuranga (anchor)
    ('TZ-19-D09-C01','TZ-19-D09-C01-W01','Utete'),         -- Rufiji
    -- ============ Rukwa (TZ-20) ============
    ('TZ-20-D01-C01','TZ-20-D01-C01-W01','Matai'),         -- Kalambo
    ('TZ-20-D02-C01','TZ-20-D02-C01-W01','Namanyere'),     -- Nkasi / Nkasi North+South
    ('TZ-20-D03-C01','TZ-20-D03-C01-W01','Katandala'),     -- Sumbawanga Municipal / Sumbawanga Urban
    ('TZ-20-D04-C01','TZ-20-D04-C01-W01','Laela'),         -- Sumbawanga Rural / Kwela
    -- ============ Ruvuma (TZ-21) ============
    ('TZ-21-D02-C01','TZ-21-D02-C01-W01','Mbinga Mjini'),  -- Mbinga Rural / Mbinga East+West
    ('TZ-21-D04-C01','TZ-21-D04-C01-W01','Namtumbo'),      -- Namtumbo (anchor)
    ('TZ-21-D07-C01','TZ-21-D07-C01-W01','Peramiho'),      -- Songea Rural / Peramiho
    ('TZ-21-D06-C01','TZ-21-D06-C01-W01','Ruvuma'),        -- Songea Municipal / Songea Urban
    ('TZ-21-D05-C01','TZ-21-D05-C01-W01','Mbamba Bay'),    -- Nyasa
    ('TZ-21-D08-C01','TZ-21-D08-C01-W01','Tunduru Mjini'), -- Tunduru / Tunduru North+South
    ('TZ-21-D01-C01','TZ-21-D01-C01-W01','Madaba'),        -- Madaba (anchor)
    -- ============ Shinyanga (TZ-22) ============
    ('TZ-22-D01-C01','TZ-22-D01-C01-W01','Kahama Mjini'),  -- Kahama Municipal / Kahama Urban
    ('TZ-22-D03-C01','TZ-22-D03-C01-W01','Bulyanhulu'),    -- Msalala
    ('TZ-22-D06-C01','TZ-22-D06-C01-W01','Ushetu'),        -- Ushetu (anchor)
    ('TZ-22-D02-C01','TZ-22-D02-C01-W01','Kishapu'),       -- Kishapu (anchor)
    ('TZ-22-D04-C01','TZ-22-D04-C01-W01','Ndala'),         -- Shinyanga Municipal / Shinyanga Urban
    ('TZ-22-D05-C01','TZ-22-D05-C01-W01','Solwa'),         -- Shinyanga Rural / Solwa
    -- ============ Simiyu (TZ-30) ============
    ('TZ-30-D01-C01','TZ-30-D01-C01-W01','Dutwa'),         -- Bariadi Rural / Bariadi
    ('TZ-30-D02-C01','TZ-30-D02-C01-W01','Bariadi'),       -- Bariadi Town / Bariadi Urban
    ('TZ-30-D03-C01','TZ-30-D03-C01-W01','Nyashimo'),      -- Busega
    ('TZ-30-D04-C01','TZ-30-D04-C01-W01','Lagangabilili'), -- Itilima
    ('TZ-30-D05-C01','TZ-30-D05-C01-W01','Maswa'),         -- Maswa / Maswa East+West
    ('TZ-30-D06-C01','TZ-30-D06-C01-W01','Mwanhuzi'),      -- Meatu
    -- ============ Singida (TZ-23) ============
    ('TZ-23-D02-C01','TZ-23-D02-C01-W01','Kiomboi'),       -- Iramba / Iramba East+West
    ('TZ-23-D05-C01','TZ-23-D05-C01-W01','Nduguti'),       -- Mkalama
    ('TZ-23-D04-C01','TZ-23-D04-C01-W01','Manyoni Mjini'), -- Manyoni / Manyoni East+West
    ('TZ-23-D03-C01','TZ-23-D03-C01-W01','Itigi'),         -- Itigi (anchor)
    ('TZ-23-D07-C01','TZ-23-D07-C01-W01','Mungaa'),        -- Singida Rural / Singida East
    ('TZ-23-D01-C01','TZ-23-D01-C01-W01','Ikungi'),        -- Ikungi / Singida North+West
    ('TZ-23-D06-C01','TZ-23-D06-C01-W01','Mandewa'),       -- Singida Municipal / Singida Urban
    -- ============ Tabora (TZ-24) ============
    ('TZ-24-D01-C01','TZ-24-D01-C01-W01','Igunga'),        -- Igunga (anchor)
    ('TZ-24-D03-C01','TZ-24-D03-C01-W01','Bukene'),        -- Nzega Rural / Bukene
    ('TZ-24-D04-C01','TZ-24-D04-C01-W01','Nzega Mjini'),   -- Nzega Town / Nzega Urban
    ('TZ-24-D08-C01','TZ-24-D08-C01-W01','Igalula'),       -- Uyui / Igalula
    ('TZ-24-D06-C01','TZ-24-D06-C01-W01','Cheyo'),         -- Tabora Municipal / Tabora Urban
    ('TZ-24-D05-C01','TZ-24-D05-C01-W01','Sikonge'),       -- Sikonge (anchor)
    ('TZ-24-D02-C01','TZ-24-D02-C01-W01','Kaliua'),        -- Kaliua (anchor)
    ('TZ-24-D07-C01','TZ-24-D07-C01-W01','Urambo'),        -- Urambo (anchor)
    -- ============ Songwe (TZ-31) ============
    ('TZ-31-D01-C01','TZ-31-D01-C01-W01','Itumba'),        -- Ileje
    ('TZ-31-D02-C01','TZ-31-D02-C01-W01','Vwawa'),         -- Mbozi / Vwawa (anchor)  [Mbozi-jimbo wards GAP]
    ('TZ-31-D03-C01','TZ-31-D03-C01-W01','Chitete'),       -- Momba
    ('TZ-31-D04-C01','TZ-31-D04-C01-W01','Mkwajuni'),      -- Songwe
    ('TZ-31-D05-C01','TZ-31-D05-C01-W01','Tunduma'),       -- Tunduma Town (anchor)
    -- ============ Tanga (TZ-25) ============
    ('TZ-25-D01-C01','TZ-25-D01-C01-W01','Bumbuli'),       -- Bumbuli (anchor)
    ('TZ-25-D02-C01','TZ-25-D02-C01-W01','Kwediboma'),     -- Handeni Rural / Handeni
    ('TZ-25-D03-C01','TZ-25-D03-C01-W01','Handeni Mjini'), -- Handeni Town / Handeni Urban
    ('TZ-25-D04-C01','TZ-25-D04-C01-W01','Songe'),         -- Kilindi
    ('TZ-25-D05-C01','TZ-25-D05-C01-W01','Mombo'),         -- Korogwe Rural
    ('TZ-25-D06-C01','TZ-25-D06-C01-W01','Korogwe'),       -- Korogwe Town / Korogwe Urban
    ('TZ-25-D07-C01','TZ-25-D07-C01-W01','Lushoto'),       -- Lushoto (anchor) / Mlalo
    ('TZ-25-D08-C01','TZ-25-D08-C01-W01','Mkinga'),        -- Mkinga (anchor)
    ('TZ-25-D09-C01','TZ-25-D09-C01-W01','Muheza'),        -- Muheza (anchor)
    ('TZ-25-D10-C01','TZ-25-D10-C01-W01','Pangani Mashariki'), -- Pangani
    ('TZ-25-D11-C01','TZ-25-D11-C01-W01','Makorora')       -- Tanga City / Tanga Urban
) AS v(council_code, code, name)
ON CONFLICT (public_id) DO NOTHING;
