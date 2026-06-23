# ADR-0004: Ports & adapters for every external integration

**Status:** Accepted · 2026-06-23 · Solution Architect
**Grounding:** PRD §21 (DI1–DI7), §15 ("no single hard dependency on the mobile path"), §16; CLAUDE.md §3 (clean boundaries, fail-safe).

## Context
Taarifu touches many external systems — NIDA/voter verification, SMS/USSD aggregators, FCM/APNs push, email, object storage + malware scan, geocoding, search, content-safety, KMS, and (Phase 2) mobile-money payments (PRD §21 EI-1…EI-20). Vendors churn per market/contract; routes fail; the citizen path must **never hard-fail on a third-party outage** (PRD §15). The legacy code called vendor SDKs directly from domain logic, making swaps and degradation impossible.

## Decision
Integrate **every** external system through a **port** (interface in `<module>.domain.port`) with **adapter** implementations in `<module>.infrastructure.adapter`, selected by config/feature-flag (PRD §21 DI1). Each port has:
- **≥1 stub/sandbox adapter** for dev/test/demo (zero external calls — enables CI and staged onboarding, DI1/§21.4);
- an explicit **degradation mode** — queue, fallback channel, cached value, or operator-assisted route (DI2);
- **no vendor SDK in domain code** (DI1) and **no civic semantics in vendor formats** — mapping lives in the adapter (DI7);
- resilience on synchronous calls (timeout + circuit-breaker + retry-with-jitter + bulkhead) and **idempotent** sends/callbacks (DI4);
- per-adapter observability (success rate, p95, queue depth, circuit state, provider cost — DI6).
Side-effecting integrations run async via the **outbox → bus → worker** (DI3, ADR-0008-aligned). The internal **PostGIS geocoder** and **Postgres FTS search** are the in-house primary implementations so no single vendor is load-bearing at launch (§21.4 portability).

## Consequences
- (+) Vendor swaps and market-specific routing never touch domain/application code; provider exit is cheap.
- (+) The product degrades instead of failing; full E2E demos/tests run with stub adapters.
- (−) Every integration needs a port + ≥1 real adapter + a stub + a defined degradation path — more upfront work, accepted as the cost of resilience and the PRD's binding integration principles.
- **Revisit trigger:** a port whose every realistic adapter shares heavy logic may grow a shared abstract base in `infrastructure` (still no vendor leak into `domain`).
