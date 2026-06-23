-- =============================================================================
-- V72__seed_geography_districts.sql  —  Districts (Wilaya), level DISTRICT.
--
-- Seeds the second admin level for ALL 26 mainland regions. Per the F1 parent-type
-- matrix a DISTRICT's only legal parent is a REGION; parent_id is resolved from
-- seed_uuid('REGION', <region_code>) so the FK is correct without a hand-kept id.
--
-- CODE format: "<region_code>-D<nn>" (e.g. TZ-09-D05 = Rombo in Kilimanjaro). The
-- numeric tail is a stable local ordinal; the whole string is the unique idempotent
-- match key (EI-14). VARCHAR(32) accommodates it.
--
-- SCOPE / F2 caveat: the source dataset (NBS / citypopulation.de) enumerates LGAs
-- (councils) under the modern reorganisation; here we model them at the DISTRICT level
-- (the standard administrative Wilaya tier). For the two DEEP-DETAIL regions —
-- Kilimanjaro and Dar es Salaam — the net-new COUNCIL (Halmashauri) tier is layered in
-- V73/V74 BENEATH these districts, and wards hang off the councils. For the other 24
-- regions only Region+District are seeded (councils/wards await per-region enrichment,
-- R4/R5) — flagged as the known coverage boundary of this MVP seed.
--
-- created_by = SYSTEM sentinel; status ACTIVE; version 0. Idempotent. Forward-only.
-- =============================================================================

INSERT INTO location (public_id, version, created_at, created_by, type, parent_id, code, name, status)
SELECT seed_uuid('DISTRICT', v.code), 0, now(), '00000000-0000-0000-0000-000000000000'::uuid,
       'DISTRICT',
       (SELECT id FROM location r WHERE r.code = v.region_code AND r.type = 'REGION'),
       v.code, v.name, 'ACTIVE'
