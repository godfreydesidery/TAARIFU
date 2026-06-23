-- =============================================================================
-- V35__responders_seed.sql  —  Minimal seed for the public provider directory.
--
-- Responsibility: seed a small set of ACTIVE + verified responder organisations and
-- one NATIONWIDE responder capability each, so the public "who handles what" directory
-- (GET /responders, GET /organisations) returns browsable content immediately after a
-- fresh migration, and integration tests have stable, self-contained fixtures.
--
-- DESIGN NOTES (the "why", per CLAUDE.md §8):
--   * Self-contained ON PURPOSE: these rows reference NO geography area ids or reporting
--     category ids (coverage = NATIONWIDE, no responder_category rows). The reporting and
--     geography seed data are owned by other modules/increments; coupling this seed to ids
--     we do not own here would be fragile and would break the module's independent build
--     (ARCHITECTURE.md §3.2). Category/area links are added when those modules are wired.
--   * Real Tanzanian parastatals/utilities are used (TANESCO electricity, DAWASA water) to
--     match the D20 "utilities/parastatals first" onboarding order (PRD §24.4, §24.6).
--   * Fixed public_id UUIDs so tests and downstream wiring can reference them deterministically.
--   * version = 0 and status/verified set so the rows are immediately publicly listable
--     (ACTIVE + verified) per the §24.4 visibility rule.
--
-- Idempotent inserts (ON CONFLICT on the unique public_id) so re-running in a dev DB is safe.
-- Forward-only; never edit once applied — add a new migration.
-- =============================================================================

-- Organisations (parastatal utilities — onboarded first, D20).
INSERT INTO responder_organisation
    (public_id, version, type, name, status, verified, contact_phone, contact_email, website_url)
VALUES
    ('a0000000-0000-4000-8000-000000000001', 0, 'PARASTATAL', 'TANESCO',
     'ACTIVE', TRUE, '0800110041', 'info@tanesco.co.tz', 'https://www.tanesco.co.tz'),
    ('a0000000-0000-4000-8000-000000000002', 0, 'PARASTATAL', 'DAWASA',
     'ACTIVE', TRUE, '0800110052', 'info@dawasa.go.tz', 'https://www.dawasa.go.tz')
ON CONFLICT (public_id) DO NOTHING;

-- One NATIONWIDE responder capability per organisation (no category/area links yet).
INSERT INTO responder
    (public_id, version, organisation_id, name, responder_type, status, coverage_type, sla_policy)
SELECT 'b0000000-0000-4000-8000-000000000001', 0, o.id,
       'TANESCO — Electricity Response', 'UTILITY', 'ACTIVE', 'NATIONWIDE',
       'Acknowledge within 24h; restore supply within contractual SLA (PRD §25.2).'
FROM responder_organisation o
WHERE o.public_id = 'a0000000-0000-4000-8000-000000000001'
ON CONFLICT (public_id) DO NOTHING;

INSERT INTO responder
    (public_id, version, organisation_id, name, responder_type, status, coverage_type, sla_policy)
SELECT 'b0000000-0000-4000-8000-000000000002', 0, o.id,
       'DAWASA — Water & Sanitation Response', 'UTILITY', 'ACTIVE', 'NATIONWIDE',
       'Acknowledge within 24h; resolve within contractual SLA (PRD §25.2).'
FROM responder_organisation o
WHERE o.public_id = 'a0000000-0000-4000-8000-000000000002'
ON CONFLICT (public_id) DO NOTHING;
