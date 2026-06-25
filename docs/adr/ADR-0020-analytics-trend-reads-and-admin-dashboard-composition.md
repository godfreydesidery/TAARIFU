# ADR-0020: Analytics time-bucket trend reads, the published `AnalyticsQueryApi` read port, and the admin dashboard composition surface

> Note: numbered 0020 because ADR numbers 0015–0019 were claimed in parallel by sibling Phase-2 Wave-1 increments (payments, PDPA-DSR, search, moderation auto-assist, deployment, USSD) on `feature/phase2-wave1`.

**Status:** Accepted · 2026-06-25 · Backend Engineer (Baraka Mushi), for Phase-2 Wave-1 (M15 read side completion)
**Extends / grounds in:** ADR-0008 (single envelope + transactional outbox), ADR-0013 (cross-module integration — published `api`-package query ports for synchronous reads), ADR-0014 (outbox event bus — analytics is an outbox-fed sink). PRD §3.3 (KPIs), Appendix C (dashboards), Appendix E (measurement plan, no-PII). ARCHITECTURE.md §3.2/§3.4 (module boundaries + `ModuleBoundaryTest`), §6.2 (deny-by-default), §8 (outbox). CLAUDE.md §3 (SOLID/KISS/DRY), §8 (thin controllers, DTOs at the boundary, Javadoc), §12 (guardrails — no PII surfaced).
**Companion (precedent in code):** `tokens.api.TokenLedgerApi`, `reporting.api.ReportQueryApi` (the synchronous read-port shape this ADR follows); `admin.api.spi.ModuleStatsProvider` (the existing dashboard-aggregation seam).

## Context

The analytics **write** side (the append-only `analytics_event` table V91, the idempotent `AnalyticsApi.record` recorder) and the **first slice of the read** side (`AnalyticsQueryService` + `AnalyticsDashboardController` at `/admin/analytics/*`: reports volume, TTFR/TTR percentiles, SLA-breach counts, the T0→T3 funnel, channel mix, engagement counts, moderation actions) already landed on `develop` (commits `0956211`, `c0ac9e6`, `3c3f22a`, `3bb27e4`). Three gaps remain against the M15 mandate and PRD §3.3 / Appendix C:

1. **No time-bucket / trend reads.** Every existing aggregation is a *total or a single-dimension breakdown over the whole window*. PRD §3.3 and Appendix C call for **trends over time** — reports volume per day/week/month and the **SLA-breach trend** — which need grouping by a truncated time bucket. The V91 index `ix_analytics_event_type_time (event_type, occurred_at)` and `ix_analytics_event_geo_cat` already exist precisely for this; nothing consumes them for a series yet.
2. **No published read port for the admin module to compose.** The mandate wants an `/admin/analytics/*` *composition* surface owned by the **admin** module that assembles the dashboard. The `ModuleBoundaryTest` forbids `admin` from importing `analytics.application`/`analytics.domain` — admin may only touch `analytics.api` (ADR-0013). So analytics must publish a read **query port** (the `*QueryApi` shape) for admin to consume; admin cannot call `AnalyticsQueryService` (an internal `application.service`) directly.
3. **A one-call dashboard overview.** The dashboard header needs several KPIs at once; making the Angular admin console fire 8 separate calls on load is wasteful on a low-bandwidth deployment (PRD §15). A single composed overview DTO is the lean payload.

## Decision

### 1. Add time-bucketed **trend** reads in the analytics module (own module only)

- New **`TimeBucket`** enum (`DAY`/`WEEK`/`MONTH`) in `analytics.api.dto` — the controlled granularity, mapped to PostgreSQL `date_trunc('day'|'week'|'month', occurred_at)`. WHY an enum bound to a fixed `date_trunc` field, never the raw request string: it makes SQL-injection structurally impossible on the one part of these queries that is an *identifier*, not a bind parameter.
- New **`TimeSeriesDto`** (`metric`, `bucket`, `from`, `to`, `List<Point{bucketStart, count}>`) in `analytics.api.dto` and a **`CountByBucketProjection`** (`Instant getBucketStart()`, `long getCount()`).
- New **native** repository queries on `AnalyticsEventRepository` (native because `date_trunc` is Postgres-specific, like the existing `percentile_cont` latency query): `countByTypeBucketed(...)` (volume trend, optional area/category filter) and `countSlaBreachesBucketed(...)` (SLA-breach trend). Each takes the `date_trunc` field as a **validated literal** chosen by `TimeBucket`, never caller text.
- New `AnalyticsQueryService` methods `reportsTrend(...)` and `slaBreachTrend(...)` (same 30-day window-defaulting helper, DRY) and two new `AnalyticsDashboardController` GET endpoints `/admin/analytics/reports/trend` and `/admin/analytics/reports/sla-breach-trend`, gated by the existing `DASHBOARD_ROLES` `@PreAuthorize` constant.

### 2. Publish **`analytics.api.AnalyticsQueryApi`** — the synchronous read port (ADR-0013 §1)

