-- =============================================================================
-- V83__seed_issue_subcategories.sql  —  Sub-categories with distinct handling (Appendix D).
--
-- Seeds the sub-categories that DIFFER from their parent's defaults — the tighter-SLA
-- safety sub-cases and the partial-sensitive sub-cases called out in Appendix D.2/D.4.
-- parent_id resolves to the V82 top-level category by code (F1-style: a sub-category's
-- only parent is its top-level category). Sub-cases that simply inherit the parent's
-- defaults are NOT enumerated here (avoid noise; the picker shows the parent).
--
-- Rationale per row is the Appendix D.2 "sub-cat" parenthetical / D.4 sensitivity rules:
--   * fallen live wire — 4h/48h safety SLA (vs Electricity 24h/14d).
--   * disputed meter — PRIVATE visibility (vs Electricity PUBLIC).
--   * disease outbreak — 6h/7d (vs Health 48h/21d).
--   * health negligence/complaint — Sensitive + PRIVATE (individual complaint).
--   * education misconduct/abuse — Sensitive + PRIVATE.
--   * active threat (security) — 1h TTFR, immediate escalation; Sensitive + PRIVATE.
--   * crime report (security) — Sensitive (vs aggregate hotspots PUBLIC).
--   * land eviction/dispute — Sensitive (vs zoning PUBLIC).
--   * environment whistleblowing on a firm — Sensitive.
--   * agriculture pest/disease outbreak — 24h/7d (vs 5d/30d).
--   * social-welfare individual case — Sensitive (PII).
--   * public-service staff-conduct complaint — Sensitive + PRIVATE.
--
-- SLA minutes: 1h=60 4h=240 6h=360 24h=1440 48h=2880 7d=10080 14d=20160 30d=43200 60d=86400.
-- created_by = SYSTEM sentinel; version 0; active TRUE. Idempotent. Forward-only.
-- =============================================================================

INSERT INTO issue_category
    (public_id, version, created_at, created_by, code, name, parent_id,
     default_routing_level, default_sla_ttfr_minutes, default_sla_ttr_minutes,
     sensitive, force_private, default_visibility, icon, active)
SELECT seed_uuid('ISSUECAT', v.code), 0, now(), '00000000-0000-0000-0000-000000000000'::uuid,
       v.code, v.name,
       (SELECT id FROM issue_category p WHERE p.code = v.parent_code),
       v.routing, v.ttfr, v.ttr, v.sensitive, v.force_private, v.visibility, NULL, TRUE
FROM (VALUES
    -- code                              name                                                  parent                  routing            ttfr  ttr    sens   forcep  vis
    ('ELEC_FALLEN_WIRE',          'Waya wa umeme ulioanguka (Fallen live wire - safety)','ELECTRICITY_ENERGY',  'SECTOR_UTILITY',  240,  2880,  FALSE, FALSE, 'PUBLIC'),
    ('ELEC_DISPUTED_METER',       'Mgogoro wa mita ya umeme (Disputed/faulty meter)',   'ELECTRICITY_ENERGY',  'SECTOR_UTILITY',  1440, 20160, FALSE, FALSE, 'PRIVATE'),
    ('HEALTH_OUTBREAK',           'Mlipuko wa ugonjwa (Disease outbreak)',              'HEALTH',              'COUNCIL',         360,  10080, FALSE, FALSE, 'PUBLIC'),
    ('HEALTH_NEGLIGENCE',         'Uzembe/malalamiko ya huduma (Service negligence)',   'HEALTH',              'COUNCIL',         2880, 30240, TRUE,  FALSE, 'PRIVATE'),
    ('EDU_MISCONDUCT',            'Utovu wa nidhamu/unyanyasaji shuleni (Misconduct/abuse)','EDUCATION',        'COUNCIL',         4320, 43200, TRUE,  FALSE, 'PRIVATE'),
    ('SEC_ACTIVE_THREAT',         'Tishio la papo kwa papo (Active threat)',            'SECURITY_SAFETY',     'WARD',            60,   10080, TRUE,  FALSE, 'PRIVATE'),
    ('SEC_CRIME_REPORT',          'Taarifa ya uhalifu (Crime report)',                  'SECURITY_SAFETY',     'OVERSIGHT',       360,  10080, TRUE,  FALSE, 'PRIVATE'),
    ('LAND_EVICTION_DISPUTE',     'Mgogoro wa ardhi/uondoshwaji (Eviction/dispute)',    'LAND_HOUSING',        'COUNCIL',         7200, 86400, TRUE,  FALSE, 'PRIVATE'),
    ('ENV_WHISTLEBLOW',           'Ufichuzi dhidi ya kampuni (Firm whistleblowing)',    'ENVIRONMENT',         'SECTOR_UTILITY',  4320, 43200, TRUE,  FALSE, 'PUBLIC'),
    ('AGRI_PEST_OUTBREAK',        'Mlipuko wa visumbufu/magonjwa (Pest/disease outbreak)','AGRICULTURE_LIVESTOCK','WARD',          1440, 10080, FALSE, FALSE, 'PUBLIC'),
    ('WELFARE_INDIVIDUAL_CASE',   'Kesi ya mtu binafsi ya ustawi (Individual welfare case)','SOCIAL_WELFARE',   'COUNCIL',         7200, 43200, TRUE,  FALSE, 'PRIVATE'),
    ('PUBSVC_STAFF_CONDUCT',      'Malalamiko ya mwenendo wa mtumishi (Staff-conduct complaint)','PUBLIC_SERVICES','WARD',          4320, 30240, TRUE,  FALSE, 'PRIVATE')
) AS v(code, name, parent_code, routing, ttfr, ttr, sensitive, force_private, visibility)
ON CONFLICT (public_id) DO NOTHING;
