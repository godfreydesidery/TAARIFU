# ADR-0008: Single `ApiResponse` envelope + error model, and transactional outbox for events

**Status:** Accepted · 2026-06-23 · Solution Architect
**Grounding:** PRD §17 (one envelope; idempotency), §16 + §21 DI3 (event bus/outbox), §15 (no hard dependency on the mobile path); CLAUDE.md §3, §8.

## Context
Two foundation contracts must be set once, in the shared kernel. **(1)** The legacy systems shipped **three inconsistent response envelopes**, forcing clients into per-endpoint special-casing (PRD §17). **(2)** Side-effecting work (notifications, indexing, analytics, verification callbacks, feed fan-out) must not run inside — or be rolled back by — the citizen's request transaction, and must survive provider outages (PRD §15, §21 DI3).

## Decision
**(1) One envelope for everything.** Every response — success and error — uses `common.api.dto.ApiResponse<T>` `{success, statusCode, message, data, meta, timestamp}` (PRD §17).

- **`statusCode`** is the **integer HTTP status** of the response (`200`, `201`, `400`, `403`, `404`, `409`, `429`, `500`, …), derived on errors from `ErrorCode.httpStatus().value()` and `200` on success. Clients read the outcome class at the top of the envelope without parsing the transport status line.
- **`message`** is localised (SW/EN).
- On **success**, `data` is the payload (object, or a list page's content).
- On **error**, `data` is an **`ApiError` `{code, errors?}`** where **`data.code`** is the **stable machine error code** (`ErrorCode.name()` — e.g. `TIER_TOO_LOW`, `NOT_FOUND`) clients branch on, and **`data.errors[]`** holds field-level Bean Validation failures (`ErrorDetail{field, code, message}`), omitted for non-validation errors.

**Rationale for `statusCode` (int) at the top + `code` (machine) in `data`.** The top-level field was changed from a `String code` (machine code) to an **`int statusCode`** so clients (and gateways/proxies/log pipelines) can read the numeric HTTP outcome directly off the body. The machine code is **not lost** — it is preserved at **`data.code`**, because the int status alone cannot discriminate distinct domain errors that share a status: `TIER_TOO_LOW`, `OUT_OF_SCOPE` and `CONFLICT_OF_INTEREST` are all `403`, and `CONFLICT`/`DUPLICATE_IDENTITY` are both `409`. Clients keep their stable, language-independent discriminator at `data.code`; the former `ErrorDetail.ValidationErrors` wrapper is folded into `ApiError.errors` (one error shape, DRY).

A single `@RestControllerAdvice` `GlobalExceptionHandler` maps **every** exception (validation, authz, optimistic-lock, not-found, domain) to the envelope with correct HTTP status and field-level `data.errors[]`. Controllers use `ResponseFactory`, never hand-build JSON. Idempotency-Key on create/submit; optimistic concurrency via `@Version`.

**Error body example** (HTTP 403, machine code preserved in `data`):
```json
{
  "success": false,
  "statusCode": 403,
  "message": "Kiwango chako cha uthibitisho hakitoshi kwa kitendo hiki.",
  "data": { "code": "TIER_TOO_LOW" },
  "timestamp": "2026-06-23T09:00:00Z"
}
```

**(2) Transactional outbox.** Domain changes and their **domain event** commit in **one transaction** (the event is written to an `outbox` table). An async **relay** publishes to the **event bus** (RabbitMQ at MVP → Kafka at scale); **idempotent workers** with backoff+jitter and a **DLQ** consume it for notifications, feed fan-out, SLA clocks, search indexing, and analytics. Events in `<module>.api.event` are the only async cross-module contract.

## Consequences
- (+) Clients write one response handler; errors are uniform and localised; APIs are predictable and contract-testable.
- (+) The citizen's write commits fast and is never rolled back by a downstream outage (PRD §15); effects are exactly-once-effect under at-least-once delivery.
- (+) The outbox/bus is the seam that lets workers (notifications/feed/search) become services later (ARCHITECTURE §10).
- (−) An outbox relay + workers + DLQ + idempotency keys are real infrastructure — accepted as the price of resilience; the smallest slices may use in-process `ApplicationEventPublisher` first and graduate to the bus.
- **Revisit trigger:** move the transport from RabbitMQ to Kafka when fan-out volume/retention demands; the worker/event contracts don't change.
