# Taarifu — Deployment & Local-Dev Guide

Concise, monorepo-specific operations guide for Taarifu. Grounded in `CLAUDE.md`
(§5 stack/CI gates), `docs/architecture/ARCHITECTURE.md` (§2 stack, §4.1
Flyway/PostGIS, §9 observability), and adapted from the north-star
`taarifu-core-api/deploy.md`. This is the *real* version for this repo — placeholders
are filled in or called out explicitly.

> The single source of product truth is `PRD.md`. This document governs how we
> ship the modular monolith and its companion web/mobile clients.

---

## 1. What ships

| Component | Path | Artefact | Runtime |
|---|---|---|---|
| Backend API | `backend/` | Spring Boot fat jar -> multi-stage Docker image | Java 21 JRE, **:8081**, context-path `/api/v1` |
| Web admin | `web-admin/` | Angular 18 static bundle | Served by CDN / static host (or Nginx) |
| Mobile app | `mobile/` | Flutter Android/iOS artefacts | Distributed via store / internal track |

> `web-admin/` and `mobile/` currently live on unmerged feature branches; CI jobs
> for them are present but **skip** when the directory is absent on a branch.

The backend is a **modular monolith** — one deployable. It owns its schema via
**Flyway** (`ddl-auto=validate`); migrations run **with the app on startup**, never
auto-DDL (ARCHITECTURE §4.1).

---

## 2. Environments

| Env | Trigger | Hosting intent (D-Q9) | Purpose |
|---|---|---|---|
| **dev** | every push to `develop` | in-country-where-feasible / dev cloud | fast feedback, ephemeral data |
| **staging** | promote from `develop` (tag or manual) | mirrors prod placement | pre-prod validation, UAT |
| **prod** | approved promote from staging | **in-country where feasible; PII never leaves** | citizen traffic |

**Data residency (D-Q9, R12):** PII-bearing data — NIDA/voter IDs, MSISDN,
verification evidence — stays on in-country infrastructure where feasible; the
design is cloud-portable so no single vendor/jurisdiction is load-bearing. The
final residency placement is a **Legal sign-off item before prod launch** — do not
launch prod into a foreign region without it. **No national-ID data in non-prod**
(R10): dev/staging use stubbed or synthetic identity data only.

**SLOs (PRD §15):** API availability 99.9% monthly; reads p95 < 500 ms; writes
p95 < 1 s. Health endpoint `/api/v1/actuator/health` must return 200 under load.

---

## 3. Local development

```bash
# 1. Provide secrets (one-time)
cp deploy/.env.example .env
#    fill the two required values:
#      TAARIFU_JWT_SECRET=$(openssl rand -base64 48)
#      TAARIFU_CRYPTO_DEV_KEY=$(openssl rand -base64 32)

# 2a. Infra only (run the backend from your IDE against it)
docker compose up -d db redis

# 2b. OR the full stack incl. the backend container
docker compose up --build

# 3. Verify
curl http://localhost:8081/api/v1/actuator/health     # -> {"status":"UP"}

# Tear down (add -v to also drop the DB volume)
docker compose down
```

The compose stack is PostgreSQL 16 + PostGIS 3.4, Redis 7, and the backend on
`:8081`, wired to the same `TAARIFU_*` env vars `application.yml` reads. Required
secrets have **no defaults** — compose fails fast with a clear message if missing.

---

## 4. CI pipeline (`.github/workflows/ci.yml`)

Triggered on push/PR to `develop` and `main`. Gate order follows CLAUDE.md §5:

```
detect changed apps
        │
        ├─ backend:       JDK 21 -> mvnw -B verify (unit + Testcontainers ITs) -> publish reports
        ├─ backend-sast:  CodeQL (Java) -> Security tab
        ├─ web-admin:     Node 22 -> npm ci -> lint? -> build -> test (headless)   [skips if dir absent]
        ├─ mobile:        Flutter -> pub get -> analyze -> test                     [skips if dir absent]
        └─ container:     build backend image -> Trivy scan (gate) -> push (only if registry secret)
```

Key points:

- **Integration tests run in CI** where Docker exists: the backend's
  `*IntegrationTest` classes use **Testcontainers** (`postgis/postgis:16-3.4`) and
  self-provision their DB. A Postgres+PostGIS and Redis **service container** are
  also provided as fallback / future-proofing.
- **SAST** = CodeQL (`security-and-quality` suite) for Java.
- **Container + dependency scan** = Trivy on the built image; the build **fails on
  CRITICAL/HIGH with a fix available** (`ignore-unfixed: true`). SARIF is also
  uploaded to the Security tab.
- **No push without credentials:** the image is built and scanned on every run;
  it is pushed only on a `push` event when the `REGISTRY` / `REGISTRY_USERNAME`
  secrets exist. Forks/PRs build-and-scan only.

### Required repo secrets (only for image push / deploy)

