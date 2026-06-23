# ADR-0002: Modular monolith (not microservices)

**Status:** Accepted · 2026-06-23 · Solution Architect
**Grounding:** PRD §16 (system architecture), §15 (NFRs); CLAUDE.md §3 (KISS, clean boundaries).

## Context
Taarifu must reach national scale (millions of citizens) with p95 < 500ms reads / < 1s writes and 99.9% availability (PRD §15), while shipping an MVP with a small team. The prior repos failed not because they were monoliths but because they had **no module boundaries** and **copy-pasted** shared logic (PRD §6.3, SYNOPSIS). Microservices now would add network, deployment, and data-consistency complexity the team does not need yet (premature distribution = a KISS violation, CLAUDE.md §3).

## Decision
Build a **modular monolith**: one deployable Spring Boot (Java 21) application with **strict internal module boundaries** (`identity, geography, institutions, reporting, engagement, accountability, communications, responders, tokens, moderation, admin`) over a **shared kernel** (`common`), exactly as PRD §16 prescribes. Modules communicate only via (a) another module's **public API package** (in-process) or (b) **domain events** over the outbox/bus. Seams are pre-cut so the high-load modules PRD §16 names — **notifications, feed, search, reporting** — can be **extracted to services later** without a rewrite (see ARCHITECTURE §10).

## Consequences
- (+) Simple ops (one deploy, one DB, in-process calls) with the fast feedback a small team needs; transactions stay local.
- (+) Strict boundaries + shared kernel kill the legacy copy-paste (DRY) and keep extraction cheap.
- (+) No distributed-systems tax (network failures, eventual consistency, saga orchestration) until a real trigger demands it.
- (−) One process is a shared failure/scaling unit; mitigated by async fan-out (outbox/bus), caching, and read models so the hot paths scale within the monolith.
- (−) Boundaries must be **enforced mechanically** (Maven multi-module + ArchUnit, ARCHITECTURE §3.4), or they rot — accepted as a hard CI gate.
- **Revisit trigger:** extract a module to a service when its scaling/deploy-cadence/load-isolation needs diverge (ARCHITECTURE §10 triggers) — not before.
