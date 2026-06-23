# Taarifu Backend — Foundation Architecture

> **Status:** Accepted (foundation increment). **Owner:** Solution Architect.
> **Scope:** the modular-monolith backend (`/backend`). DESIGN ONLY — this document is the contract the backend engineers build to.
> **Grounding:** PRD §6 (actors), §7 (RBAC/tiers), §9 + §9.0 (domain & geography), §16 (architecture), §17 (API), §18 (security), §21 (integrations), §23 (tokens), §24 (responders); CLAUDE.md (engineering rules).
> **Companion docs:** [FOUNDATION-SCOPE.md](FOUNDATION-SCOPE.md) (what to build this increment) and [`../adr/`](../adr/) (the decisions behind every choice here).

---

## 0. One-paragraph summary

Taarifu's backend is a **modular monolith** in **Spring Boot 3.3.x on Java 21**, packaged **by feature** under `com.taarifu.*`, over **PostgreSQL + PostGIS** with **Flyway-owned schema** (`ddl-auto=validate`). Each module is a vertical slice with a **four-layer internal structure** (`api → application → domain → infrastructure`) and a small **public API package**; modules never reach into each other's internals. All external systems (NIDA/voter, SMS, USSD, push, email, object store, geocoder, search, content-safety, payments) sit behind **ports** in a `domain.port` package with **adapter** implementations in `infrastructure.adapter` and a **stub/sandbox** adapter for every one of them. Every entity extends a shared **`BaseEntity`** (internal `Long` PK, public `UUID`, audit, soft-delete, optimistic-lock) and gets a human-readable code from a **DB sequence** where users see it. Every HTTP response — success or error — uses the **single `ApiResponse<T>` envelope**. Security is **stateless JWT** (short access + rotating refresh) with **deny-by-default, method-level RBAC**, **scoped roles**, and **trust tiers T0–T3** enforced server-side. Side-effecting work leaves the request via a **transactional outbox → event bus → workers** so a provider outage never rolls back the citizen's transaction. The seams are drawn so high-load modules (notifications, feed, search, reporting) can be **extracted into services later** without a rewrite.

---

## 1. Architectural style & drivers (PRD §16, §15)

**Style: modular monolith** (one deployable, strict internal boundaries) — not microservices. KISS over premature distribution (CLAUDE.md §3); the prior repos failed on copy-paste and absent boundaries, not on being a monolith.

Drivers that shape every decision below:

| Driver | PRD | Architectural consequence |
|---|---|---|
| National scale, p95 < 500ms reads / < 1s writes | §15 | Async fan-out via outbox/bus; caching (Redis); indexed read models for feed/search/reports. |
| 99.9% availability, no hard dependency on the mobile path | §15, §21 DI2 | Every adapter has a **degradation mode**; ports isolate vendor outages from domain. |
| Feature phones, patchy 2G/3G, tight data budget | §14, §15 | Small envelopes, pagination, delta-friendly contracts; SMS used sparingly; USSD session state in Redis. |
| PDPA 2022/2023; PII (NIDA/voter) encrypted at rest | §18, §15 | Field-level (envelope) encryption via KMS port; PII redacted from logs; pseudonymised analytics. |
| Extract high-load modules later | §16 | Clean module public APIs + events; no shared mutable tables across module boundaries. |
| One source of truth per concept (DRY) | CLAUDE.md §3 | Shared kernel (`common`) owns BaseEntity, envelope, errors, pagination, codes, audit. |

---

## 2. Target tech stack & pinned versions (PRD §5; CLAUDE.md §5)

> Pin concrete versions; the BOM owns transitive versions. Bumps go through an ADR-superseding note or a `build` commit referencing this table.

