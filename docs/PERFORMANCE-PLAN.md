# Taarifu — Performance & Load Test Plan

| | |
|---|---|
| **Owner** | QA / Test Engineering |
| **Status** | Draft v1 — harness landed; first calibrated run pending |
| **Grounds in** | [PRD.md](../PRD.md) §15 (NFRs), §16 (architecture), R28 (scale risk) |
| **Harness** | [`perf/k6/`](../perf/k6) · run guide: [`perf/README.md`](../perf/README.md) |
| **Closes** | `docs/LAUNCH-READINESS.md` P1 *"Load test … not yet run"* |

This plan defines **what** we load-test, **against which budgets**, and **what to
tune** when a budget is missed. The runnable scripts implement it; this document
is the contract they are measured against.

---

## 1. Objectives & the SLO contract (PRD §15)

| # | SLO | Source | k6 threshold |
|---|---|---|---|
| O1 | **Reads p95 < 500 ms** under expected load | PRD §15 | `taarifu_read_latency p(95)<500` |
| O2 | **Writes p95 < 1 s** under expected load | PRD §15 | `taarifu_write_latency p(95)<1000` |
| O3 | **99.9 %** availability for core APIs | PRD §15 | `http_req_failed rate<0.001` per run |
| O4 | **Graceful degradation** under overload (no collapse) | PRD §15, R28 | observed in `stress` (knee, not cliff) |
| O5 | **Functional correctness under load** (envelope `success`, right status) | PRD §17 | `taarifu_journey_ok rate>0.99`, `checks rate>0.99` |

p99 is also asserted (`< 2×` the p95 budget) as a tail guard: a p99 that blows
past p95 signals GC pauses, lock contention, or cold caches even when p95 passes.

**Why availability is a per-run proxy.** 99.9 %/month is an infra/observability
SLO measured continuously on the server (PRD §15 — `actuator/prometheus`). A load
run can't observe a month, so we gate each run at **< 0.1 % failed requests**
under expected load as the strongest single-run proxy, and rely on server
metrics for the true monthly figure.

---

## 2. Scope

**In scope (this harness):**
- The hottest **public reads** (the national-scale front door, PRD §22.6).
- The core **citizen write loop**: file report -> track (PRD §10 M3).
- The **admin dashboard** read aggregation (PRD §10 M14) — the most expensive
  read path (cross-module + grouped analytics).
- A **mixed** production-shape blend for the launch-gate sign-off.

**Out of scope here (tested elsewhere / server-side):**
- USSD/SMS gateway throughput — driven via the aggregator simulator, not k6
  (different transport; tracked separately).
- Push/FCM and SMS provider fan-out throughput — provider-side; asserted via
  outbox/queue-depth + delivery-latency **server metrics**, not request latency.
- Soak-only memory/GC leak analysis is observed on **server** metrics during the
  `soak` profile, not via client latency alone.

---

## 3. Scenarios × profiles (the test matrix)

Every scenario runs at four intensities (`PROFILE`). Same journey, four lenses.

| Scenario (script) | SLO class | smoke | load | stress | soak | Auth |
|---|---|:--:|:--:|:--:|:--:|---|
| `public-reads.js` | read | ✅ | ✅ | ✅ | ✅ | none |
| `citizen-journey.js` | write + read | ✅ | ✅ | ✅ | ✅ | `>= T2` token |
| `admin-dashboard.js` | admin (read) | ✅ | ✅ | ✅ | ⚪ | ADMIN/ROOT token |
| `mixed-traffic.js` | all | ⚪ | ✅ | ✅ | ✅ | citizen + admin |

`✅` primary · `⚪` optional. Admin soak is low-value (few operators); skip unless
chasing a specific leak.

### Profile shapes (`perf/k6/lib/profiles.js`)

| Profile | Shape | Purpose | Default targets |
|---|---|---|---|
| `smoke` | 1–2 VUs, ~1 min | correctness + SLO sanity; per-PR CI gate | `SMOKE_VUS=2` |
| `load` | ramp → hold 5 min → ramp | the PRD §15 "expected load" check (launch gate) | `LOAD_TARGET_VUS=50` |
| `stress` | step beyond expected (~300) | find the knee; confirm O4 graceful degrade | `STRESS_TARGET_VUS=300` |
| `soak` | hold 30 min | leaks / pool exhaustion / backlog over time | `SOAK_VUS=40`, `SOAK_DURATION=30m` |