FROM (VALUES
    -- Arusha (TZ-01)
    ('TZ-01','TZ-01-D01','Arusha City'),
    ('TZ-01','TZ-01-D02','Arusha Rural'),
    ('TZ-01','TZ-01-D03','Karatu'),
    ('TZ-01','TZ-01-D04','Longido'),
    ('TZ-01','TZ-01-D05','Meru'),
    ('TZ-01','TZ-01-D06','Monduli'),
    ('TZ-01','TZ-01-D07','Ngorongoro'),
    -- Dar es Salaam (TZ-02)
    ('TZ-02','TZ-02-D01','Ilala'),
    ('TZ-02','TZ-02-D02','Kigamboni'),
    ('TZ-02','TZ-02-D03','Kinondoni'),
    ('TZ-02','TZ-02-D04','Temeke'),
    ('TZ-02','TZ-02-D05','Ubungo'),
    -- Dodoma (TZ-03)
    ('TZ-03','TZ-03-D01','Bahi'),
    ('TZ-03','TZ-03-D02','Chamwino'),
    ('TZ-03','TZ-03-D03','Chemba'),
    ('TZ-03','TZ-03-D04','Dodoma City'),
    ('TZ-03','TZ-03-D05','Kondoa Rural'),
    ('TZ-03','TZ-03-D06','Kondoa Town'),
    ('TZ-03','TZ-03-D07','Kongwa'),
    ('TZ-03','TZ-03-D08','Mpwapwa'),
    -- Geita (TZ-27)
    ('TZ-27','TZ-27-D01','Bukombe'),
    ('TZ-27','TZ-27-D02','Chato'),
    ('TZ-27','TZ-27-D03','Geita Rural'),
    ('TZ-27','TZ-27-D04','Geita Town'),
    ('TZ-27','TZ-27-D05','Mbogwe'),
    ('TZ-27','TZ-27-D06','Nyang''hwale'),
    -- Iringa (TZ-04)
    ('TZ-04','TZ-04-D01','Iringa Municipal'),
    ('TZ-04','TZ-04-D02','Iringa Rural'),
    ('TZ-04','TZ-04-D03','Kilolo'),
    ('TZ-04','TZ-04-D04','Mafinga Town'),
    ('TZ-04','TZ-04-D05','Mufindi'),
    -- Kagera (TZ-05)
    ('TZ-05','TZ-05-D01','Biharamulo'),
    ('TZ-05','TZ-05-D02','Bukoba Municipal'),
    ('TZ-05','TZ-05-D03','Bukoba Rural'),
    ('TZ-05','TZ-05-D04','Karagwe'),
    ('TZ-05','TZ-05-D05','Kyerwa'),
    ('TZ-05','TZ-05-D06','Missenyi'),
    ('TZ-05','TZ-05-D07','Muleba'),
    ('TZ-05','TZ-05-D08','Ngara'),
    -- Katavi (TZ-28)
    ('TZ-28','TZ-28-D01','Mlele'),
    ('TZ-28','TZ-28-D02','Mpanda Municipal'),
    ('TZ-28','TZ-28-D03','Mpimbwe'),
    ('TZ-28','TZ-28-D04','Nsimbo'),
    ('TZ-28','TZ-28-D05','Tanganyika'),
    -- Kigoma (TZ-08)
    ('TZ-08','TZ-08-D01','Buhigwe'),
    ('TZ-08','TZ-08-D02','Kakonko'),
    ('TZ-08','TZ-08-D03','Kasulu Rural'),
    ('TZ-08','TZ-08-D04','Kasulu Town'),
    ('TZ-08','TZ-08-D05','Kibondo'),
    ('TZ-08','TZ-08-D06','Kigoma Municipal'),
    ('TZ-08','TZ-08-D07','Kigoma Rural'),
    ('TZ-08','TZ-08-D08','Uvinza'),
    -- Kilimanjaro (TZ-09)
    ('TZ-09','TZ-09-D01','Hai'),
    ('TZ-09','TZ-09-D02','Moshi Municipal'),
    ('TZ-09','TZ-09-D03','Moshi Rural'),
    ('TZ-09','TZ-09-D04','Mwanga'),
    ('TZ-09','TZ-09-D05','Rombo'),
    ('TZ-09','TZ-09-D06','Same'),
    ('TZ-09','TZ-09-D07','Siha'),
    -- Lindi (TZ-12)
    ('TZ-12','TZ-12-D01','Kilwa'),
    ('TZ-12','TZ-12-D02','Lindi Municipal'),
    ('TZ-12','TZ-12-D03','Liwale'),
    ('TZ-12','TZ-12-D04','Mtama'),
    ('TZ-12','TZ-12-D05','Nachingwea'),
    ('TZ-12','TZ-12-D06','Ruangwa'),
    -- Manyara (TZ-26)
    ('TZ-26','TZ-26-D01','Babati Rural'),
    ('TZ-26','TZ-26-D02','Babati Town'),
    ('TZ-26','TZ-26-D03','Hanang'),
    ('TZ-26','TZ-26-D04','Kiteto'),
    ('TZ-26','TZ-26-D05','Mbulu Rural'),
    ('TZ-26','TZ-26-D06','Mbulu Town'),
    ('TZ-26','TZ-26-D07','Simanjiro'),
    -- Mara (TZ-13)
    ('TZ-13','TZ-13-D01','Bunda Rural'),
    ('TZ-13','TZ-13-D02','Bunda Town'),
    ('TZ-13','TZ-13-D03','Butiama'),
    ('TZ-13','TZ-13-D04','Musoma Municipal'),
    ('TZ-13','TZ-13-D05','Musoma Rural'),
    ('TZ-13','TZ-13-D06','Rorya'),
    ('TZ-13','TZ-13-D07','Serengeti'),
    ('TZ-13','TZ-13-D08','Tarime Rural'),
    ('TZ-13','TZ-13-D09','Tarime Town'),
    -- Mbeya (TZ-14)
    ('TZ-14','TZ-14-D01','Busekelo'),
    ('TZ-14','TZ-14-D02','Chunya'),
    ('TZ-14','TZ-14-D03','Kyela'),
    ('TZ-14','TZ-14-D04','Mbarali'),
    ('TZ-14','TZ-14-D05','Mbeya City'),
    ('TZ-14','TZ-14-D06','Mbeya Rural'),
    ('TZ-14','TZ-14-D07','Rungwe'),
    -- Morogoro (TZ-16)
    ('TZ-16','TZ-16-D01','Gairo'),
    ('TZ-16','TZ-16-D02','Ifakara Town'),
    ('TZ-16','TZ-16-D03','Kilosa'),
    ('TZ-16','TZ-16-D04','Malinyi'),
    ('TZ-16','TZ-16-D05','Mlimba'),
    ('TZ-16','TZ-16-D06','Morogoro Municipal'),
    ('TZ-16','TZ-16-D07','Morogoro Rural'),
    ('TZ-16','TZ-16-D08','Mvomero'),
    ('TZ-16','TZ-16-D09','Ulanga'),
    -- Mtwara (TZ-17)
    ('TZ-17','TZ-17-D01','Masasi Rural'),
    ('TZ-17','TZ-17-D02','Masasi Town'),
    ('TZ-17','TZ-17-D03','Mtwara Municipal'),
    ('TZ-17','TZ-17-D04','Mtwara Rural'),
    ('TZ-17','TZ-17-D05','Nanyamba Town'),
    ('TZ-17','TZ-17-D06','Nanyumbu'),
    ('TZ-17','TZ-17-D07','Newala Rural'),
    ('TZ-17','TZ-17-D08','Newala Town'),
    ('TZ-17','TZ-17-D09','Tandahimba'),
    -- Mwanza (TZ-18)
    ('TZ-18','TZ-18-D01','Buchosa'),
    ('TZ-18','TZ-18-D02','Ilemela Municipal'),
    ('TZ-18','TZ-18-D03','Kwimba'),
    ('TZ-18','TZ-18-D04','Magu'),
    ('TZ-18','TZ-18-D05','Misungwi'),
    ('TZ-18','TZ-18-D06','Mwanza City'),
    ('TZ-18','TZ-18-D07','Sengerema'),
    ('TZ-18','TZ-18-D08','Ukerewe'),
    -- Njombe (TZ-29)
    ('TZ-29','TZ-29-D01','Ludewa'),
    ('TZ-29','TZ-29-D02','Makambako Town'),
    ('TZ-29','TZ-29-D03','Makete'),
    ('TZ-29','TZ-29-D04','Njombe Rural'),
    ('TZ-29','TZ-29-D05','Njombe Town'),
    ('TZ-29','TZ-29-D06','Wanging''ombe'),
    -- Pwani (TZ-19)
    ('TZ-19','TZ-19-D01','Bagamoyo'),
    ('TZ-19','TZ-19-D02','Chalinze'),
    ('TZ-19','TZ-19-D03','Kibaha'),
    ('TZ-19','TZ-19-D04','Kibaha Town'),
    ('TZ-19','TZ-19-D05','Kibiti'),
    ('TZ-19','TZ-19-D06','Kisarawe'),
    ('TZ-19','TZ-19-D07','Mafia'),
    ('TZ-19','TZ-19-D08','Mkuranga'),
    ('TZ-19','TZ-19-D09','Rufiji'),
    -- Rukwa (TZ-20)
    ('TZ-20','TZ-20-D01','Kalambo'),
    ('TZ-20','TZ-20-D02','Nkasi'),
    ('TZ-20','TZ-20-D03','Sumbawanga Municipal'),
    ('TZ-20','TZ-20-D04','Sumbawanga Rural'),
    -- Ruvuma (TZ-21)
    ('TZ-21','TZ-21-D01','Madaba'),
    ('TZ-21','TZ-21-D02','Mbinga Rural'),
    ('TZ-21','TZ-21-D03','Mbinga Town'),
    ('TZ-21','TZ-21-D04','Namtumbo'),
    ('TZ-21','TZ-21-D05','Nyasa'),
    ('TZ-21','TZ-21-D06','Songea Municipal'),
    ('TZ-21','TZ-21-D07','Songea Rural'),
    ('TZ-21','TZ-21-D08','Tunduru'),
    -- Shinyanga (TZ-22)
    ('TZ-22','TZ-22-D01','Kahama Municipal'),
    ('TZ-22','TZ-22-D02','Kishapu'),
    ('TZ-22','TZ-22-D03','Msalala'),
    ('TZ-22','TZ-22-D04','Shinyanga Municipal'),
    ('TZ-22','TZ-22-D05','Shinyanga Rural'),
    ('TZ-22','TZ-22-D06','Ushetu'),
    -- Simiyu (TZ-30)
    ('TZ-30','TZ-30-D01','Bariadi Rural'),
    ('TZ-30','TZ-30-D02','Bariadi Town'),
    ('TZ-30','TZ-30-D03','Busega'),
    ('TZ-30','TZ-30-D04','Itilima'),
    ('TZ-30','TZ-30-D05','Maswa'),
    ('TZ-30','TZ-30-D06','Meatu'),
    -- Singida (TZ-23)
    ('TZ-23','TZ-23-D01','Ikungi'),
    ('TZ-23','TZ-23-D02','Iramba'),
    ('TZ-23','TZ-23-D03','Itigi'),
    ('TZ-23','TZ-23-D04','Manyoni'),
    ('TZ-23','TZ-23-D05','Mkalama'),
    ('TZ-23','TZ-23-D06','Singida Municipal'),
    ('TZ-23','TZ-23-D07','Singida Rural'),
    -- Songwe (TZ-31)
    ('TZ-31','TZ-31-D01','Ileje'),
    ('TZ-31','TZ-31-D02','Mbozi'),
    ('TZ-31','TZ-31-D03','Momba'),
    ('TZ-31','TZ-31-D04','Songwe'),
    ('TZ-31','TZ-31-D05','Tunduma Town'),
    -- Tabora (TZ-24)
    ('TZ-24','TZ-24-D01','Igunga'),
    ('TZ-24','TZ-24-D02','Kaliua'),
    ('TZ-24','TZ-24-D03','Nzega Rural'),
    ('TZ-24','TZ-24-D04','Nzega Town'),
    ('TZ-24','TZ-24-D05','Sikonge'),
    ('TZ-24','TZ-24-D06','Tabora Municipal'),
    ('TZ-24','TZ-24-D07','Urambo'),
    ('TZ-24','TZ-24-D08','Uyui'),
    -- Tanga (TZ-25)
    ('TZ-25','TZ-25-D01','Bumbuli'),
    ('TZ-25','TZ-25-D02','Handeni Rural'),
    ('TZ-25','TZ-25-D03','Handeni Town'),
    ('TZ-25','TZ-25-D04','Kilindi'),
    ('TZ-25','TZ-25-D05','Korogwe Rural'),
    ('TZ-25','TZ-25-D06','Korogwe Town'),
    ('TZ-25','TZ-25-D07','Lushoto'),
    ('TZ-25','TZ-25-D08','Mkinga'),
    ('TZ-25','TZ-25-D09','Muheza'),
    ('TZ-25','TZ-25-D10','Pangani'),
    ('TZ-25','TZ-25-D11','Tanga City')
) AS v(region_code, code, name)
ON CONFLICT (public_id) DO NOTHING;