| Concern | Choice | Pinned version |
|---|---|---|
| Language / runtime | Java (LTS) | **Java 21** (toolchain `21`) |
| Framework | Spring Boot | **3.3.5** (Spring Framework 6.1.x, manages Spring Security 6.3.x, Spring Data JPA) |
| Build | Maven | **3.9.x** wrapper (`mvnw`) committed |
| Database | PostgreSQL | **16.x** + **PostGIS 3.4** |
| Migrations | Flyway | **10.x** (Spring Boot-managed; `flyway-database-postgresql`) |
| Persistence | Hibernate (via Spring Data JPA) | Boot-managed; `ddl-auto=validate` |
| Cache / OTP / idempotency / USSD state | Redis (Spring Data Redis / Lettuce) | **7.x** server |
| Object storage | S3-compatible (AWS SDK v2) | `software.amazon.awssdk:s3` **2.28.x** |
| JWT | `com.nimbusds:nimbus-jose-jwt` (or Spring Authorization Server resource-server) | **9.40** |
| API docs | springdoc-openapi (Swagger UI) | **2.6.0** |
| Mapping | MapStruct | **1.6.x** |
| Boilerplate | Lombok (sparingly — CLAUDE.md §8) | **1.18.34** |
| Tests | JUnit 5 + Testcontainers (postgres) + Spring Boot Test + REST Assured | JUnit **5.10**, Testcontainers **1.20.x** |
| Observability | Micrometer + OpenTelemetry + Actuator | Boot-managed |
| Resilience | Resilience4j (circuit-breaker, retry, timeout, bulkhead) | **2.2.x** |

A Maven **parent POM** (`com.taarifu:taarifu-backend`) pins these; a single multi-module Maven project (one module per package-by-feature module) keeps boundaries compile-checked (see §3.4).

---

## 3. Module map & boundaries (PRD §16)

### 3.1 The modules

Each module owns its tables, its domain, its public API, and its events. Modules communicate **only** through (a) another module's published **public API package** (synchronous, in-process), or (b) **domain events** (asynchronous, via the bus). Never via each other's entities, repositories, or `infrastructure`.

| Module (`com.taarifu.*`) | Responsibility | Phase | Key PRD |
|---|---|---|---|
| `common` | **Shared kernel.** BaseEntity, `ApiResponse` envelope, error model + global handler, pagination, code generation, audit infra, security primitives, port-neutral exceptions, base test fixtures. **Depends on nothing else.** | MVP | §9, §17, §18 |
| `identity` | User account, Profile, ProfileLocation, Role/RoleAssignment, trust tier, verification requests, sessions/refresh tokens, audit log subject. | MVP | §6, §7, §9.1 |
| `geography` | Region→District→Council/LGA→[Division]→Ward→Village/Mtaa→Hamlet; Constituency; effective-dated WardConstituency; LocationType; closure-table hierarchy; GPS→ward resolution (PostGIS). | MVP | §9.0, §9.1 |
| `institutions` | PoliticalParty, Parliament/ParliamentRole, Representative (MP/Councillor/exec), mandate, legislature. | MVP (profiles) | §9.1 |
| `reporting` | IssueCategory, Report (ticket), CaseEvent, Assignment, SLAClock, Escalation, RoutingRule, ResponderAssignment (owner+collaborators). | MVP | §9.1, §12.1, §24, §25.2 |
| `engagement` | Petition + Signature, Survey/Poll + Question/Option/Response, Q&A Question, Comment/Discussion. | P2 (seam now) | §9.1, §12.2 |
| `accountability` | RepresentativeContribution, Attendance, Promise, Project + Milestone/Update, Rating. | P2 (seam now) | §9.1 |
| `communications` | Announcement, FeedItem, Subscription/Follow, Notification, NotificationPreference; feed fan-out workers. | MVP | §9.1, §13 |
| `responders` | Responder directory (govt/parastatal/private), Responder staff scope, B2B/provider workspace, data-sharing/consent. (Routing + ResponderAssignment live in `reporting`; the *directory* lives here.) | MVP (govt/parastatal) | §24 |
| `tokens` | Wallet, TokenTransaction ledger, ActionCost/FreeQuotaPolicy, TokenReward; metering + free-quota enforcement; Payment/TokenPackage (P2 seam). | MVP (free) | §23 |
| `moderation` | Flag/Report-abuse, moderation queues, takedown/appeal, content-safety pipeline, verification review (operator-assisted). | MVP (basic) | §18, §25.8 |
| `admin` | Reference-data administration, taxonomy/SLA config, role granting, feature flags/AppConfig, bulk import, system configuration. | MVP | §6, §19 |

