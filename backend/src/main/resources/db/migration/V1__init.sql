-- =============================================================================
-- V1__init.sql  —  Baseline / shared setup for the Taarifu schema.
--
-- Responsibility: install the database extensions every later migration depends
-- on, before any table is created. Flyway owns the schema; Hibernate runs with
-- ddl-auto=validate and never creates anything (ARCHITECTURE.md §4.1, ADR-0005).
--
-- WHY these extensions:
--   * postgis  — the geography module's `location` table carries PostGIS columns
--     (`boundary geometry(MultiPolygon,4326)`, `centroid geometry(Point,4326)`)
--     mapped via hibernate-spatial to JTS Geometry. Point-in-polygon GPS->ward
--     resolution (EI-7, PRD §9.0) needs the geometry type + GiST indexing. The
--     entity columns will FAIL Hibernate validate without this extension present.
--   * pgcrypto — provides gen_random_uuid() and digest()/hmac primitives. UUID
--     public ids are generated application-side today (BaseEntity.assignPublicId),
--     but seed/bulk-import paths and any DB-side default fall back to pgcrypto;
--     installing it here keeps later reference-data migrations self-contained.
--
-- Idempotent: every statement uses IF NOT EXISTS so re-running on a partially
-- provisioned database is safe (the seed/region-onboarding gate re-runs these).
-- Forward-only: never edit this file once applied — add a new V<n> migration.
-- =============================================================================

-- PostGIS: spatial types + GiST/geometry operators for ward boundaries (PRD §9.0, EI-7).
CREATE EXTENSION IF NOT EXISTS postgis;

-- pgcrypto: gen_random_uuid() + hashing primitives for seed/import and DB-side UUID defaults.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
