# Taarifu — Engineering Rules & Working Agreement

> The team's binding conventions. **The single source of product truth is [PRD.md](PRD.md)** (decisions in §19 + §25.10). This file governs *how* we build. Every agent and contributor follows it.

## 1. Product in one line
Taarifu is a **Tanzania civic-engagement platform** connecting citizens with elected representatives and government/parastatal/private responders — issue reporting & case management, representatives & institutions, engagement (petitions/polls/Q&A), and announcements & notifications. Swahili-first, mobile-first, inclusive of feature phones.

## 2. Methodology (modern, design-first)
- **Design first, then build.** No non-trivial code without an agreed design: an ADR and/or an updated section in `docs/` and (for APIs) the OpenAPI contract. Contract-first for APIs; schema-first (migrations) for data.
- **Iterative & incremental** (Agile). Work in small vertical slices that deliver an end-to-end capability. Prefer a thin working path over a broad unfinished one.
- **Definition of Ready** (before coding a story): clear acceptance criteria (from the PRD US-x.y / UC-x), design/ADR where it touches >1 module, test approach noted.
- **Definition of Done** (§9 below).

## 3. Engineering principles
- **SOLID** — single-responsibility classes/modules; depend on abstractions (ports), not implementations (adapters); keep interfaces small and focused; open for extension, closed for modification.
- **KISS** — the simplest design that satisfies the requirement and NFRs. No speculative generality, no premature microservices, no frameworks we don't need.
- **DRY** — one source of truth per concept. Shared logic lives in the **shared kernel** / common modules, never copy-pasted (the legacy code's biggest sin — see SYNOPSIS).
- **Clean boundaries** — modular monolith with strict module boundaries and a shared kernel; a module exposes a small public API and hides its internals. Cross-cutting integrations sit behind **pluggable adapter ports** (PRD §21, DI1–DI7).
- **Fail safe & degrade gracefully** — never hard-fail the citizen path on a third-party outage; honour the integrity/security guardrails (tiered identity, one-account-additive-roles, electoral scoping, tokens never buy democratic weight).
- **Secure & private by default** — deny-by-default authz, method-level security, no secrets in source, PII (national/voter ID) encrypted at rest, PDPA 2022/2023 (PRD §18, §25.1).

## 4. Repository layout (monorepo)
```
/backend      Spring Boot (Java 21) modular monolith — the API & domain
/web-admin    Angular 18 admin console (+ citizen web/PWA workspace)
/mobile       Flutter citizen app
/docs         design docs, ADRs (docs/adr/), OpenAPI (docs/api/), diagrams
/.claude/agents  the technical team (subagents)
PRD.md  SYNOPSIS.md  TEAM.md  README.md  CLAUDE.md
```
> Reference clones `taarifu-engine-api|engine-dash|mob-app|core-api` are **insight only** and git-ignored — never import or copy their code.

## 5. Tech stack (target)
- **Backend:** Java 21, Spring Boot 3.x (Web, Security, Data JPA, Validation, Actuator), PostgreSQL (+ PostGIS), Flyway migrations, Redis, S3-compatible object storage, transactional outbox + event bus, JWT (access + rotating refresh), springdoc-openapi, JUnit 5 + Testcontainers, Maven. `ddl-auto=validate` (Flyway owns schema).
- **Admin/Web:** Angular 18 (standalone components), TypeScript, RxJS, i18n (SW default + EN), PWA/offline, WCAG 2.1 AA.
- **Mobile:** Flutter/Dart, BLoC, offline-first, FCM, secure storage, SW/EN.
- **Infra:** Docker, CI/CD (build→test→SAST→container-scan→deploy), in-country-where-feasible hosting, observability (logs/metrics/traces, health).

## 6. Branching & PR policy (gitflow-lite)
- **`main`** — release/stable. **PR-only. Never commit or push directly.**
- **`develop`** — integration branch. Feature work merges here.
- **`feature/<short-name>`** — branch **off `develop`** for each unit of work. Merge back into `develop` (PR or fast-forward) **occasionally** at integration points; keep features small and short-lived.
- **`main` is updated only via PR** from `develop` (`gh pr create --base main --head develop`).
- Rebase/keep features current with `develop`. Delete merged feature branches.

## 7. Commits (Conventional Commits)
`type(scope): summary` — types: `feat, fix, refactor, docs, test, chore, build, ci, perf, style`.
- Small, focused, buildable commits. Imperative mood. Reference the PRD/US/UC/ADR where useful.
- **Commit and push occasionally** (logical checkpoints), not one giant commit.
- Examples: `feat(identity): add Profile entity + V2 migration`, `docs(adr): ADR-0003 modular-monolith boundaries`, `test(geography): ward→constituency resolution`.

## 8. Coding standards
- **Backend:** package-by-feature/module (e.g. `com.taarifu.identity`, `com.taarifu.geography`, `com.taarifu.reporting`, `com.taarifu.common`). Constructor injection only. DTOs at the boundary; entities never leak out of a module. One response envelope + error model (PRD §17). UUID public ids; human codes via DB sequences. Validation at the edge. No business logic in controllers. Lombok allowed but sparingly.
- **Naming:** intention-revealing, no abbreviations-for-their-own-sake; correct Swahili civic terms where domain-facing (Mkoa/Wilaya/Halmashauri/Kata/Jimbo/Mbunge/Diwani — see tanzania-domain-expert).
- **Frontend/Mobile:** feature folders, smart/dumb component split, typed API clients generated/derived from OpenAPI, all user-facing strings externalised (i18n), no hardcoded URLs/secrets.
- **Documentation via code comments (required):** every **component is documented in code** — each class/module, public method/function, REST endpoint, entity field with non-obvious meaning, BLoC/service, and Angular/Flutter component carries a doc comment using the language's standard: **Javadoc** (`/** … */`) for Java, **TSDoc/JSDoc** (`/** … */`) for TypeScript, **dartdoc** (`///`) for Dart, and SQL comments for migrations. State the component's **responsibility/purpose**, parameters/returns, thrown errors, and the **"why"** for any non-obvious decision (security/PDPA, integrity guardrails, Tanzanian rules, edge cases). Keep comments accurate and updated with the code — stale comments are a defect; explain *why*, not the obvious *what*. Match the surrounding density.

## 9. Definition of Done
A change is done when: it meets its acceptance criteria; **every component is documented with code comments** (§8 — Javadoc/TSDoc/dartdoc); has tests (unit + integration where it crosses a boundary; contract tests for APIs); passes lint + build + tests + SAST locally; updates the relevant **design docs / ADRs / OpenAPI / PRD cross-refs**; honours security/PDPA + the locked decisions; is i18n-ready (SW/EN); and is reviewed (architect for cross-cutting, security for identity/PII/tokens/audit/moderation).

## 10. Testing
- **TDD encouraged** for domain logic. Unit-test the domain; integration-test repositories/controllers with **Testcontainers (PostgreSQL)**; contract-test the API against the OpenAPI spec; cover the edge cases the PRD calls out (offline sync, USSD/SMS, electoral-vs-residence, ID dedup, anonymous sensitive reports). Target ≥80% on core modules.

## 11. The team (how to delegate)
Specialist subagents live in `.claude/agents/` (roster in [TEAM.md](TEAM.md)). Use them by role: `solution-architect` (design/ADRs), `database-engineer` (schema/migrations), `backend-engineer`, `frontend-engineer`, `mobile-engineer`, `integrations-engineer`, `qa-engineer`, `security-privacy-engineer`, `devops-sre`, `ux-ui-designer`, `tanzania-domain-expert` (civic correctness), `trust-safety-moderator`, plus four `enduser-*` personas for UAT. **Architecture decisions go through `solution-architect`; anything touching identity/PII/tokens/audit/moderation goes through `security-privacy-engineer`; civic correctness through `tanzania-domain-expert`.**

## 12. Guardrails (do not violate)
- Don't edit/commit/push `main` directly. Don't copy legacy clone code. Don't put secrets in source. Don't bypass method-level authz. Don't let tokens gate or buy democratic-weight actions. Don't expose PII or `ProfileLocation`/private reports publicly. Don't redesign a locked decision (PRD §19/§25.10) without an ADR that supersedes it.
