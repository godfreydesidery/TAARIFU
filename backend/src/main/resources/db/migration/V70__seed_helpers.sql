-- =============================================================================
-- V70__seed_helpers.sql  —  Reference-data SEED foundation (helpers only).
--
-- Responsibility: install the small, IMMUTABLE helper this V70..V85 seed block uses
-- to mint DETERMINISTIC public_id UUIDs from a row's natural key (its `code`), so that:
--   * re-running any seed migration is idempotent (ON CONFLICT (public_id) DO NOTHING
--     matches the SAME row every time — the UUID is a pure function of the code), and
--   * cross-migration references resolve by code without a round-trip (a ward's
--     constituency mapping can compute both UUIDs from codes alone).
--
-- DESIGN NOTES (the "why", per CLAUDE.md §8):
--   * UUIDv5-style (name-based, SHA-256 folded to 128 bits, RFC-4122 version=5 +
--     variant bits set). NOT random gen_random_uuid(): a random id would change on
--     every run and break idempotency / make the seed non-reproducible across envs.
--   * Namespaced by entity ("REGION:", "DISTRICT:", "PARTY:", ...) so the SAME code in
--     two tables (unlikely but possible) never collides on public_id.
--   * IMMUTABLE + STRICT: a pure function of its input; Postgres may inline/cache it.
--   * created_by = SYSTEM sentinel = all-zero UUID (AuditorAwareImpl.SYSTEM_ACTOR =
--     new UUID(0L,0L)); reference data is a system write, never a user write.
--   * The helper lives in the public schema. Hibernate ddl-auto=validate inspects only
--     mapped TABLES, never functions, so this is invisible to validate (no drift risk).
--
-- Idempotent: CREATE OR REPLACE FUNCTION. Forward-only; never edit once applied.
-- =============================================================================

-- pgcrypto (digest()) is installed by V1; CREATE EXTENSION IF NOT EXISTS is a no-op here
-- but keeps this migration self-describing about its dependency.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- seed_uuid(namespace, code) -> deterministic RFC-4122 v5-style UUID.
-- Folds SHA-256(namespace || ':' || code) to 16 bytes, then forces version (5) and
-- the RFC-4122 variant (10xx) nibbles so the result is a syntactically valid UUID.
CREATE OR REPLACE FUNCTION seed_uuid(p_namespace text, p_code text)
RETURNS uuid
LANGUAGE sql
IMMUTABLE
STRICT
AS $$
    SELECT (
        substr(h, 1, 8)  || '-' ||
        substr(h, 9, 4)  || '-' ||
        '5' || substr(h, 14, 3) || '-' ||                                   -- version 5
        to_hex( ( ('x' || substr(h, 17, 2))::bit(8) & b'00111111' | b'10000000' )::int ) ||
        substr(h, 19, 2) || '-' ||                                          -- variant 10xx
        substr(h, 21, 12)
    )::uuid
    FROM ( SELECT encode(digest(p_namespace || ':' || p_code, 'sha256'), 'hex') AS h ) s;
$$;

COMMENT ON FUNCTION seed_uuid(text, text) IS
    'Deterministic RFC-4122 v5-style UUID from (namespace, natural code) for idempotent, reproducible reference-data seeding (V70..V85). Pure function; invisible to Hibernate ddl-auto=validate.';
