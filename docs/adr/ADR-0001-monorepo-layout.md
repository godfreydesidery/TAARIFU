# ADR-0001: Monorepo layout (backend + web-admin + mobile + docs)

**Status:** Accepted · 2026-06-23 · Solution Architect
**Grounding:** CLAUDE.md §4 (repository layout); PRD §14 (clients & channels), §16.

## Context
Taarifu ships four artefacts that share contracts: a Spring Boot backend, an Angular admin console (+ citizen web/PWA), a Flutter citizen app, and design/API docs. The clients are **derived from the backend's OpenAPI contract** (typed API clients, contract tests — CLAUDE.md §8, §10), and the four prior repos fragmented this — drift between API, web and mobile was a recurring failure. The team is small and works design-first across all surfaces in lockstep.

## Decision
Use a **single monorepo** with top-level folders, exactly as CLAUDE.md §4 fixes:
```
/backend   /web-admin   /mobile   /docs   /.claude/agents
PRD.md  SYNOPSIS.md  TEAM.md  README.md  CLAUDE.md
```
- `/docs` holds ADRs (`docs/adr/`), architecture (`docs/architecture/`), OpenAPI (`docs/api/`), diagrams.
- The OpenAPI contract is the **shared source of truth** between backend and clients; it lives in `/docs/api` and is contract-tested.
- Reference clones (`taarifu-engine-api|engine-dash|mob-app|core-api`) are **git-ignored, insight-only** — never imported.
- Branching per CLAUDE.md §6 (gitflow-lite: `main` PR-only, `develop` integration, short-lived `feature/*`).

## Consequences
- (+) One PR can change a contract and all consumers atomically; no cross-repo version dance; one place for design docs and ADRs.
- (+) Easy enforcement of "clients derive from OpenAPI" and shared CI gates.
- (−) CI must be path-scoped (build only what changed) to stay fast — accepted; per-folder build pipelines.
- (−) Repo grows; mitigated by clear top-level ownership and `.gitignore` for the legacy clones.
- **Revisit trigger:** if a client team needs a fully independent release cadence/tooling that path-scoped CI can't serve, split that client into its own repo and consume the published OpenAPI artefact.