> `notifications`, `feed`, `search` are **logical sub-concerns inside `communications`** at MVP (one module), but with their **own port + worker seam** so each can be lifted out into a service later (PRD §16 extract-to-service list). See §10.

### 3.2 Dependency rule (the boundary contract)

```
                         ┌─────────────┐
                         │   common    │  (shared kernel — depends on NOTHING)
                         └──────▲──────┘
        ┌───────────┬──────────┼───────────┬────────────┬───────────┐
   geography     identity   tokens     moderation     admin      institutions
        ▲             ▲         ▲            ▲                         ▲
        │             │         │            │                         │
        └──────┬──────┴────┬────┴─────┬──────┴─────────┬───────────────┘
            reporting   communications   responders   engagement   accountability
            (depends on geography, identity, tokens, responders, institutions
             — via their PUBLIC APIs and events only)
```

Rules (enforced — see §3.4):
1. `common` depends on no other Taarifu module.
2. Foundation modules (`geography`, `identity`, `institutions`, `tokens`, `moderation`, `admin`) depend on `common` and may publish events, but do **not** depend on the higher feature modules.
3. Feature modules (`reporting`, `engagement`, `accountability`, `communications`, `responders`) may depend on foundation modules' **public API packages** only.
4. **No cycles.** If two modules need to react to each other, they do it via **events**, not bidirectional dependencies.
5. A module exposes a small **`api`** package (public DTOs, public service interfaces, published event records). Everything else (`domain`, `infrastructure`) is **internal** and never imported across module lines.

### 3.3 Internal layering (every module)

Each feature module is itself layered. Dependencies point **inward**: `api → application → domain ← infrastructure`. The domain depends on nothing framework-specific except JPA annotations on entities (pragmatic KISS); ports are plain interfaces.

```
com.taarifu.<module>
├── api/                 PUBLIC. The module's contract to the outside:
│   ├── controller       REST controllers (thin; validate, delegate, wrap in ApiResponse)
│   ├── dto              request/response DTOs (never expose entities — CLAUDE.md §8)
│   └── event            published domain-event records (other modules subscribe)
├── application/         use-case orchestration (transaction boundary lives here)
│   ├── service          application services (constructor-injected, @Transactional)
│   └── mapper           MapStruct entity<->DTO mappers
├── domain/              the model + contracts; framework-light
│   ├── model            JPA entities (extend BaseEntity) + enums + value objects
│   ├── repository       Spring Data repository interfaces (ports to persistence)
│   └── port             outbound PORTS to external systems (interfaces only)
└── infrastructure/      adapters & wiring
    ├── adapter          port implementations (vendor SDK calls live ONLY here)
    ├── config           module @Configuration, bean wiring
    └── persistence      custom query impls, projections, native/PostGIS queries
```

**Why this layering** (PRD §16; CLAUDE.md §3 SOLID): controllers stay dumb (no business logic — CLAUDE.md §8); the application layer owns transactions and orchestration; the domain is testable without Spring; vendor SDKs are quarantined in `infrastructure.adapter` behind `domain.port` interfaces so a provider swap (DI1, DI7) never touches domain or application code.

### 3.4 Enforcing boundaries (mechanical, not just convention)

- **Maven multi-module:** one Maven module per feature module. A module's POM declares dependencies only on the modules §3.2 permits, so an illegal import **fails the build**.
- **ArchUnit tests** in `common` (shared test base) assert: no module imports another module's `domain`/`infrastructure`; `api.controller` has no `@Transactional`; entities don't leak past `api`; `domain.port` interfaces have no vendor imports.
- **`-Werror`-style review gate:** any new cross-module dependency requires an ADR note (CLAUDE.md §2 design-first).

---

## 4. Persistence conventions (PRD §9, §17; CLAUDE.md §8)

### 4.1 Schema ownership — Flyway + `validate`