| Secret | Purpose | Needed for |
|---|---|---|
| `REGISTRY` | container registry host (e.g. `ghcr.io/<org>`) | image push |
| `REGISTRY_USERNAME` / `REGISTRY_PASSWORD` | registry auth | image push |

CI runs fully (build, test, SAST, scan) **without** any secret; only image push is gated.

---

## 5. Build → deploy flow

1. **Build & test** — `mvnw -B verify` produces `taarifu-backend-<ver>.jar`; the
   multi-stage `backend/Dockerfile` packages it on a slim JRE base as a **non-root**
   user, no secrets baked in (config from env at runtime).
2. **Scan** — Trivy gates the image; SAST gates the source.
3. **Image tags** — `:<git-sha>` (immutable) and `:<branch>`; a release tag adds
   `:vX.Y.Z`. The **same image** is promoted dev -> staging -> prod (no rebuild).
4. **Deploy** — start lean (managed VM + systemd / `docker compose` on a host),
   grow to orchestrated containers (Helm/Kubernetes) when load and team justify it
   (PRD §16). Keep the migration seam clean; don't impose Kubernetes early.
5. **Config & secrets** — 12-factor: all config via env; secrets from the env /
   secret manager (Vault / cloud KMS), never from source. CORS is an explicit
   allow-list (no `*`-with-credentials). Actuator exposes only `health,info`.

---

## 6. Database migrations (Flyway)

Golden rule: **migrations deploy with the app**. On startup the backend runs
Flyway (`validate-on-migrate: true`) then Hibernate validates entities against the
migrated schema (`ddl-auto=validate`) and **fails fast** on any mismatch.

- Migrations are **forward-only**, range-partitioned per module
  (`V<NNN>__<module>_<change>.sql`, ARCHITECTURE §4.1). Never edit an applied
  migration — add a new one.
- **Backup before a prod migration:** `pg_dump` / managed snapshot first.
- Prefer **expand → migrate → contract** (backward-compatible schema first, drop
  old columns in a later release) so a rollback of the app doesn't break the DB.

---

## 7. Health, smoke, gates

- **Liveness:** `GET /api/v1/actuator/health/liveness`
- **Readiness:** `GET /api/v1/actuator/health/readiness` (DB/Redis checks)
- **Aggregate:** `GET /api/v1/actuator/health`

Post-deploy smoke: probe `/api/v1/actuator/health` returns `UP`; run a thin
read-path check. **Block promotion** if, over a 10-minute window, 5xx rate > 1% or
reads p95 > 500 ms (error-budget gate, PRD §15).

Graceful degradation (PRD §622): when a dependency is down, the citizen path stays
available in read-only/degraded mode rather than hard-failing — verify this holds
after deploy.

---

## 8. Rollback (one command)

The backend is stateless; rollback = redeploy the **previous known-good image tag**.

```bash
# docker compose host
docker compose pull && docker compose up -d        # or pin the previous tag and `up -d`

# Helm (when on Kubernetes)
helm history taarifu -n taarifu-prod
helm rollback taarifu <PREV_REVISION> -n taarifu-prod
```

If a **migration** caused the issue: prefer rolling forward with a fix; only apply
a down-migration if it has been validated; otherwise restore the pre-deploy
snapshot to a new instance and fail over. Document **RTO/RPO** targets with the
hosting provider once chosen (placeholder pending D-Q9 sign-off).

---

## 9. Election-period operational neutrality

During election periods enforce: a **change freeze** (no non-critical deploys),
heightened monitoring, capacity headroom for traffic spikes, and **no infra action
that could be read as favouring any party**. All admin/infra actions remain on the
immutable audit trail (PRD §638).

---

## 10. Observability (ARCHITECTURE §9)

- **Metrics:** Micrometer; expose `/api/v1/actuator/prometheus` to the internal
  scrape only (currently `health,info` are the only public endpoints — widen the
  actuator allow-list for the metrics scraper via env, never publicly).
- **Logs:** structured JSON with trace/span + correlation id; **PII redacted**.
- **Traces:** OpenTelemetry end-to-end incl. outbox -> worker spans.
- **Alert on:** 5xx spikes, latency SLO burn, SLA breaches, queue/DLQ depth,
  circuit-open, provider spend spikes, deploy failure, CrashLoopBackOff.

---

## 11. Open items needing a decision (ask, don't invent)

| Item | Owner | Blocks |
|---|---|---|
| Hosting provider + final PII residency placement (D-Q9, R12) | Legal + SRE | prod launch |
| Container registry choice + credentials | SRE | image push/deploy |
| Orchestration target (VM+systemd now vs. Helm/K8s later) | SRE | prod topology |
| RTO/RPO targets + backup/restore drill cadence | SRE + Legal | DR sign-off |
| Data-controller registration timing (R27) | Legal | prod launch |
