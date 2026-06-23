# ADR-0014: Transactional outbox + in-process event bus — the async substrate that finally lets the `// TODO(wiring)` event-driven integrations land (KISS: one DB table + a poller + an in-process dispatcher, no Kafka, no RabbitMQ)

**Status:** Accepted · 2026-06-23 · Solution Architect (David Okello)
**Extends:** ADR-0008 (single envelope + **transactional outbox** — promised the outbox/bus but did not build it), ADR-0013 (cross-module integration — fixed the contracts and left every cross-module **write/effect** as `// TODO(wiring)` "*until the outbox increment*"). ADR-0002 (modular monolith), ADR-0004 (ports & adapters). This ADR builds the **smallest outbox/bus that honours ADR-0008's atomicity guarantee and unblocks ADR-0013 §2's deferred wirings** — and deliberately stops there.
**Grounding:** PRD §16 (event-driven fan-out; outbox; extract-to-service), §21 DI3 (transactional outbox, idempotent workers, backoff+jitter, DLQ), §15 (no hard dependency on the mobile path; p95), §13 (announcements → notifications/feed), §24.3 / D21 (routing → responder OWNER assignment), §18 (PII out of logs/events). ARCHITECTURE.md §8 (the outbox diagram — this ADR realises its **MVP "even in-process `ApplicationEventPublisher`"** rung), §3.1 (events are the only async cross-module contract), §10 (extract-to-service). CLAUDE.md §3 (KISS/clean boundaries/fail-safe), §8 (no entity leak; UUID ids), §12 (no PII exposure).
**Companion (precedent in code, awaiting this bus):** `communications.api.event.AnnouncementPublished`, `responders.api.event.ResponderAssignedEvent`, `tokens.api.event.TokenSpent` — three event records already shaped in `<module>.api.event` with `// TODO(wiring): once the shared transactional-outbox base exists (common.outbox) …`. This ADR is that base.

## Context

ADR-0008 set the **atomicity contract** ("domain change + event commit in one transaction; an async relay publishes; idempotent workers with backoff+jitter and a DLQ consume") but built none of it. ADR-0013 then fixed every cross-module **contract** — synchronous reads via `*QueryApi`, writes/effects via events — and explicitly parked the writes: routing → OWNER assignment (D21), notification/feed fan-out, SLA clocks, search/analytics, moderation takedown, async rewards all sit at `// TODO(wiring)` **gated on an outbox that does not exist**. Three event records (`AnnouncementPublished`, `ResponderAssignedEvent`, `TokenSpent`) already exist in `<module>.api.event` as the contract, raised in-process today with no durable, transactional, retried delivery behind them. The `common.outbox` package named in ARCHITECTURE.md §11 is empty.

Four forces shape the decision:

1. **Atomicity is non-negotiable (ADR-0008, PRD §15 DI3).** The "something happened" intent must commit in the **same DB transaction** as the domain row, or a crash between "row committed" and "event published" silently drops fan-out/routing. A `@TransactionalEventListener(AFTER_COMMIT)` alone does **not** give this — if the JVM dies after commit but before the listener runs, the effect is lost. Only a **durable outbox row written in the same tx** survives a crash.
2. **KISS — we are one deployable at national-MVP scale (ARCHITECTURE.md §1, §8 "MVP … even in-process `ApplicationEventPublisher`").** We do **not** introduce Kafka or RabbitMQ now: no broker to operate, no partition/consumer-group ops, no extra residency surface. The bus is **in-process** (a dispatcher invoking handlers inside the same JVM). The outbox table is the durability + retry + ordering substrate the broker would otherwise provide.
3. **At-least-once delivery ⇒ idempotent handlers (PRD §21 DI3).** A poller that dispatches then marks PROCESSED can crash after dispatch, before the mark — so a handler **will** occasionally see the same event twice. Effects must be **exactly-once-effect** under at-least-once delivery. This is the single hardest correctness constraint and is pushed onto the handler contract.
4. **No PII in events (PRD §18, §12; CLAUDE.md §12).** The payload is JSONB of **ids/codes/enums only**; consumers re-read the aggregate by id through the owner's `*QueryApi`. National/voter IDs, names, body text **never** enter the outbox (it is queryable, replayable, and dumped in support).