- A plain interface in `com.taarifu.analytics.api` (the `*QueryApi` shape, alongside the existing write-side `AnalyticsApi`), returning **only public DTOs/records/enums/`UUID`s** — never an entity, repository, or `domain` type. It exposes a single `overview(Instant from, Instant to, UUID geoAreaId)` returning a new composite **`DashboardOverviewDto`** (`window`, `reportsVolumeTotal`, `ttfr`, `ttr`, `verificationFunnel`, `slaBreaches`, `channelMix`, `moderationActions`) assembled from the existing query methods.
- Implemented by a new `@Service @Transactional(readOnly=true)` `AnalyticsQueryApiService` in `analytics.application.service` that delegates to `AnalyticsQueryService` (no new query logic — DRY). The caller (admin) injects the **interface**.

### 3. Add the **admin** composition surface (admin module only)

- `admin.application.service.AnalyticsAdminService` injects `AnalyticsQueryApi` (the interface) and exposes `overview(...)` — the thin, fault-isolated seam (a failing analytics read degrades to a logged error, never blanks the console — ARCHITECTURE §1).
- `admin.api.controller.AdminAnalyticsController` at `/admin/analytics/overview`, `@PreAuthorize` to the admin/authority read roles, wrapping the result in the single `ApiResponse` envelope. This is the admin-owned dashboard endpoint; the per-tile detail endpoints stay in the analytics module's `AnalyticsDashboardController` (also under `/admin/analytics/*`) — the two co-exist under one path namespace, one owned by analytics (tiles), one by admin (composed overview), with no path collision.

### 4. **No materialised view / rollup table (V150) now** — KISS

PRD says *"consider a rollup table/materialised view if aggregations are heavy."* At MVP/early-Phase-2 volume the existing B-tree indexes (`event_type, occurred_at` and `geo_area_id, category_id, occurred_at`) make every count/trend an index range-scan + group — fast enough. Adding a materialised view now buys a refresh-staleness problem and a second source of truth for no measured benefit (no speculative generality, CLAUDE.md §3). **No V150–V152 migration is created.** Revisit trigger: a trend/overview query shows up on the dashboard p95, or `analytics_event` row count crosses the planned national-scale threshold — then add `V150__analytics_daily_rollup` (a `MATERIALIZED VIEW` keyed on `date_trunc('day')`, refreshed by an outbox/cron worker) behind the *same* `AnalyticsQueryApi`, callers unchanged (extract-to-service survives, ADR-0013 consequence).

## Boundaries, security, privacy

- **Module boundary (ADR-0013, `ModuleBoundaryTest`):** all new analytics files live under `com.taarifu.analytics.*`; all new admin files under `com.taarifu.admin.*`. `admin` depends on `analytics` **only** through `analytics.api.AnalyticsQueryApi` (a sanctioned `admin → analytics` `api → api`/`api → application-via-interface` edge). `analytics` gains **no** dependency on `admin`. The suite stays GREEN.
- **No outbox / no new event:** this is a pure **read** increment. It emits no domain event and adds no outbox edge. (The write side already rides the outbox per ADR-0014.)
- **Deny-by-default (ARCHITECTURE §6.2):** `/admin/analytics/**` is not in any public allow-list, so it requires authentication; every handler is `@PreAuthorize`-gated to `ADMIN`/`ROOT`/`MODERATOR`/`RESPONDER_ADMIN`/`REPRESENTATIVE`. A citizen token is denied at the method layer (proven by a security IT that fails closed).
- **No PII (Appendix E.4, binding):** every new payload is counts/percentiles over the already-PII-free `analytics_event` facts — enum names, public-id strings, time-bucket instants, and integer counts only. Nothing resolves to a person, a precise location, a report body, or `actor_ref`. The trend/overview DTOs carry no new dimension that could.
- **No secrets in source:** none introduced (no external service).

## Consequences

- (+) The §3.3 trend KPIs (volume-over-time, SLA-breach trend) are now served, reusing the existing time index — no schema change.
- (+) The admin console gets one lean composed overview call and the dashboard surface is owned by the admin module through a published port, exactly as `ReportsAdminService` consumes `ReportQueryApi` — one house pattern, boundary mechanically enforced.
- (+) The analytics read model stays the single source for these numbers; no live cross-module reads (ADR-0013).
- (−) Two endpoints now answer under `/admin/analytics/*` from two modules (tiles from analytics, overview from admin). Accepted: distinct sub-paths, no collision, and it keeps each module owning its own surface; documented here so a future reader is not surprised.
- (−) Trend/overview are computed on each call (no cache/MV). Accepted at current scale; revisit trigger and the drop-in MV path are specified in §4 so the change lands behind the same interface.

## Central needs (for other owners)

- **Live emission of the trend-bearing facts is gated on the outbox wiring** (`// TODO(wiring)`, ADR-0013 §2 / ADR-0014): until reporting/identity/moderation emit `REPORT_FILED`/`REPORT_ESCALATED`/funnel events through the outbox into `AnalyticsApi.record`, the trend/overview endpoints return correct-but-empty series. This ADR ships the read contract; emission lands independently. No action required of the analytics/admin owners beyond what is built here.