> The numeric targets are **laptop-friendly defaults, not the production load.**
> They MUST be recalibrated from real onboarding numbers (§7 Open inputs) before
> a launch pass is quoted.

### Endpoint coverage per scenario

- **public-reads:** `GET /regions`, `/regions/{id}/districts`,
  `/representatives`, `/representatives/{id}`, `/petitions`, `/petitions/{id}`,
  `/issue-categories`.
- **citizen-journey:** `POST /auth/otp/request` (warm), [`POST /auth/signup`
  in otp-stub mode], **`POST /reports`** (write), `GET /reports` (mine),
  `GET /reports/{id}`, `GET /reports/{id}/timeline`.
- **admin-dashboard:** `GET /admin/dashboard/stats`,
  `/admin/analytics/{reports/volume, reports/ttfr, reports/sla-breaches,
  verification/funnel}`, `/admin/reports`, `/admin/stats`, `/admin/users`.

---

## 4. SLO budgets (centralised)

Defined once in `perf/k6/lib/thresholds.js`; the plan and the scripts cannot
drift.

| Class | Metric | p95 budget | p99 guard | Override env |
|---|---|---|---|---|
| Read | `taarifu_read_latency` | **500 ms** (PRD §15) | 1000 ms | `READ_P95_MS` |
| Write | `taarifu_write_latency` | **1000 ms** (PRD §15) | 2000 ms | `WRITE_P95_MS` |
| Admin | `taarifu_admin_latency` | **500 ms** (held to read SLO) | — | `ADMIN_P95_MS` |
| Availability | `http_req_failed` | rate **< 0.001** | — | `ERROR_RATE_MAX` |
| Correctness | `taarifu_journey_ok`, `checks` | rate **> 0.99** | — | — |

**Admin budget note (decision needed).** PRD §15 gives no separate admin/back-
office latency SLO. We default the admin dashboard to the **read** budget
(500 ms) — the strict, defensible choice. If the cross-module aggregate
legitimately can't hold 500 ms at expected operator concurrency, the team should
**explicitly** agree a relaxed admin budget (an ADR/PRD note) rather than
silently bumping `ADMIN_P95_MS`. Flagged in §7.

---

## 5. Environment & test data

- **Target:** the backend at `/api/v1` on `:8081`; PostgreSQL 16 + PostGIS 3.4
  and Redis 7 (the `docker-compose.yml` stack, or a staging/region-wave env).
- **Server metrics are part of the test.** `application.yml` exports Prometheus
  with p95/p99 histograms bucketed at **250 ms / 500 ms / 1 s** aligned to PRD
  §15. Every run is read **client-side (k6) AND server-side (Prometheus/Grafana)**
  — client says *what the user saw*, server says *why* (DB pool, GC, queue depth).
- **Realistic data volume matters.** Run against a DB seeded with representative
  reference geography (regions→wards), issue categories, a directory of reps, and
  a non-trivial number of reports — empty tables make every read look fast and
  hide the real query plans. Seed before the `load`/`stress`/`soak` profiles.
- **Auth/test data:** see `perf/README.md` → *Seeding*. Citizen writes need
  `>= T2` tokens (report filing is T2-gated); admin needs an ADMIN/ROOT token
  that completed staff TOTP. OTP is out-of-band + hashed, so tokens are seeded
  once, never minted in the load loop.
- **Locale:** Swahili-first (`Accept-Language: sw`) by default — the i18n
  resolution path is part of measured latency, matching real traffic.

---

## 6. What to tune (when a budget is missed)

A prioritised playbook, mapped to the likely culprit by symptom:

