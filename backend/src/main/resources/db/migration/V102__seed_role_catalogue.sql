-- =============================================================================
-- V102 — seed the platform role catalogue (RoleName, PRD §7.2).
--
-- WHY: the `role` table is referenced by SignupService (findByName(CITIZEN)) and by
-- every RoleAssignment, but no prior migration populated it. An empty catalogue makes
-- citizen signup fail at runtime (findByName -> empty -> INTERNAL_ERROR) in every
-- non-dev environment. This seeds all RoleName values so signup, additive role grants
-- (D15), responder/representative onboarding, and staff login work out of the box.
--
-- Idempotent (ON CONFLICT on the unique name) so re-runs / dev bootstrap are safe.
-- created_by left NULL = system-seeded (no acting principal); version=0 for the
-- optimistic-lock column (NOT NULL, no default). Descriptions mirror the enum Javadoc.
-- =============================================================================
INSERT INTO role (public_id, version, created_at, deleted, name, description)
VALUES
    (gen_random_uuid(), 0, now(), FALSE, 'GUEST',           'Unauthenticated visitor (default, no account).'),
    (gen_random_uuid(), 0, now(), FALSE, 'CITIZEN',         'Registered citizen — the base role of every account.'),
    (gen_random_uuid(), 0, now(), FALSE, 'ORG_MEMBER',      'Member of an organisation profile.'),
    (gen_random_uuid(), 0, now(), FALSE, 'ORG_ADMIN',       'Administrator of an organisation profile.'),
    (gen_random_uuid(), 0, now(), FALSE, 'REPRESENTATIVE',  'Elected representative (MP/Councillor/exec) acting in civic capacity.'),
    (gen_random_uuid(), 0, now(), FALSE, 'RESPONDER_AGENT', 'Staff agent of a responder (govt/parastatal/private), scoped to areas/categories (D20).'),
    (gen_random_uuid(), 0, now(), FALSE, 'RESPONDER_ADMIN', 'Administrator of a responder workspace (D20).'),
    (gen_random_uuid(), 0, now(), FALSE, 'MODERATOR',       'Content/safety moderator.'),
    (gen_random_uuid(), 0, now(), FALSE, 'ADMIN',           'Platform administrator (reference data, taxonomy, role granting).'),
    (gen_random_uuid(), 0, now(), FALSE, 'ROOT',            'Super-administrator (root); the highest-trust operational role.')
ON CONFLICT (name) DO NOTHING;
