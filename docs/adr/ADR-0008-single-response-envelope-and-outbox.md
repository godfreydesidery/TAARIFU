# ADR-0008: Single `ApiResponse` envelope + error model, and transactional outbox for events

**Status:** Accepted · 2026-06-23 · Solution Architect
**Grounding:** PRD §17 (one envelope; idempotency), §16 + §21 DI3 (event bus/outbox), §15 (no hard dependency on the mobile path); CLAUDE.md §3, §8.

## Context
Two foundation contracts must be set once, in the shared kernel. **(1)** The legacy systems shipped **three inconsistent response envelopes**, forcing clients into per-endpoint special-casing (PRD §17). **(2)** Side-effecting work (notifications, indexing, analytics, verification callbacks, feed fan-out) must not run inside — or be rolled back by — the citizen's request transaction, and must survive provider outages (PRD §15, §21 DI3).

## Decision
**(1) One envelope for everything.** Every response — success and error — uses `common.api.dto.ApiResponse<T>` `{success, code, message, data, meta, timestamp}` (PRD §17). Stable machine `code`s drive client branching; `message` is localised (SW/EN). A single `@RestControllerAdvice` `GlobalExceptionHandler` maps **every** exception (validation, authz, optimistic-lock, not-found, domain) to the envelope with correct HTTP status and field-level `errors[]`. Controllers use `ResponseFactory`, never hand-build JSON. Idempotency-Key on create/submit; optimistic concurrency via `@Version`.

**(2) Transactional outbox.** Domain changes and their **domain event** commit in **one transaction** (the event is written to an `outbox` table). An async **relay** publishes to the **event bus** (RabbitMQ at MVP → Kafka at scale); **idempotent workers** with backoff+jitter and a **DLQ** consume it for notifications, feed fan-out, SLA clocks, search indexing, and analytics. Events in `<module>.api.event` are the only async cross-module contract.

## Consequences
- (+) Clients write one response handler; errors are uniform and localised; APIs are predictable and contract-testable.
- (+) The citizen's write commits fast and is never rolled back by a downstream outage (PRD §15); effects are exactly-once-effect under at-least-once delivery.
- (+) The outbox/bus is the seam that lets workers (notifications/feed/search) become services later (ARCHITECTURE §10).
- (−) An outbox relay + workers + DLQ + idempotency keys are real infrastructure — accepted as the price of resilience; the smallest slices may use in-process `ApplicationEventPublisher` first and graduate to the bus.
- **Revisit trigger:** move the transport from RabbitMQ to Kafka when fan-out volume/retention demands; the worker/event contracts don't change.
