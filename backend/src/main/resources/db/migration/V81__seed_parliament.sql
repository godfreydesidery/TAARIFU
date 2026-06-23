-- =============================================================================
-- V81__seed_parliament.sql  —  Parliament terms + parliament-role catalogue.
--
-- Seeds the Union Parliament (Bunge) terms and the assignable-office catalogue. The DB
-- owns "at most one CURRENT term per legislature" (ux_parliament_current_per_legislature);
-- this seed sets is_current = TRUE for exactly ONE Union term.
--
-- TERMS (VERIFIED, Wikipedia / The Chanzo / The Citizen):
--   * 12th Parliament: inaugurated 2020-11-13, dissolved 2025-08-03 -> is_current FALSE.
--   * 13th Parliament: inaugurated 2025-11-12 -> CURRENT as of this seed (today 2026-06).
--   end_date is exclusive; the 12th uses its dissolution date, the 13th is open (NULL).
--   Zanzibar House of Representatives terms are Phase 2 (D17) — not seeded.
--
-- ROLES: a small, stable catalogue of Bunge offices; a representative optionally FKs one.
-- created_by = SYSTEM sentinel; version 0. Idempotent. Forward-only.
-- =============================================================================

-- ---- Parliament terms (Union). ----
INSERT INTO parliament
    (public_id, version, created_at, created_by, term_number, name, legislature, start_date, end_date, is_current)
SELECT seed_uuid('PARLIAMENT', v.code), 0, now(), '00000000-0000-0000-0000-000000000000'::uuid,
       v.term_number, v.name, 'UNION_PARLIAMENT', v.start_date, v.end_date, v.is_current
FROM (VALUES
    ('UNION-12', 12, '12th Parliament of the United Republic of Tanzania', DATE '2020-11-13', DATE '2025-08-03', FALSE),
    ('UNION-13', 13, '13th Parliament of the United Republic of Tanzania', DATE '2025-11-12', NULL,             TRUE)
) AS v(code, term_number, name, start_date, end_date, is_current)
ON CONFLICT (public_id) DO NOTHING;

-- ---- Parliament-role catalogue (assignable offices). ----
INSERT INTO parliament_role
    (public_id, version, created_at, created_by, code, name, description)
SELECT seed_uuid('PARLROLE', v.code), 0, now(), '00000000-0000-0000-0000-000000000000'::uuid,
       v.code, v.name, v.description
FROM (VALUES
    ('SPEAKER',        'Speaker of the National Assembly (Spika)',        'Presiding officer of the Bunge.'),
    ('DEPUTY_SPEAKER', 'Deputy Speaker (Naibu Spika)',                    'Deputises for the Speaker.'),
    ('LEADER_GOV_BUSINESS','Leader of Government Business (Kiongozi wa Shughuli za Serikali Bungeni)','Manages government business in the House.'),
    ('LEADER_OPPOSITION','Leader of the Official Opposition (Kiongozi wa Kambi ya Upinzani)','Heads the official opposition in the House.'),
    ('MINISTER',       'Minister (Waziri)',                               'Cabinet minister who is also a Member of Parliament.'),
    ('DEPUTY_MINISTER','Deputy Minister (Naibu Waziri)',                  'Deputy minister who is also a Member of Parliament.'),
    ('COMMITTEE_CHAIR','Committee Chairperson (Mwenyekiti wa Kamati)',    'Chairs a standing or select parliamentary committee.'),
    ('WHIP',           'Whip (Mnadhimu)',                                 'Party whip in the House.')
) AS v(code, name, description)
ON CONFLICT (public_id) DO NOTHING;