- **Flyway owns the schema.** `spring.jpa.hibernate.ddl-auto=validate` — Hibernate never creates or alters tables; on startup it only **validates** that entities match the Flyway-migrated schema (PRD §5, §16). A mismatch fails fast.
- Migrations live in `src/main/resources/db/migration`, named `V<NNN>__<module>_<change>.sql` (e.g. `V002__geography_hierarchy.sql`). **Forward-only**; never edit an applied migration — add a new one.
- **Every migration carries SQL comments** documenting the change and the "why" (CLAUDE.md §8 mandatory documentation).
- Migration numbering is **range-partitioned per module** to avoid merge collisions: `V0xx` shared/baseline, `V1xx` identity, `V2xx` geography, `V3xx` institutions, `V4xx` reporting, `V5xx` communications, `V6xx` responders, `V7xx` tokens, `V8xx` moderation, `V9xx` engagement/accountability, `V95x` admin. (Documented in FOUNDATION-SCOPE.md.)
- `CREATE EXTENSION IF NOT EXISTS postgis;` and the audit/code sequences land in the **baseline** migration (`V001`).

### 4.2 BaseEntity (the shared base — fixes the legacy `id+uid+code` triple)

Every persistent entity extends `BaseEntity` (in `common.domain.model`). It provides:

| Field | Type | Purpose / PRD |
|---|---|---|
| `id` | `Long` (IDENTITY/sequence) | **internal** surrogate PK; used for FKs and joins. Never exposed in APIs. |
| `publicId` | `UUID` (v7/ULID-ordered), unique, not null | **public id** in URLs/DTOs (PRD §17 — never sequential). Generated on persist. |
| `version` | `Long` (`@Version`) | optimistic locking (PRD §9, §17 optimistic concurrency). |
| `createdAt` / `createdBy` | `Instant` / `UUID` | audit (PRD §9; populated by `AuditorAware` + JPA auditing). |
| `updatedAt` / `updatedBy` | `Instant` / `UUID` | audit. |
| `deleted` / `deletedAt` / `deletedBy` | `boolean` / `Instant` / `UUID` | **soft-delete** (PRD §9). Default repository queries filter `deleted = false` via `@SQLRestriction`. |

**Human-readable codes** are a separate, opt-in concern (not on every entity): entities users see by code (e.g. `Report` ticket `TAR-2026-000123`) get a `code` column populated from a **DB sequence** via a `CodeGenerator` service in `common` (e.g. `TAR-<year>-<6-digit zero-padded seq>`). UUID = machine/public id; code = human display id. This replaces the prior parallel `id/uid/code` mess (PRD §6.3) with one disciplined pattern.

### 4.3 FK & geography specifics (PRD §9.0)

- **Real foreign keys** everywhere (fixes the legacy loose `Long constituencyId` — PRD §6.3). FKs reference the internal `id`.
- **Admin hierarchy** uses a **closure table** (`location_closure(ancestor_id, descendant_id, depth)`) over a single `location` table discriminated by `LocationType {REGION, DISTRICT, COUNCIL, DIVISION, WARD, VILLAGE, MTAA, HAMLET}` — so "all descendants of a District" is one indexed query at national scale (PRD §9.0, §9.1).
- **`WardConstituency`** bridge is **effective-dated** (`effectiveFrom`, `effectiveTo` nullable) so re-delimitation never corrupts history (PRD §9.0, EI-14). Resolution always reads "the mapping effective at date D".
- **GPS→ward** is an **in-house PostGIS point-in-polygon** query against seeded ward boundaries — the **source of truth**, vendor-independent (PRD §9.0, EI-7). Boundary geometry is optional per row; absence degrades to manual ward drill-down.
- **PII columns** (`Profile.idNo`, etc.) are **field-level encrypted** via the `CryptoPort` (envelope encryption, KMS — EI-19, §18); the column stores ciphertext; a separate **blind-index hash** column on `(idType,idNo)` enables dedup lookups (D15) without decrypting.

### 4.4 Naming

- Tables/columns `snake_case`; entities `PascalCase`; Swahili civic terms preserved in the domain where they are the real name (`Region`/Mkoa, `Ward`/Kata, `Constituency`/Jimbo, `Representative`/Mbunge–Diwani) — see CLAUDE.md §8 and the tanzania-domain-expert.

---

## 5. API surface: the single envelope, errors, pagination (PRD §17)