This ADR builds the substrate; it does **not** implement the business handlers — it wires exactly **two** to prove the seam (§5) and leaves the rest as the now-unblocked `// TODO(wiring)`.

## Decision

Build a **transactional outbox table (`V97`) + an `@Scheduled` relay/poller + an in-process `EventDispatcher` over a `DomainEventHandler` SPI**, all in the shared kernel `com.taarifu.common.outbox`. The producer writes one outbox row in its own `@Transactional`; the relay reads PENDING rows, dispatches each to the registered handlers, and marks the row PROCESSED — at-least-once, with **idempotent handlers**, bounded retries with backoff, and a terminal FAILED state. No broker.

### 1. `OutboxEvent` entity + `outbox_event` table — migration **V97**

The durable record of "something happened", written in the producer's transaction. It extends `BaseEntity` (internal `Long id`, public `UUID publicId`, audit, optimistic-lock — ARCHITECTURE §4.2) and lives in `common.outbox` (it is shared-kernel infrastructure, not any one feature module's domain).

| Column | Type | Purpose / WHY |
|---|---|---|
| `id` | `bigserial` PK | internal surrogate (BaseEntity). FK target for nothing — outbox is leaf. |
| `public_id` | `uuid` unique not null | public id (BaseEntity). Also serves as a natural **delivery dedup anchor** (see idempotency, §3). |
| `aggregate_type` | `varchar(64)` not null | the producing aggregate's type, e.g. `ANNOUNCEMENT`, `REPORT`. For routing/diagnostics and replay-by-type. |
| `aggregate_id` | `uuid` not null | the producing aggregate's **public** id (never internal `id`, never an FK — ADR-0013: cross-module references are opaque UUIDs). |
| `event_type` | `varchar(96)` not null | the taxonomy key handlers register on, e.g. `ANNOUNCEMENT_PUBLISHED`, `REPORT_ROUTED`. |
| `payload` | `jsonb` not null | the event body — **ids/codes/enums ONLY, NO PII** (PRD §18). Serialised `EventEnvelope` (§4). |
| `occurred_at` | `timestamptz` not null | domain-time the thing happened (set by the producer; ordering key — §"ordering caveats"). |
| `status` | `varchar(16)` not null default `'PENDING'` | `PENDING` \| `PROCESSED` \| `FAILED`. |
| `attempts` | `int` not null default `0` | dispatch attempts so far; drives backoff and the FAILED cutoff. |
| `next_attempt_at` | `timestamptz` not null default `now()` | earliest time the relay may re-pick this row (backoff schedule). Poller filters `next_attempt_at <= now()`. |
| `last_error` | `varchar(1024)` null | truncated last failure reason for diagnostics. **Redacted — no PII, no stack trace** (ADR-0008 §5.2). |
| `processed_at` | `timestamptz` null | when it reached PROCESSED (or terminal FAILED). |
| `version` | `bigint` | optimistic lock (BaseEntity). |
| BaseEntity audit/soft-delete columns | — | `created_at/by`, `updated_at/by`, `deleted` etc. The poller does **not** soft-delete; a separate retention job hard-purges PROCESSED rows older than N days (§"operability"). |

**Indexes** (this table is hot — poll every second):
- `idx_outbox_due (status, next_attempt_at)` — the **relay's only query path**: `status='PENDING' AND next_attempt_at <= now()`. Partial index `WHERE status='PENDING'` keeps it tiny as PROCESSED rows accumulate before purge.
- `idx_outbox_aggregate (aggregate_type, aggregate_id, occurred_at)` — replay/diagnostics by aggregate.

> **Migration number.** `V97__common_outbox_event.sql` — the next free number after the cross-cutting tail (`…V94, V95, V96`); the outbox is shared-kernel, not a feature module, so it sits with the other cross-cutting late migrations rather than in a per-module range (ARCHITECTURE §4.1). Forward-only, with the mandatory SQL comments (CLAUDE.md §8). `ddl-auto=validate` — the `OutboxEvent` entity must match this exactly.

