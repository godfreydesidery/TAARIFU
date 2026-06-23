-- =============================================================================
-- V82__seed_issue_categories.sql  —  Top-level issue taxonomy (Appendix D.2).
--
-- Seeds the 14 top-level reportable-issue categories with their DEFAULT routing token,
-- SLA (TTFR/TTR in MINUTES), sensitivity, forced-private, and default visibility, exactly
-- per PRD Appendix D.2/D.4. These are admin-configurable defaults (US-1.2); the engine
-- (UC-D04) may re-route, and per-area overrides may adjust SLA.
--
-- SLA conversion: hours*60, days*1440. Examples used below:
--   24h=1440  48h=2880  72h=4320  6h=360  2h=120
--   7d=10080  14d=20160  21d=30240  30d=43200  45d=64800  60d=86400
-- Corruption/GBV/Other carry NO public TTR promise; a sentinel TTR is used and noted
-- (the engine treats sensitive cases per investigation/protocol, not a citizen SLA).
--
-- ROUTING tokens map to the V23 CHECK domain
-- (WARD, MTAA_VILLAGE, COUNCIL, DISTRICT, REGION, SECTOR_UTILITY, OVERSIGHT).
-- force_private => GBV & Corruption (D.4 "never PUBLIC"). sensitive(always) => same two.
--
-- created_by = SYSTEM sentinel; version 0; active TRUE. Idempotent. Forward-only.
-- =============================================================================

INSERT INTO issue_category
    (public_id, version, created_at, created_by, code, name, parent_id,
     default_routing_level, default_sla_ttfr_minutes, default_sla_ttr_minutes,
     sensitive, force_private, default_visibility, icon, active)
SELECT seed_uuid('ISSUECAT', v.code), 0, now(), '00000000-0000-0000-0000-000000000000'::uuid,
       v.code, v.name, NULL,
       v.routing, v.ttfr, v.ttr, v.sensitive, v.force_private, v.visibility, v.icon, TRUE
FROM (VALUES
    -- code                         name (Swahili-first)                              routing            ttfr  ttr    sens   forcep  vis        icon
    ('WATER_SANITATION',     'Maji na Usafi wa Mazingira (Water & Sanitation)',     'SECTOR_UTILITY', 2880,  20160, FALSE, FALSE, 'PUBLIC',  'water'),
    ('ROADS_TRANSPORT',      'Barabara na Usafiri (Roads & Transport)',             'SECTOR_UTILITY', 4320,  43200, FALSE, FALSE, 'PUBLIC',  'road'),
    ('ELECTRICITY_ENERGY',   'Umeme na Nishati (Electricity & Energy)',             'SECTOR_UTILITY', 1440,  20160, FALSE, FALSE, 'PUBLIC',  'power'),
    ('HEALTH',               'Afya (Health)',                                       'COUNCIL',        2880,  30240, FALSE, FALSE, 'PUBLIC',  'health'),
    ('EDUCATION',            'Elimu (Education)',                                   'COUNCIL',        4320,  43200, FALSE, FALSE, 'PUBLIC',  'education'),
    ('SECURITY_SAFETY',      'Usalama (Security & Safety)',                         'WARD',           360,   10080, FALSE, FALSE, 'PRIVATE', 'security'),
    ('GBV_CHILD_PROTECTION', 'Ukatili wa Kijinsia na Ulinzi wa Mtoto (GBV & Child Protection)','OVERSIGHT',120, 10080, TRUE, TRUE, 'PRIVATE','shield'),
    ('CORRUPTION_GOVERNANCE','Rushwa na Utawala Bora (Corruption & Governance)',    'OVERSIGHT',      1440,  43200, TRUE,  TRUE,  'PRIVATE', 'gavel'),
    ('LAND_HOUSING',         'Ardhi na Makazi (Land & Housing)',                    'COUNCIL',        7200,  86400, FALSE, FALSE, 'PRIVATE', 'land'),
    ('ENVIRONMENT',          'Mazingira (Environment)',                             'SECTOR_UTILITY', 4320,  43200, FALSE, FALSE, 'PUBLIC',  'environment'),
    ('AGRICULTURE_LIVESTOCK','Kilimo na Mifugo (Agriculture & Livestock)',          'WARD',           7200,  43200, FALSE, FALSE, 'PUBLIC',  'agriculture'),
    ('SOCIAL_WELFARE',       'Ustawi wa Jamii (Social Welfare)',                    'COUNCIL',        7200,  43200, FALSE, FALSE, 'PRIVATE', 'welfare'),
    ('PUBLIC_SERVICES',      'Huduma za Umma na Urasimu (Public Services & Bureaucracy)','WARD',      4320,  30240, FALSE, FALSE, 'PUBLIC',  'services'),
    ('COMMUNITY_INFRA',      'Jamii na Miundombinu (Community & Infrastructure)',   'WARD',           7200,  64800, FALSE, FALSE, 'PUBLIC',  'community'),
    ('OTHER',                'Nyingine / Haijaainishwa (Other / Uncategorised)',    'WARD',           4320,  4320,  FALSE, FALSE, 'PRIVATE', 'other')
) AS v(code, name, routing, ttfr, ttr, sensitive, force_private, visibility, icon)
ON CONFLICT (public_id) DO NOTHING;