### 5.1 `ApiResponse<T>` — one envelope for everything

All responses (success **and** error) use one shape (PRD §17 — fixes the prior 3 inconsistent envelopes). Lives in `common.api.dto`.

```json
{
  "success": true,
  "code": "OK",
  "message": "Imefaulu",
  "data": { },
  "meta": { "page": 0, "size": 20, "total": 137, "totalPages": 7 },
  "timestamp": "2026-06-23T09:00:00Z"
}
```

- `success` — boolean.
- `code` — **stable machine code** (`OK`, `VALIDATION_FAILED`, `TIER_TOO_LOW`, `NOT_FOUND`, `CONFLICT`, `RATE_LIMITED`, …) — clients branch on this, not on the message.
- `message` — **localised** (SW default, EN) human text (PRD §17, D-Q10).
- `data` — the payload (object, or a list page's content); `null` on error.
- `meta` — pagination/extra; `null` when not paginated.
- `timestamp` — server `Instant`, ISO-8601 UTC.

A `ResponseFactory` in `common` builds success/error/paged responses; controllers never hand-build the envelope.

### 5.2 Error model

- Domain throws typed exceptions extending `common.error.ApiException(ErrorCode code, Object... messageArgs)`; `ErrorCode` is an enum mapping → HTTP status + machine `code` + i18n message key.
- A single `@RestControllerAdvice` **`GlobalExceptionHandler`** in `common` translates every exception (including Bean Validation `MethodArgumentNotValidException`, auth/authz failures, optimistic-lock, not-found) into the envelope with **correct HTTP status** and **field-level validation details** in `data.errors[]` (PRD §17).
- **No stack traces or PII** ever reach the client or the logs unredacted (PRD §18).

### 5.3 Pagination, sorting, filtering

- Standard query params `page` (0-based), `size` (default 20, cap 100), `sort` (`field,asc|desc`), `q` (free-text), plus per-resource `filter` params (PRD §17).
- Paged endpoints return `data` = list + `meta` = `{page,size,total,totalPages}`. A `PageMapper` in `common` turns Spring `Page<T>` into the envelope.

### 5.4 Versioning, idempotency, concurrency

- All endpoints under **`/api/v1`** (PRD §17); plural resource nouns; public **UUID** path ids.
- **Idempotency:** create/submit endpoints (reports, signatures, OTP, payments) accept an `Idempotency-Key` header; keys stored in Redis (TTL) → duplicate replays return the original result (PRD §17, DI4).
- **Optimistic concurrency:** updates carry the `version` (header `If-Match` / body field); a stale version → `409 CONFLICT` envelope (PRD §17).
- **OpenAPI** generated by springdoc, served at `/api/v1/openapi.json` + Swagger UI; the spec is committed to `/docs/api/` and **contract-tested** against clients (CLAUDE.md §10).

---

## 6. Security model (PRD §7, §18; CLAUDE.md §3)

### 6.1 Authentication

- **Stateless JWT.** Short-lived **access token** (~15 min) + **rotating refresh token** (~30 days, single-use, rotated on every refresh, family-revocation on reuse-detection). Refresh tokens are persisted hashed (`identity` `RefreshToken` entity) so they can be revoked; access tokens are not stored (PRD §17, §18).
- Login by **phone/email + password or OTP** (PRD §18). OTP store + attempt counters in Redis with lockout/backoff (PRD §18, US-0.1). Optional **TOTP MFA** for staff (PRD §18, EI-15).
- **No secrets in source.** Signing keys, KMS, provider creds all from env/secret manager (PRD §18, DI5; CLAUDE.md §12). Asymmetric (RS256/ES256) signing so verification keys can be published and rotated.

### 6.2 Authorization — deny-by-default, method-level, scoped, tiered

- **Method-level** `@PreAuthorize` on **every** protected endpoint — no "authenticated-only" admin surface (PRD §7.1, §18 — fixes the legacy gaps). Global `@EnableMethodSecurity`; deny by default.
- **RBAC** over the role catalogue (PRD §7.2): `GUEST, CITIZEN, ORGANIZATION_MEMBER/_ADMIN, REPRESENTATIVE, AREA_OFFICIAL` (now `RESPONDER_AGENT/_ADMIN` — §24), `MODERATOR, ADMIN, ROOT`.
- **Scoped roles** (attribute-based): `RoleAssignment` carries `areaIds[]`, `categoryIds[]`, `constituencyId` (PRD §7.1, §9.1). A custom `PermissionEvaluator` / scope-checker enforces "this responder only within assigned areas/categories", "this rep only their own constituency".
- **Trust tiers T0–T3** (PRD §7.3) computed onto the account and carried as a JWT claim **but re-checked server-side** per action (never trust the client claim — PRD §17, §18). A `@RequiresTier(T3)` method annotation + interceptor gates high-stakes actions (sign petition, rate rep, binding poll, create org).
- **Conflict-of-interest guard (D13/D16):** a cross-cutting check blocks self-actions (rate/answer/resolve/moderate self or own work); all multi-hat actions audited (PRD §6.4, §18).
- **Integrity fence (D18):** binding-action authorization paths check **tier + electoral scope + one-per-person only** and **must never read token balance** (PRD §23.5). Enforced and unit-tested in `tokens` + the binding endpoints.

### 6.3 Data protection & transport

- **PII field-level encryption** (envelope, KMS) for national/voter IDs; blind-index for dedup; **PII redacted from logs**; **pseudonymised before analytics** (PRD §18, §15, EI-9, EI-19).
- **TLS everywhere; tight CORS allow-list** (no wildcard-with-credentials — fixes legacy `*`+credentials); secure headers; CSRF strategy suited to the bearer-token model (PRD §18).
- **Immutable audit log** for every state-changing official/admin/moderation action (PRD §18, §15).
- Aligned to **OWASP ASVS**; **PDPA 2022/2023** (right to access/erasure = anonymisation per §25.1).

---

## 7. Ports & adapters for external integrations (PRD §21)

Every external system is a **port** (interface in `<module>.domain.port`) with adapters in `<module>.infrastructure.adapter`, selected by config/feature-flag, each with a **stub/sandbox** adapter and an explicit **degradation mode** (PRD §21 DI1–DI7). **No vendor SDK leaks into domain code** (DI1); **no civic semantics leak into a vendor format** (DI7).

| Port (interface) | Module | Adapters (config-selected) | Degradation (PRD §21) |
|---|---|---|---|
| `IdentityVerificationProvider` | identity | operator-assisted (MVP), NIDA API (later), voter-ID; **stub** | EI-1/2: queue → moderator review; no T1/T2 loss while pending |
| `SmsGateway` | communications | aggregator(s) multi-route least-cost + DLR webhook; **stub** | EI-3: route failover → queue+backoff; OTP→email; alerts→feed/push |
| `UssdGateway` | communications | aggregator session webhook (Redis state); **stub** | EI-4 (P2): SMS-keyword fallback |
| `PushSender` | communications | FCM HTTP v1 / APNs; **stub** | EI-5: no token → SMS fallback; feed always retains item |
| `EmailSender` | communications | transactional ESP (SPF/DKIM/DMARC) + bounce webhook; **stub** | EI-6: queue+retry; prefer SMS/push; signup never blocks |
| `Geocoder` | geography | **internal PostGIS (primary, source of truth)**; external tiles (optional) | EI-7: external down → manual ward drill-down |
| `ObjectStore` + `MalwareScanner` | common/reporting | S3 pre-signed + ClamAV/engine; **stub** | EI-8: quarantine until clean; report accepted regardless |
| `SearchPort` | communications | Postgres FTS (MVP), OpenSearch (later); **stub** | EI-10: external down → Postgres FTS / DB query |
| `ContentSafety` | moderation | ML/hosted (P2); **stub** | EI-18: down → all to human moderators |
| `SpeechToText` | reporting | Swahili STT (optional); **stub** | EI-17: absent → text/photo entry |
| `CryptoPort` (KMS envelope) | common | cloud KMS / Vault; **dev key (stub)** | EI-19: leased keys cached; blocks new PII decrypt only |
| `PaymentProvider` | tokens | M-Pesa/Tigo/Airtel/Halopesa/card (P2 seam); **stub** | §23: free path always available; idempotent reconciliation |

> All synchronous outbound calls run through **Resilience4j** (timeout + circuit-breaker + retry-with-jitter + bulkhead). Open circuit → immediate degraded path, no thread pile-up (PRD §21.4). Per-adapter metrics (success rate, p95, queue depth, circuit state, provider cost) feed dashboards (DI6).

The **stub adapter for every port** lets the whole system run full E2E demos/tests with **zero external calls** — which is what makes region-by-region staged onboarding (D-Q5) and CI possible (PRD §21.4).

---

## 8. Transactional outbox & event-driven flow (PRD §16, §21 DI3)

Side-effecting, cross-module, and integration work is **decoupled from the request transaction**:

```
[HTTP request]
   │  (single DB transaction)
   ├─► write domain rows
   └─► write OUTBOX row (event payload, same tx)   ◄── atomicity: domain + intent commit together
        │
   [commit] ──► HTTP response (fast; PRD §15 p95)
        │
  [OutboxRelay poller / CDC] ──► [Event Bus]  (RabbitMQ at MVP → Kafka at scale)
                                     │
        ┌────────────────────────────┼─────────────────────────────┐
   [Notification worker]      [Feed fan-out worker]        [Search index worker]
   [SLA-clock worker]         [Analytics sink]             [Audit/event-log sink]
```

- **Transactional outbox:** the domain change and the "something happened" event commit in **one transaction**; a relay publishes asynchronously. A provider outage **never rolls back** the citizen's action (DI3, PRD §15 "no hard dependency on the mobile path").
- **Events** are immutable records in `<module>.api.event` (e.g. `ReportSubmitted`, `ReportStatusChanged`, `AnnouncementPublished`, `IdVerified`, `TokenSpent`). They are the **only** async cross-module contract.
- **Workers** are idempotent (idempotency keys, DI4), use **exponential backoff + jitter**, and route poison messages to a **DLQ with alerting** (DI3). Effects are **exactly-once-effect** even under at-least-once delivery.
- **MVP transport:** start with the DB outbox + **RabbitMQ** (or even in-process Spring `ApplicationEventPublisher` for the smallest slices), with the worker interface designed so the bus can be swapped to **Kafka** when fan-out volume demands — no domain change (PRD §16).

This is the machinery behind **feed fan-out, notifications, SLA clocks, search indexing, and analytics** (PRD §16) and the substrate that lets those become services later (§10).

---

## 9. Observability & operability (PRD §15)

- **Structured JSON logs** with trace/span ids (OpenTelemetry) and a correlation id per request; **PII redacted** (PRD §15, §18).
- **Metrics** (Micrometer → `/actuator/prometheus`): RED (rate/errors/duration) per endpoint, USE per resource, **per-adapter** success rate / p95 / queue depth / circuit state / provider cost (DI6).
- **Tracing** end-to-end including outbox→worker spans.
- **Health:** `/actuator/health` (liveness/readiness groups → `/health`, `/live`), DB/Redis/bus checks.
- **Alerts:** 5xx, latency SLO burn, SLA breaches, queue depth, DLQ non-empty, circuit-open, provider spend spikes (PRD §15, §21.4).

---

## 10. Modular-monolith → services evolution (PRD §16)

The monolith is the **right size now** (KISS). The seams are pre-cut so the high-load modules named in PRD §16 — **notifications, feed, search, reporting** — extract cleanly **without a rewrite**:

| Pre-cut seam | Why it enables extraction |
|---|---|
| Module public API package + events as the **only** cross-module contract | A module can move behind a network call; callers already depend on its `api`, not its internals. |
| **Transactional outbox + bus** already the async path | Extracting a worker = pointing it at the bus; producers don't change. |
| **Ports/adapters** for all I/O | An extracted service keeps the same port; only deployment topology changes. |
| **No shared mutable tables across boundaries** | Each module owns its tables → its schema can move to its own DB. |
| **Stateless JWT + resource-server pattern** | An extracted service validates the same tokens; no session affinity. |

**Extract-to-service triggers** (revisit when any hold): notifications fan-out exceeds in-process worker throughput / needs independent scaling; search outgrows Postgres FTS (move `SearchPort` to an OpenSearch service); reporting/feed read load needs isolation from the write path; a module needs an independent deploy cadence. Until a trigger fires, **stay a monolith.**

---

## 11. Canonical backend package layout (`com.taarifu.*`)

> This is the **binding** layout. Every backend engineer follows it (see FOUNDATION-SCOPE.md for the exact files to create this increment).

```
com.taarifu
├── TaarifuApplication.java                 // Spring Boot entry point (documented)
│
├── common/                                 // SHARED KERNEL (depends on nothing else)
│   ├── api/
│   │   ├── dto/        ApiResponse, ErrorDetail, PageMeta
│   │   └── ResponseFactory
│   ├── domain/
│   │   ├── model/      BaseEntity, BaseCodedEntity
│   │   └── port/       CryptoPort, ObjectStorePort, ClockPort
│   ├── error/          ApiException, ErrorCode, GlobalExceptionHandler
│   ├── persistence/    CodeGenerator, JpaAuditingConfig, AuditorAwareImpl, SoftDeleteSupport
│   ├── security/       SecurityPrimitives, CurrentUser, @RequiresTier, ScopeChecker, JwtSupport
│   ├── pagination/     PageRequestFactory, PageMapper
│   ├── i18n/           MessageResolver (SW default, EN)
│   ├── outbox/         OutboxEntry, OutboxRelay, DomainEvent (base), EventPublisher
│   └── config/         OpenApiConfig, JacksonConfig, RedisConfig, ResilienceConfig
│
├── identity/
│   ├── api/{controller,dto,event}
│   ├── application/{service,mapper}
│   ├── domain/{model,repository,port}      // User, Profile, ProfileLocation, Role, RoleAssignment,
│   │                                       //   VerificationRequest, RefreshToken, enums; ports: IdentityVerificationProvider
│   └── infrastructure/{adapter,config,persistence}
│
├── geography/
│   ├── api/{controller,dto,event}
│   ├── application/{service,mapper}
│   ├── domain/{model,repository,port}      // Region…Hamlet, LocationClosure, Constituency,
│   │                                       //   WardConstituency, LocationType; port: Geocoder
│   └── infrastructure/{adapter,config,persistence}  // PostGIS point-in-polygon
│
├── institutions/    (api/application/domain/infrastructure)   // PoliticalParty, Parliament, Representative
├── reporting/       (…)   // IssueCategory, Report, CaseEvent, Assignment, SLAClock, RoutingRule, ResponderAssignment
├── communications/  (…)   // Announcement, FeedItem, Subscription, Notification, NotificationPreference;
│                          //   ports: SmsGateway, UssdGateway, PushSender, EmailSender, SearchPort
├── responders/      (…)   // Responder directory + staff scope (generalises AreaOfficial)
├── tokens/          (…)   // Wallet, TokenTransaction, ActionCost/FreeQuotaPolicy, TokenReward; port: PaymentProvider
├── moderation/      (…)   // Flag, ModerationCase, Appeal, verification review; port: ContentSafety
├── engagement/      (…)   // Petition, Survey/Poll, Question, Comment      [P2 — seam only now]
├── accountability/  (…)   // Contribution, Attendance, Promise, Project, Rating  [P2 — seam only now]
└── admin/           (…)   // reference-data admin, taxonomy/SLA config, role granting, AppConfig, bulk import
```

Resources:
```
src/main/resources
├── application.yml                 // ddl-auto=validate; no secrets (env placeholders)
├── application-{dev,test,prod}.yml
├── db/migration/                   // Flyway V<NNN>__<module>_<change>.sql (range-partitioned §4.1)
└── i18n/messages_sw.properties, messages_en.properties
```

---

## 12. Cross-references

- **What to build this increment:** [FOUNDATION-SCOPE.md](FOUNDATION-SCOPE.md).
- **Decisions behind this design:** [ADR-0001 … ADR-0010](../adr/).
- **Product truth:** [PRD.md](../../PRD.md) (§16, §17, §18, §21, §9.0). **Engineering rules:** [CLAUDE.md](../../CLAUDE.md).
