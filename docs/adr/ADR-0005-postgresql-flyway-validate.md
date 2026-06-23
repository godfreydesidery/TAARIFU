# ADR-0005: PostgreSQL + PostGIS, Flyway migrations, `ddl-auto=validate`

**Status:** Accepted · 2026-06-23 · Solution Architect
**Grounding:** PRD §16 (Postgres, Flyway, validate), §9.0 (geography/PostGIS), §5; CLAUDE.md §2 (schema-first), §5.

## Context
Taarifu needs a relational store with **geospatial** capability (GPS→ward point-in-polygon, ward boundaries — PRD §9.0, EI-7), strong constraints (unique phone, ID dedup, one-primary/one-electoral location), and a **disciplined, reviewable schema history**. The legacy approach let Hibernate manage DDL (`ddl-auto=update`), producing drift and unrepeatable schemas. Schema must be **schema-first / migration-owned** (CLAUDE.md §2).

## Decision
- **PostgreSQL 16 + PostGIS 3.4** as the primary store.
- **Flyway 10** owns the schema; migrations in `src/main/resources/db/migration`, named `V<NNN>__<module>_<change>.sql`, **forward-only**, **range-partitioned per module** to avoid merge collisions (ARCHITECTURE §4.1), each with **SQL comments** (CLAUDE.md §8).
- **`spring.jpa.hibernate.ddl-auto=validate`** — Hibernate never creates/alters; it only validates entities against the migrated schema and **fails fast** on mismatch (PRD §16).
- PostGIS is the **in-house source of truth** for geocoding; closure table for hierarchy; effective-dated `WardConstituency` (PRD §9.0).

## Consequences
- (+) Every schema change is an explicit, reviewed, versioned, environment-identical artefact; no drift; geospatial queries are first-class.
- (+) `validate` catches entity/DDL divergence at boot, not in production.
- (−) Engineers must hand-write migrations (can't lean on auto-DDL) and never edit an applied one — accepted; this is the point.
- (−) PostGIS adds an extension dependency and slightly heavier local setup — accepted; Testcontainers uses a PostGIS image so tests match prod.
- **Revisit trigger:** if read load on feed/search/reports outgrows Postgres, introduce read replicas and/or move `SearchPort` to OpenSearch (ADR-0004 already isolates this) — Postgres stays the system of record.
