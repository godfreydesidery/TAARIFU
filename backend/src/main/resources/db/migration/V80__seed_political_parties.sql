-- =============================================================================
-- V80__seed_political_parties.sql  —  Registered political parties (Vyama vya Siasa).
--
-- Seeds parties with FULL registration under the Registrar of Political Parties
-- (Wikipedia: List of political parties in Tanzania). status = ACTIVE (PartyStatus);
-- a future deregistration flips status to INACTIVE without deleting the row, preserving
-- a FORMER representative's historical affiliation.
--
-- NEUTRALITY (PRD election-period rule): purely factual reference data — name,
-- abbreviation, founding year. No field encodes preference, endorsement, or standing.
--
-- founded_year is seeded ONLY where confidently known; ideology/contacts/logo_ref are
-- left NULL (UNVERIFIED — to be enriched from the Registrar's official register, not
-- guessed). CODE = the party's stable acronym; the idempotent match key.
--
-- created_by = SYSTEM sentinel; version 0. Idempotent. Forward-only.
-- =============================================================================

INSERT INTO political_party
    (public_id, version, created_at, created_by, code, name, abbreviation, founded_year, status)
SELECT seed_uuid('PARTY', v.code), 0, now(), '00000000-0000-0000-0000-000000000000'::uuid,
       v.code, v.name, v.abbreviation, v.founded_year, 'ACTIVE'
FROM (VALUES
    ('CCM',          'Chama Cha Mapinduzi',                                  'CCM',          1977),
    ('CHADEMA',      'Chama cha Demokrasia na Maendeleo',                    'CHADEMA',      1992),
    ('CUF',          'Civic United Front (Chama Cha Wananchi)',              'CUF',          1992),
    ('ACT-WAZALENDO','ACT-Wazalendo (Alliance for Change and Transparency)', 'ACT',          2014),
    ('NCCR-MAGEUZI', 'National Convention for Construction and Reform–Mageuzi','NCCR-Mageuzi',1992),
    ('NLD',          'National League for Democracy',                        'NLD',          NULL),
    ('UDP',          'United Democratic Party',                              'UDP',          1994),
    ('TLP',          'Tanzania Labour Party',                                'TLP',          1993),
    ('UPDP',         'United People''s Democratic Party',                    'UPDP',         NULL),
    ('ADC',          'Alliance for Democratic Change',                       'ADC',          NULL),
    ('AFP',          'Alliance for Tanzania Farmers Party',                  'AFP',          NULL),
    ('CCK',          'Chama cha Kijamii',                                    'CCK',          NULL),
    ('CHAUMMA',      'Chama cha Ukombozi wa Umma',                           'CHAUMMA',      NULL),
    ('CHAUSTA',      'Chama cha Haki na Ustawi',                             'CHAUSTA',      NULL),
    ('DP',           'Democratic Party',                                     'DP',           NULL),
    ('NRA',          'National Reconstruction Alliance',                     'NRA',          NULL),
    ('SAU',          'Sauti ya Umma',                                        'SAU',          NULL),
    ('TADEA',        'Tanzania Democratic Alliance Party',                   'TADEA',        NULL),
    ('UMD',          'Union for Multiparty Democracy',                       'UMD',          NULL),
    ('PPT-MAENDELEO','Progressive Party of Tanzania–Maendeleo',              'PPT-Maendeleo',NULL)
) AS v(code, name, abbreviation, founded_year)
ON CONFLICT (public_id) DO NOTHING;