### 2. `OutboxWriter.append(...)` — called in the SAME `@Transactional` as the domain change (the atomicity guarantee)

A thin `@Service` in `common.outbox` with **no transaction of its own** (it joins the caller's). The producer, **already inside its application-service `@Transactional`**, calls `outboxWriter.append(envelope)` right after the domain mutation; both rows commit (or roll back) together.

```java
public interface OutboxWriter {
    /**
     * Persists one PENDING outbox row in the CALLER'S current transaction (Propagation.MANDATORY).
     * MUST be invoked inside the same @Transactional as the domain change so the event and the
     * domain row commit atomically (ADR-0008; PRD §15 DI3) — a crash can never leave the row
     * written but the event lost. The payload MUST contain ids/codes only, never PII (PRD §18).
     */
    void append(EventEnvelope<?> event);
}
```

- The impl is annotated `@Transactional(propagation = Propagation.MANDATORY)` — if a careless caller invokes it **outside** a transaction, it **fails loudly** rather than silently writing a non-atomic event. This makes the atomicity contract mechanically enforced, not documented hope.
- It serialises `event.payload()` to JSONB via the shared `ObjectMapper`, sets `status=PENDING`, `attempts=0`, `next_attempt_at=now()`, `occurred_at=event.occurredAt()`, and saves. Nothing more — **the producer's thread does no dispatch** (the citizen path stays fast — PRD §15).
- WHY a writer service and not the producer `new`-ing an entity: keeps the table/columns and the PENDING invariant in one place (DRY), and keeps `OutboxEvent` out of feature modules' imports (they depend only on `common.outbox.OutboxWriter` + `EventEnvelope`).

### 3. `OutboxRelay` — the `@Scheduled` poller: read PENDING → dispatch → mark PROCESSED (at-least-once)

A `@Component` in `common.outbox`, driven by `@Scheduled(fixedDelayString="${taarifu.outbox.poll-interval-ms:1000}")`. One poll cycle, in its **own per-batch transaction**:

1. **Claim a batch.** `SELECT … WHERE status='PENDING' AND next_attempt_at <= now() ORDER BY occurred_at, id LIMIT :batchSize FOR UPDATE SKIP LOCKED`. `FOR UPDATE SKIP LOCKED` makes the poller **safe to run on multiple app instances** (horizontal scale, ARCHITECTURE §10) — each instance claims disjoint rows, no double-dispatch from concurrency (only the crash window in step 3 causes a redelivery).
2. **Dispatch each row** to the in-process `EventDispatcher` (§4), which fans it to every `DomainEventHandler` registered for that `event_type`.
3. **On success:** `status=PROCESSED`, `processed_at=now()`.
   **On handler exception:** `attempts++`; if `attempts >= maxAttempts` (default **8**) → `status=FAILED`, `last_error=<redacted>`, `processed_at=now()` (terminal — our **DLQ is the FAILED rows**, queryable + alertable, no separate broker DLQ needed at this scale); else stay `PENDING` and set `next_attempt_at = now() + backoff(attempts)`.
4. **Backoff** = exponential **with jitter** (PRD §21 DI3): `min(base * 2^attempts, cap) ± rand`, e.g. base 2s, cap 5min. Jitter prevents a thundering-herd retry of a batch that failed on a shared dependency.

> **WHY a poller and not `@TransactionalEventListener(AFTER_COMMIT)`:** the after-commit listener loses the effect if the JVM dies in the post-commit window (force 1) and gives no retry/DLQ. The poller reads **committed** rows, so it is crash-safe and retryable by construction. The cost — up to `poll-interval` latency before a handler runs — is acceptable for fan-out/routing (PRD §15 budgets the citizen *write*, not the downstream effect). *(insight: the legacy systems fired side-effects inline on the request thread and lost them on any downstream failure — the exact failure this design removes.)*

> **At-least-once, not exactly-once delivery.** The crash window between "handler ran" (step 2) and "row marked PROCESSED" (step 3 commits) means a handler can be invoked **twice** for one row. We accept this and require **idempotent handlers** (§4) — far simpler than two-phase commit across the dispatch (KISS).

### 4. `EventEnvelope` + `DomainEventHandler` SPI + `EventDispatcher` (the in-process bus)

**`EventEnvelope<P>`** — the immutable record the producer builds and the relay reconstructs; it is what crosses the boundary (it wraps the `<module>.api.event` payload record, which stays the public contract):

```java
public record EventEnvelope<P>(
        UUID eventId,          // == outbox public_id; the IDEMPOTENCY KEY handlers dedup on
        String eventType,      // taxonomy key, e.g. "ANNOUNCEMENT_PUBLISHED"
        String aggregateType,  // "ANNOUNCEMENT"
        UUID aggregateId,      // aggregate public id (opaque UUID — ADR-0013)
        P payload,             // the api.event record — ids/codes/enums ONLY, NO PII (PRD §18)
        Instant occurredAt) {}
```

**`DomainEventHandler` SPI** — handlers register **by `eventType`** (ISP: one handler per concern, kept small):

```java
public interface DomainEventHandler {
    /** The event types this handler consumes; the dispatcher routes by exact match. */
    Set<String> handledEventTypes();
    /**
     * Apply the side-effect IDEMPOTENTLY. MUST be safe to call more than once for the same
     * envelope.eventId() (at-least-once delivery — ADR-0014 §3): use the eventId as a dedup key,
     * or make the write a natural upsert/conditional-insert. MUST NOT read PII from the payload
     * (there is none) and MUST NOT call back synchronously into the producing module's domain —
     * cross-module reads go through the callee's *QueryApi (ADR-0013).
     * A thrown exception → the relay retries (backoff) and eventually FAILS the row.
     */
    void handle(EventEnvelope<?> event);
}
```

**`EventDispatcher`** — a `@Component` that, at startup, builds a `Map<String, List<DomainEventHandler>>` from all `DomainEventHandler` beans Spring injects (handlers self-register by returning their `handledEventTypes()`). `dispatch(envelope)` looks up handlers for `envelope.eventType()` and invokes each. An event with **no** registered handler is **dispatched to zero handlers and marked PROCESSED** (a published event need not have a consumer yet — additive, decoupled). The dispatcher is **in-process** — no serialization across a network, no broker — but the relay is the only thing that calls it, so swapping the dispatcher for a real bus later (revisit trigger) touches one seam.

**Idempotency keys (the contract, restated for clarity).** The **`eventId` (== outbox `public_id`)** is the canonical idempotency key. Two patterns satisfy the handler contract:
- **Dedup table / processed-marker:** the handler records `(eventId, handlerName)` in its own module on success and no-ops if already present (per-handler, since one event fans to several handlers).
- **Naturally idempotent write:** an upsert keyed on a business key (e.g. a `notification (recipient, source_event_id)` unique constraint; a `responder_assignment` whose single-OWNER unique guard already exists — ADR-0013 §4a). Preferred where the schema already enforces uniqueness — no extra table.

### 5. First event taxonomy to wire (two flows — proves producer + bus + idempotent handler end-to-end)

This increment wires exactly **two** event types to validate the substrate; the rest of ADR-0013 §2's list (SLA clocks, search/analytics, moderation takedown, async rewards) is now **unblocked** and follows the identical pattern as separate increments.

**(a) `ANNOUNCEMENT_PUBLISHED` → notification + feed fan-out.**
- *Producer (communications):* in the `Announcement` publish application-service, inside the existing `@Transactional`, after the status→`PUBLISHED` write, build `EventEnvelope<AnnouncementPublished>(aggregateType="ANNOUNCEMENT", aggregateId=announcementPublicId, eventType="ANNOUNCEMENT_PUBLISHED", payload=…, occurredAt=publishedAt)` and `outboxWriter.append(it)`. Replaces the `// TODO(wiring): … written to the outbox in the same transaction as the publish` marker on `AnnouncementPublished`.
- *Handler (communications `FeedFanoutHandler`, `NotificationDispatchHandler`):* on `handle`, match followers by `audienceAreaIds`/`audienceRole`/`categoryId`, insert FeedItems and queue per-channel Notifications. **Idempotent** via a unique `(recipient_id, source_event_id)` on FeedItem/Notification — a redelivered event upserts, never double-notifies. Channels (PUSH/SMS/EMAIL) themselves go out through the `communications` ports (ADR-0004), each with its own degradation (ARCHITECTURE §7) — the handler only enqueues.

**(b) `REPORT_ROUTED` → responders OWNER `ResponderAssignment` (D21).**
- *Producer (reporting):* on report create/categorize, inside the report-create `@Transactional`, after the `Report` row and its category are set, `outboxWriter.append(EventEnvelope<ReportRouted>(aggregateType="REPORT", aggregateId=reportPublicId, eventType="REPORT_ROUTED", payload={reportId, categoryId, wardId/constituencyId}, occurredAt))`. **No PII** — ids only; the responder side re-reads the report via `reporting.api.ReportQueryApi` (ADR-0013 §1). `ReportRouted` is a **new record in `reporting.api.event`** (ids/codes only) authored by the reporting owner in their increment; this ADR fixes its shape and `eventType`.
- *Handler (responders `RoutingHandler`):* on `handle`, evaluate `RoutingRule` for the report's `categoryId` + area, and create the **OWNER** `ResponderAssignment` (D21). **Idempotent** via the **existing single-OWNER guard** (a unique constraint ensuring one OWNER per report — ADR-0013 §4a): a redelivered `REPORT_ROUTED` attempts the same OWNER insert and the unique constraint makes the second a no-op/caught-conflict. The handler then `outboxWriter.append(ResponderAssignedEvent)` (the existing record) so a reporting handler can set `Report.ownerResponderId` — closing the loop **asynchronously**.

> **No synchronous reporting → responders cycle (ADR-0013 §1).** Routing is the canonical reason the bus exists: `reporting` **emits** `REPORT_ROUTED` and returns; the `responders` `RoutingHandler` reacts **asynchronously**. There is **no synchronous edge from `reporting` to `responders`**, so the dependency graph stays acyclic (the `ModuleBoundaryTest` slice cycle-check stays GREEN). The reverse read direction (`responders → reporting` category/report validation) is the **synchronous `*QueryApi`** call from ADR-0013 §4a — different direction, different synchronicity, no cycle.

### Ordering caveats (state explicitly — do not over-promise)

- **No global ordering.** The relay orders a batch by `(occurred_at, id)`, but with `SKIP LOCKED` across instances and per-event retry/backoff, **delivery order is best-effort, not guaranteed** — a retried event can land after a later one. Handlers must **not** assume ordering across different events.
- **Per-aggregate ordering is not guaranteed either** in this MVP (we do not serialize by `aggregate_id`). The two wired flows are **order-insensitive** by design: fan-out is a set-union (idempotent upsert), OWNER assignment is a single idempotent insert. If a future flow needs per-aggregate ordering (e.g. report status transitions that must apply in sequence), add a **per-`aggregate_id` claim lock** in the relay (a documented revisit trigger) — do **not** retrofit ordering onto handlers.
- **At-least-once + idempotent ⇒ correct without ordering** for the chosen taxonomy. This is the deliberate KISS trade: idempotency buys us out of the much harder ordering+exactly-once problem a broker would impose anyway.

## Consequences

- (+) **ADR-0008's atomicity promise is real:** domain + event commit in one tx (`Propagation.MANDATORY` enforces it); a crash never drops fan-out or routing. The citizen's write commits fast and is never rolled back by a downstream effect (PRD §15 DI3).
- (+) **ADR-0013 §2 is unblocked.** Every parked event-driven `// TODO(wiring)` (routing OWNER assignment, fan-out, SLA clocks, search/analytics, moderation takedown, async rewards) can now be implemented against a concrete `OutboxWriter` + `DomainEventHandler` SPI, each as its own small increment, without re-litigating boundaries. The three pre-shaped event records (`AnnouncementPublished`, `ResponderAssignedEvent`, `TokenSpent`) get their durable delivery.
- (+) **KISS / low ops.** One table, one `@Scheduled` bean, one in-process dispatcher — **no broker to run, secure, or place in-country** (no new residency surface — PRD §18). `FOR UPDATE SKIP LOCKED` already makes it horizontally safe across app instances.
- (+) **Extract-to-service survives (ARCHITECTURE §10).** The `EventDispatcher` is the single seam: when notifications/feed fan-out exceeds in-process throughput (PRD §16 trigger), the relay publishes to a real broker (RabbitMQ→Kafka) instead of the in-process dispatcher; the `OutboxWriter`/`EventEnvelope`/`DomainEventHandler` contracts and every producer/handler stay unchanged.
- (+) **Privacy by construction.** Payloads are ids/codes/enums only; `last_error` is redacted — the outbox is replay/dump-safe (PRD §18, CLAUDE.md §12).
- (−) **At-least-once means handlers must be idempotent** — a real, recurring discipline (every handler needs a dedup key or a naturally-idempotent write). Accepted: it is strictly simpler than exactly-once delivery, and the two wired flows already have the uniqueness constraints (`(recipient, source_event_id)`; single-OWNER) to lean on.
- (−) **Up-to-`poll-interval` (≈1s) latency** before a handler runs, and **no ordering guarantees**. Accepted for fan-out/routing (PRD §15 budgets the citizen write, not the async effect); the chosen taxonomy is order-insensitive.
- (−) **A hot, growing table.** Mitigated by the partial PENDING index (relay query stays small) and a retention purge of old PROCESSED rows. FAILED rows persist as the DLQ and are alerted on (queue depth / FAILED count → ARCHITECTURE §9 alerts).
- **Revisit triggers:** (a) **fan-out volume / independent scaling need** (PRD §16) → swap the in-process `EventDispatcher` for a broker behind the same SPI, relay becomes a publisher; (b) **a flow needs per-aggregate ordering** → add per-`aggregate_id` claim-locking in the relay (not in handlers); (c) **FAILED-row volume or replay needs grow** → add a small ops endpoint/console to re-queue FAILED rows (reset to PENDING) and a richer DLQ; (d) **poll latency shows on a hot path** → drop `poll-interval` and/or wake the relay on append via an in-JVM signal, table unchanged.

## Decision summary

- **`outbox_event` table (migration `V97`, `common.outbox.OutboxEvent extends BaseEntity`):** `aggregate_type`, `aggregate_id (uuid)`, `event_type`, `payload (jsonb — ids/codes/enums ONLY, NO PII)`, `occurred_at`, `status (PENDING|PROCESSED|FAILED)`, `attempts`, `next_attempt_at`, `last_error (redacted)`, `processed_at`. Partial PENDING index on `(status, next_attempt_at)`; `ddl-auto=validate`.
- **`OutboxWriter.append(EventEnvelope)`** — `@Transactional(Propagation.MANDATORY)`, runs in the **producer's** transaction → domain row + event commit atomically (ADR-0008).
- **`OutboxRelay`** — `@Scheduled` poller: claim PENDING batch `FOR UPDATE SKIP LOCKED` (multi-instance safe) → dispatch → PROCESSED; on failure `attempts++` + exponential backoff-with-jitter, FAILED after **8** attempts (FAILED rows = the DLQ). **At-least-once.**
- **`EventEnvelope` + `DomainEventHandler` SPI (register by `eventType`) + in-process `EventDispatcher`** — handlers are **idempotent** keyed on `eventId (== outbox public_id)`; **no synchronous callback into the producer** (cross-module reads via `*QueryApi`, ADR-0013).
- **First taxonomy wired:** **`ANNOUNCEMENT_PUBLISHED`** → communications fan-out (notification + feed; idempotent via `(recipient, source_event_id)`); **`REPORT_ROUTED`** → responders OWNER `ResponderAssignment` (D21; idempotent via the existing single-OWNER guard). **No synchronous `reporting → responders` cycle** — routing is async via the outbox (ADR-0013). Ordering is **best-effort, not guaranteed**; correctness rests on idempotency.
