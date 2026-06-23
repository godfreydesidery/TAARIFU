# ADR-0003: Package-by-feature with internal api/application/domain/infrastructure layering

**Status:** Accepted · 2026-06-23 · Solution Architect
**Grounding:** CLAUDE.md §8 (package-by-feature); PRD §16; SOLID (CLAUDE.md §3).

## Context
Package-by-layer (`controllers/`, `services/`, `repositories/` at the top) hides feature boundaries and invites cross-feature coupling — a contributor to the legacy "one giant service layer" problem. We need boundaries that are **visible in the package tree** and **enforceable**, and an internal structure that keeps controllers thin, transactions explicit, the domain testable without Spring, and vendor SDKs quarantined (CLAUDE.md §3, §8).

## Decision
**Package-by-feature** at the top: `com.taarifu.<module>`. Inside each module, **four layers** with inward-pointing dependencies:
- `api` (PUBLIC) — `controller`, `dto`, `event`. The module's only outward contract.
- `application` — `service` (transaction boundary), `mapper`.
- `domain` — `model` (JPA entities + enums), `repository`, `port` (outbound interfaces).
- `infrastructure` — `adapter` (vendor impls), `config`, `persistence`.

Rules: controllers carry no business logic and no `@Transactional`; entities never leave `api`; only `infrastructure.adapter` imports vendor SDKs; cross-module calls hit another module's `api` only.

## Consequences
- (+) Boundaries are obvious and **ArchUnit-checkable** (ARCHITECTURE §3.4); a feature is a cohesive slice you can read, test, and later extract.
- (+) SOLID by construction: SRP per layer, dependency-inversion via `domain.port`, small public surface.
- (−) More packages/ceremony than package-by-layer — accepted; the slice template (FOUNDATION-SCOPE §4) makes it mechanical.
- **Revisit trigger:** none expected; if a module genuinely has no infrastructure/ports, its `infrastructure` package may be omitted (still no layer inversion).
