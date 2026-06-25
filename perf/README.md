# Taarifu — Load & Performance Harness (k6)

Runnable [k6](https://k6.io) load/stress/soak scripts for the Taarifu backend,
gating on the **PRD §15** Non-Functional Requirements:

> API **p95 < 500 ms for reads**, **< 1 s for writes** under expected load;
> **99.9 %** monthly availability for core APIs; graceful degradation.

This harness exists to close the **OPEN** launch-readiness item
(`docs/LAUNCH-READINESS.md` P1: *"Load test … not yet run"*). The companion
plan — scenarios, SLO budgets, and what to tune — is in
[`docs/PERFORMANCE-PLAN.md`](../docs/PERFORMANCE-PLAN.md).

> It **does not touch app source.** It is a black-box harness against the running
> API at `/api/v1` on `:8081`.

---

## Layout

```
perf/
  k6/
    lib/
      config.js       target/env knobs, API base, auth, locale (Swahili-first)
      thresholds.js   PRD §15 SLO budgets -> k6 pass/fail thresholds + metrics
      profiles.js     smoke | load | stress | soak ramping-VU stages
      http.js         shared request helper: auth, SLO tagging, envelope checks
      setup.js        readiness gate + real reference-id resolution
    scenarios/
      public-reads.js     Guest front door: regions / reps / petitions / categories
      citizen-journey.js  (signup/OTP) -> file report -> track   [needs T2 token]
      admin-dashboard.js  back-office console load              [needs admin token]
      mixed-traffic.js    realistic blend (reads 80 / citizen 18 / admin 2)
    data/
      citizen-tokens.example.csv   template for the citizen >= T2 token pool
      README.md                    how tokens get here
  scripts/
    seed-tokens.sh    mints citizen tokens via the perf OTP seam (non-prod)
  .gitignore          never commit real tokens / local results
```

---

## Prerequisites

1. **Install k6** — <https://grafana.com/docs/k6/latest/set-up/install-k6/>
   - Windows: `choco install k6` or `winget install k6 --source winget`
   - macOS: `brew install k6` · Debian/Ubuntu: see the docs.
   - Verify: `k6 version`
2. **A running backend** to test against (next section).
3. For the **authenticated** journeys: seeded tokens (see *Seeding*).
4. For `seed-tokens.sh`: `bash`, `curl`, `jq`.

---

## Run against a local stack

From the repo root:

```bash
# 1. Bring up the dependencies + backend on :8081 (PostGIS + Redis + app).
#    Copy deploy/.env.example -> .env and set the two required secrets first
#    (TAARIFU_JWT_SECRET, TAARIFU_CRYPTO_DEV_KEY) — see docker-compose.yml header.
docker compose up -d

# 2. Confirm it is ready (context-path is /api/v1):
curl -s http://localhost:8081/api/v1/actuator/health

# 3. Smoke the PUBLIC reads (no auth needed). Start here every time.
k6 run -e PROFILE=smoke perf/k6/scenarios/public-reads.js
```

The script's `setup()` gates on `/actuator/health` and resolves real ids, so a
cold/booting backend fails fast with a clear message instead of bad numbers.

### Profiles

Every scenario takes the same `PROFILE` (defaults to `smoke`):

| `PROFILE` | Shape | Use it for |
|---|---|---|
| `smoke` | 1–2 VUs, ~1 min | correctness + SLO sanity; the **per-PR CI gate** |
| `load` | ramp to expected pilot VUs, hold 5 min | the **PRD §15 "under expected load" check** (launch gate) |
| `stress` | step beyond expected to ~300 VUs | find the knee; confirm **graceful degradation** |
| `soak` | moderate load held 30 min | leaks / pool exhaustion / queue backlog over time |

```bash
k6 run -e PROFILE=load   perf/k6/scenarios/public-reads.js
k6 run -e PROFILE=stress perf/k6/scenarios/public-reads.js
k6 run -e PROFILE=soak   -e SOAK_DURATION=45m perf/k6/scenarios/public-reads.js
```

### Run against staging / a region-wave env

Only flags change — never the scripts:

```bash
k6 run -e PROFILE=load \
       -e BASE_URL=https://staging.taarifu.go.tz \
       -e LOAD_TARGET_VUS=200 \
       perf/k6/scenarios/public-reads.js
```

### Key env knobs

| Env | Default | Meaning |
|---|---|---|
| `BASE_URL` | `http://localhost:8081` | host root (no context-path) |
| `API_PREFIX` | `/api/v1` | API context-path |
| `PROFILE` | `smoke` | load shape (above) |
| `ACCEPT_LANGUAGE` | `sw` | locale header (Swahili-first; set `en` to A/B) |
| `LOAD_TARGET_VUS` | `50` | hold concurrency for `load` |
| `STRESS_TARGET_VUS` | `300` | peak concurrency for `stress` |
| `SOAK_VUS` / `SOAK_DURATION` | `40` / `30m` | soak hold |
| `READ_P95_MS` / `WRITE_P95_MS` | `500` / `1000` | SLO budgets (PRD §15 — change only with justification) |
| `ERROR_RATE_MAX` | `0.001` | max failed-request fraction (99.9 % proxy) |
| `CITIZEN_BEARER` / `CITIZEN_TOKENS_CSV` | — | citizen `>= T2` auth (write journey) |
| `ADMIN_BEARER` | — | ADMIN/ROOT auth (admin journey) |
| `REPORT_CATEGORY_ID` / `REPORT_WARD_ID` | auto-discovered | pin the report refs for lower variance |

---

## The authenticated journeys (and why they need seeding)

`OtpService` delivers the OTP **out-of-band** (SMS gateway / logging stub) and
stores only its **keyed hash** — the plaintext code is **never** in any API
response (S-4, anti-enumeration). So a black-box script **cannot log in inside
the load loop**. On top of that, **filing a report is T2-gated**
(`ReportController @RequiresTier("T2")`) — a fresh T1 signup token cannot file.

Therefore the citizen and admin journeys authenticate with **pre-seeded tokens**.

### Citizen write path — two modes

- **`AUTH_MODE=seeded` (default, gives the real SLO numbers):** the loop uses a
  pre-seeded `>= T2` bearer (`CITIZEN_BEARER` or a row from
  `CITIZEN_TOKENS_CSV`) for the report write + track, while still exercising the
  `POST /auth/otp/request` hop once per iteration as a measured warm path.

  ```bash
  k6 run -e PROFILE=load -e CITIZEN_BEARER=<t2-access-token> \
         perf/k6/scenarios/citizen-journey.js
  # or a pool, fanned out across VUs:
  k6 run -e PROFILE=load -e CITIZEN_TOKENS_CSV=perf/k6/data/citizen-tokens.csv \
         perf/k6/scenarios/citizen-journey.js
  ```

- **`AUTH_MODE=otp-stub` (full signup, requires a backend perf seam):** if you
  run the backend in a **non-production** perf configuration that accepts a fixed
  OTP, the loop completes the whole `request -> verify` signup.

  ```bash
  k6 run -e PROFILE=smoke -e AUTH_MODE=otp-stub -e PERF_OTP=000000 \
         perf/k6/scenarios/citizen-journey.js
  ```

### Admin path

`AdminDashboardController` is `hasAnyRole('ADMIN','ROOT')` and staff login is
**MFA-gated** (TOTP, N-4) — not completable in-loop. Pass a pre-seeded token for
an admin that already finished the TOTP exchange:

```bash
k6 run -e PROFILE=load -e LOAD_TARGET_VUS=20 -e ADMIN_BEARER=<admin-access-token> \
       perf/k6/scenarios/admin-dashboard.js
```

### Seeding

> **No secrets in source (CLAUDE.md §12).** Real tokens go in
> `perf/k6/data/citizen-tokens.csv` (gitignored). Only `*.example.csv` is committed.

Recommended for a throwaway perf env — run the backend with a perf OTP seam
(`PERF_OTP`) and `TAARIFU_VERIFICATION_PROVIDER=auto-stub` so accounts can reach
T2/T3 without NIDA, then:

```bash
BASE_URL=http://localhost:8081 PERF_OTP=000000 COUNT=20 \
  ./perf/scripts/seed-tokens.sh
# Validate a token's tier before relying on it for the WRITE journey:
curl -s http://localhost:8081/api/v1/profiles/me/tier \
  -H "Authorization: Bearer <token>" | jq
```

If you can't add a seam, mint `>= T2` tokens from your DB seed and write them
(one per line) into `citizen-tokens.csv`. Either way the output is the same.

> The perf OTP seam and auto-stub verification are a **backend opt-in for a
> non-production perf env** — this harness never modifies app source. Tracked in
> the PERFORMANCE-PLAN under *Open inputs / dependencies*.

---

## The mixed (production-shape) run — for the launch gate

```bash
k6 run -e PROFILE=load \
       -e CITIZEN_TOKENS_CSV=perf/k6/data/citizen-tokens.csv \
       -e ADMIN_BEARER=<admin-token> \
       perf/k6/scenarios/mixed-traffic.js
```

Runs reads + citizen + admin concurrently at an 80 / 18 / 2 weighting (tune via
`READ_WEIGHT` / `CITIZEN_WEIGHT` / `ADMIN_WEIGHT`). Authenticated journeys are
auto-skipped if their tokens are absent, so it also runs reads-only with no
seeding.

---

## How to read the results

At the end of a run k6 prints a summary. Read it in this order:

1. **`✓/✗` thresholds block (top).** Any `✗` = the run **FAILED** that SLO and
   k6 exits **non-zero** (CI/quality-gate blocks). The ones that matter:
   - `taarifu_read_latency ... p(95)<500` — PRD §15 reads.
   - `taarifu_write_latency ... p(95)<1000` — PRD §15 writes.
   - `taarifu_admin_latency ... p(95)<500` — admin reads.
   - `http_req_failed ... rate<0.001` — availability proxy (99.9 %).
   - `taarifu_journey_ok ... rate>0.99` + `checks ... rate>0.99` — correctness
     (a 2xx with envelope `success:false` still counts as a failure).
2. **Per-class trends** — `taarifu_read_latency` / `_write_` / `_admin_` give
   `avg / p(90) / p(95) / p(99)`. p95 vs the budget is the headline; a p99 far
   above p95 means a heavy tail (GC pause, lock, cold cache) worth chasing.
3. **`http_req_duration{name:...}`** — per-endpoint latency (we tag the route,
   not the id). This is how you find *which* endpoint is slow, e.g.
   `GET /representatives/{id}` vs `GET /regions`.
4. **`taarifu_server_errors`** and `http_req_failed` — any 5xx/transport errors.
5. **Throughput** — `http_reqs` (count + rate) and `iterations` tell you the RPS
   the run actually drove; compare against the target you set.

### Saving / sharing results

```bash
# Machine-readable summary for CI to parse / archive:
k6 run --summary-export=perf/results/summary.json -e PROFILE=load \
       perf/k6/scenarios/public-reads.js

# Full time-series for offline analysis or a Grafana dashboard:
k6 run --out json=perf/results/run.json ...
# (results/ is gitignored.)
```

> **Read the SERVER side too.** The backend already exports Prometheus with
> p95/p99 histograms bucketed at 250 ms / 500 ms / 1 s aligned to PRD §15
> (`application.yml` → `management.metrics.distribution`). During a run, watch
> `/api/v1/actuator/prometheus` (and any Grafana board) for server-side p95,
> 5xx rate, DB pool saturation, and **outbox/queue depth** — the client numbers
> tell you *what* the user saw; the server numbers tell you *why*.

---

## CI usage

Wire the **smoke** profile into the pipeline (cheap, deterministic) as a gate;
run **load** on a schedule / pre-release against staging. k6's non-zero exit on a
breached threshold is the gate signal — no extra assertion glue needed.

```bash
k6 run -e PROFILE=smoke -e BASE_URL=$STAGING_URL perf/k6/scenarios/public-reads.js
```

---

## Limits / honesty notes

- **Run k6 from a host that is NOT the backend host**, and ensure the load
  generator isn't the bottleneck (watch its CPU). Localhost runs prove
  correctness and relative regressions, not absolute production capacity.
- **"Expected load" is a placeholder.** The default VU targets are
  laptop-friendly; calibrate `LOAD_TARGET_VUS` from real onboarding numbers
  before quoting a launch pass (see PERFORMANCE-PLAN → *Open inputs*).
- **Announcement-burst fan-out** (PRD §15 / R28) is a server-side async path
  best driven by triggering a mass announcement and watching outbox/queue depth
  + delivery latency on the server metrics; the request-side trigger fits this
  harness, the fan-out is asserted on the server. Tracked in the plan.
