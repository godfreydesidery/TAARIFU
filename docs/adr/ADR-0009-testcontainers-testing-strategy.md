# ADR-0009: Testing strategy — JUnit 5 + Testcontainers + ArchUnit + contract tests

**Status:** Accepted · 2026-06-23 · Solution Architect
**Grounding:** PRD §15 (≥80% core coverage; contract tests; CI gates), §16; CLAUDE.md §9 (DoD), §10 (testing).

## Context
Schema, geospatial queries, constraints (unique phone, ID dedup, one-primary/one-electoral, effective-dated `WardConstituency`), and the response contract are where Taarifu's correctness lives. H2/in-memory databases don't reproduce PostgreSQL/PostGIS behaviour, partial-unique indexes, or `ddl-auto=validate` — tests that pass on H2 but fail on Postgres are worse than no tests. Module boundaries also rot without an automated guard.

## Decision
- **Unit tests (JUnit 5):** domain logic and services, no Spring context where possible — TDD encouraged for domain (CLAUDE.md §10).
- **Integration tests (Testcontainers, real PostgreSQL+PostGIS):** repositories, native/PostGIS queries, constraint enforcement, Flyway `validate`, and controllers (envelope + pagination) via REST Assured. Tests run against the **same Postgres+PostGIS image** as prod.
- **Boundary tests (ArchUnit):** enforce ARCHITECTURE §3.4 (no cross-module `domain`/`infrastructure` imports; controllers no `@Transactional`; no entity leaks past `api`; `domain.port` has no vendor imports).
- **Contract tests:** the API is verified against the committed **OpenAPI** spec; clients (Angular/Flutter) contract-test against it (CLAUDE.md §10).
- **Stub adapters** (ADR-0004) let full E2E tests run with **zero external calls**.
- **Coverage:** ≥80% on core modules; CI gates = build + test + SAST + container scan + migration validation (PRD §15).

## Consequences
- (+) Tests catch real Postgres/PostGIS/constraint behaviour; boundaries can't silently rot; the contract stays honest.
- (+) Stub adapters make CI hermetic and fast despite many integrations.
- (−) Testcontainers needs a Docker daemon in CI and is slower than in-memory — accepted; fidelity beats speed for a civic system of record. Reuse containers across a test class/suite to limit cost.
- **Revisit trigger:** if suite time becomes a bottleneck, split fast (unit/ArchUnit) from slow (Testcontainers) into separate CI stages; do not drop Testcontainers for H2.