| Symptom | Likely cause | First levers |
|---|---|---|
| **Read p95 > 500 ms** on a list/detail | missing/cold index, N+1, no caching | verify indexes on the filtered/sorted columns; check for N+1 (open-in-view is off — good); add caching for hot reference reads (regions/categories/reps) per PRD §15 ("feed and search optimised — indexes, caching") |
| **Write p95 > 1 s** on `POST /reports` | synchronous side-effects in the request path | ensure routing/notification fan-out is **async via the outbox** (PRD §16, ADR-0014), not inline; keep the file-report transaction tight; defer media/scan work |
| **Heavy p99 tail** (p99 ≫ p95) | GC pauses, lock contention, connection-pool waits | server metrics: JVM GC, DB pool saturation/wait time; size the Hikari pool; check for hot-row locks (optimistic locking should avoid blocking) |
| **5xx / errors climb under load** (O3) | pool/thread exhaustion, downstream timeout | size DB pool + Tomcat threads; confirm circuit-breakers/timeouts on SMS/verification adapters degrade, don't pile up (PRD §15 resilience) |
| **Admin p95 > 500 ms** | cross-module aggregate + grouped analytics | cache/precompute dashboard counts; paginate/limit analytics windows; consider materialised views; or agree a relaxed admin budget (§4) |
| **Stress = cliff, not knee** (O4 fail) | no backpressure / unbounded queues | add backpressure + bounded pools; shed load gracefully; confirm read-only degrade when a dependency is down (PRD §15) |
| **Soak: latency/memory creep** | leak, unbounded cache, outbox backlog | heap/GC trend; bounded caches; confirm outbox drains faster than it fills (queue depth flat, not climbing) |

Architecturally, PRD §16 keeps the high-load modules (**notifications / feed /
search**) extractable from the modular monolith — if a module is the persistent
bottleneck at national scale, that is the escape hatch (R28 mitigation), not a
first resort.

---

## 7. Open inputs / dependencies (must resolve before a launch pass)

These are **not invented** here — they need a decision/number from the team:

1. **Expected pilot load (concurrency / RPS).** The default VU targets are
   placeholders. Calibrate `LOAD_TARGET_VUS` from real region-wave onboarding
   projections before quoting an O1/O2 pass. *(Owner: PM/SRE.)*
2. **Admin latency budget.** Confirm whether the admin dashboard holds the
   read SLO (500 ms) at expected operator concurrency, or agree a relaxed,
   documented budget (§4). *(Owner: Architect + PM.)*
3. **Perf auth seam.** To drive the citizen **write** journey at volume we need
   either (a) a non-production perf OTP seam + `auto-stub` verification so
   `seed-tokens.sh` can mint `>= T2` tokens, or (b) a DB seed of pre-verified
   `>= T2` accounts. *(Owner: Backend — opt-in, non-prod only; harness never
   touches app source.)*
4. **Announcement-burst fan-out target** (PRD §15, R28). Define the burst size
   (recipients) and the delivery-latency/queue-depth SLO; drive the trigger via
   the API and assert fan-out on **server** outbox/queue metrics. *(Owner: SRE +
   Backend.)*
5. **Production-like data volume** for the target env (row counts per table) so
   reads exercise real query plans. *(Owner: QA + DBA.)*

---

## 8. Entry & exit criteria (the quality gate)

**Entry (before a meaningful `load` run):**
- Backend healthy at `/api/v1/actuator/health`; server metrics scrapeable.
- DB seeded with production-like reference + report volume (§5, §7.5).
- Tokens seeded (§5) for the authenticated journeys.
- `LOAD_TARGET_VUS` calibrated to expected load (§7.1).

**Exit / PASS (per the SLO contract):**
- `load` profile, mixed-traffic: **all thresholds green** (O1 read p95 < 500 ms,
  O2 write p95 < 1 s, O3 errors < 0.1 %, O5 correctness > 99 %).
- `stress`: degradation is a **knee, not a cliff** (O4) — latency rises,
  availability holds above an agreed floor, the system recovers on ramp-down.
- `soak`: no latency creep, no memory leak, outbox/queue depth stable.
- Server metrics corroborate the client numbers (no hidden 5xx, no pool
  exhaustion, fan-out drains).

**FAIL = block.** Any red SLO under expected load is a launch blocker unless the
team records an explicit, owned exception (with the data behind it).

---

## 9. CI integration

- **Per-PR:** `smoke` on `public-reads.js` (and `citizen-journey.js` once a
  shared perf token is wired) against an ephemeral/staging stack. k6's non-zero
  exit on a breached threshold is the gate — no extra glue.
- **Pre-release / scheduled:** `load` + `stress` (+ periodic `soak`) against
  staging; archive `--summary-export` JSON as a build artefact for trend
  tracking. Wire a regression alert if read/write p95 drifts up release-over-
  release.
