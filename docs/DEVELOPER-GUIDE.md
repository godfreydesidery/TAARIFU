# Taarifu — Developer Onboarding Guide

> Get a developer from a fresh clone to a running backend + admin console, an authenticated
> API call, and their first merged feature — without a second meeting.
>
> **Product truth:** [PRD.md](../PRD.md). **Engineering rules:** [CLAUDE.md](../CLAUDE.md).
> **Design:** [docs/architecture/ARCHITECTURE.md](architecture/ARCHITECTURE.md) and [docs/adr/](adr/).
> **API contract:** [docs/api/openapi.yaml](api/openapi.yaml) (curated) + the live springdoc spec
> at `/api/v1/openapi.json`.

---

## 1. What you are building

Taarifu is a **Tanzania civic-engagement platform** — citizens report issues and engage their
elected representatives and government/parastatal/private responders. It is **Swahili-first,
mobile-first, and inclusive of feature phones** (USSD/SMS). The backend is a **Spring Boot 3.3 /
Java 21 modular monolith** over **PostgreSQL + PostGIS** with **Redis**, **Flyway-owned schema**
(`ddl-auto=validate`), stateless JWT auth, tiered identity (T1–T3), and a transactional
outbox + in-process event bus. Clients are an **Angular 18** admin console (+ citizen PWA workspace)
and a **Flutter** citizen app.

---

## 2. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| **JDK** | **21** (Temurin/Adoptium) | Maven toolchain pinned to 21. |
| **Docker + Docker Compose** | recent | Runs PostgreSQL 16 + PostGIS 3.4 and Redis 7. |
| **Node.js** | 18 or 20 LTS | For the Angular admin console. |
| **Angular CLI** | 18 | `npm i -g @angular/cli@18` (or use `npx ng`). |
| **Flutter SDK** | stable | Only for `/mobile` work. |
| **Git** | any | gitflow-lite (see §8). |
| `openssl` | any | To generate the two required dev secrets. |

> Windows: the repo includes the Maven wrapper (`mvnw` / `mvnw.cmd`). Use `./mvnw` in Git Bash
> or `mvnw.cmd` in PowerShell. The Bash snippets below also work under Git Bash.

---

## 3. Repository layout

```
/backend        Spring Boot (Java 21) modular monolith — the API & domain (16 modules)
/web-admin      Angular 18 admin console (+ citizen web/PWA workspace)
/mobile         Flutter citizen app
/docs           design docs, ADRs (docs/adr/), OpenAPI (docs/api/), this guide
/deploy         .env templates + production compose
docker-compose.yml   local dev stack (db + redis + backend)
PRD.md  SYNOPSIS.md  TEAM.md  README.md  CLAUDE.md
```

### Backend module map (`com.taarifu.*`)

| Module | Responsibility | Public path roots |
|---|---|---|
| `common` | Shared kernel: `ApiResponse` envelope, `ErrorCode`, `ResponseFactory`, pagination, security primitives, outbox. **Depends on nothing else.** | — |
| `identity` | Accounts, profile, locations, roles, trust tiers, verification, refresh tokens, MFA. | `/auth`, `/profiles`, `/auth/mfa/totp` |
| `geography` | Mkoa→…→Kata/Mtaa, constituencies, GPS→ward (PostGIS). | `/regions`, `/districts`, `/wards`, `/constituencies`, `/locations` |
| `institutions` | Representatives, parties, parliaments. | `/representatives`, `/parties`, `/parliaments`, `/admin/institutions` |
| `reporting` | Reports, case events, issue categories, SLA. | `/reports`, `/public/reports`, `/issue-categories` |
| `responders` | Responder/org directory + workspace (assign/start/resolve/escalate), routing rules. | `/responders`, `/organisations`, `/responders/admin` |
| `engagement` | Petitions, surveys/polls, Q&A. | `/petitions`, `/surveys`, `/questions` |
| `accountability` | Contributions, attendance, promises, ratings (curation). | `/representatives/{id}/...`, `/ratings`, `/accountability` |
| `communications` | Feed, announcements, subscriptions, notifications + preferences; SMS/USSD/push/email ports. | `/feed`, `/announcements`, `/subscriptions`, `/notifications` |
| `tokens` | Wallet + ledger (free quota), action-cost/reward admin. **Tokens never gate binding actions (D18).** | `/me/wallet`, `/admin/tokens` |
| `moderation` | Flags, queue + actions, appeals, ID-verification review. | `/flags`, `/moderation/...` |
| `media` | Pre-signed upload/download, malware-scan callback. | `/media` |
| `ussd` | Feature-phone aggregator webhook (text/plain, shared secret). | `/ussd/gateway` |
| `analytics` | KPI/SLA/funnel dashboards. | `/admin/analytics` |
| `admin` | User management + additive role grants, reports oversight, dashboards, app/flag config, outbox DLQ. | `/admin/...`, `/app-config` |

**Internal layering (every module):** `api` (controllers, DTOs, events — the public surface) →
`application` (services, transaction boundary, mappers) → `domain` (entities, repositories, ports) →
`infrastructure` (adapters, config, persistence). Cross-module integration is **only** through
another module's `com.taarifu.<m>.api` package (sync `*QueryApi` ports) or **events** — never via
another module's `domain`/`infrastructure` (ADR-0013, enforced by `ModuleBoundaryTest`).

---

## 4. Local setup (the 10-minute path)

### 4.1 Create your `.env`

`docker-compose.yml` auto-loads `./.env` from the repo root. Copy the template and fill the **two
required secrets** — there are no insecure defaults; the backend and compose both fail fast if a
secret is missing (MF-1).

```bash
cp deploy/.env.example .env

# Generate strong values and paste them into .env:
openssl rand -base64 48   # -> TAARIFU_JWT_SECRET   (must be >= 256-bit)
openssl rand -base64 32   # -> TAARIFU_CRYPTO_DEV_KEY (field-encryption dev key)
```

`.env` is git-ignored. **Never commit a secret** (CLAUDE.md §12). Required and notable keys:

| Var | Required | Purpose |
|---|---|---|
| `TAARIFU_JWT_SECRET` | **yes** | JWT signing secret (≥256-bit; boot fails fast if absent/weak). |
| `TAARIFU_CRYPTO_DEV_KEY` | **yes** | Base64 256-bit key for field-level PII encryption (dev only; KMS in prod). |
| `POSTGRES_DB / _USER / _PASSWORD` | no (defaults) | Local DB; defaults `taarifu/taarifu/taarifu`. Not production secrets. |
| `TAARIFU_CORS_ORIGINS` | no | Browser allow-list; default `http://localhost:4200`. Never `*` with credentials. |
| `TAARIFU_GEOCODER` | no | `stub` locally (no seeded boundaries) or `postgis`. |
| `TAARIFU_VERIFICATION_PROVIDER` | no | `operator-assisted` (MVP) or `auto-stub` (E2E/dev). |
| `DB_HOST_PORT / REDIS_HOST_PORT / BACKEND_HOST_PORT` | no | Host port overrides if a port is taken. |

### 4.2 Bring up the dependencies

```bash
# Just the infra (recommended — run the backend from your IDE for fast reload):
docker compose up -d db redis

# OR the full stack including the backend container:
docker compose up --build
```

The DB image is `postgis/postgis:16-3.4` (matches prod and Testcontainers). Flyway runs the
range-partitioned migrations on startup; `ddl-auto=validate` means Hibernate only validates entities
against the migrated schema and fails fast on a mismatch — it never creates or alters tables.

### 4.3 Run the backend (dev profile)

```bash
cd backend
# Pass the dev profile + the two secrets the app reads from the environment:
SPRING_PROFILES_ACTIVE=dev \
TAARIFU_JWT_SECRET="$(grep TAARIFU_JWT_SECRET ../.env | cut -d= -f2)" \
TAARIFU_CRYPTO_DEV_KEY="$(grep TAARIFU_CRYPTO_DEV_KEY ../.env | cut -d= -f2)" \
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

PowerShell equivalent:

```powershell
$env:SPRING_PROFILES_ACTIVE="dev"
$env:TAARIFU_JWT_SECRET="<paste from .env>"
$env:TAARIFU_CRYPTO_DEV_KEY="<paste from .env>"
cd backend; .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

The API listens on **`http://localhost:8081`** with context-path **`/api/v1`**.

| What | URL |
|---|---|
| Liveness probe | `http://localhost:8081/api/v1/actuator/health/liveness` |
| Health | `http://localhost:8081/api/v1/actuator/health` |
| Swagger UI | `http://localhost:8081/api/v1/swagger-ui.html` |
| OpenAPI JSON | `http://localhost:8081/api/v1/openapi.json` |
| A public read (smoke test) | `http://localhost:8081/api/v1/regions` |

### 4.4 Run the admin console

```bash
cd web-admin
npm install
npx ng serve   # http://localhost:4200 (already in the default CORS allow-list)
```

### 4.5 (Optional) Run the mobile app

```bash
cd mobile
flutter pub get
flutter run    # point its API base at http://10.0.2.2:8081/api/v1 on the Android emulator
```

---

## 5. The dev-admin login (and the staff MFA dance)

The platform ships with **zero accounts** — there is no user-seed migration. Under the **`dev`
profile only**, `DevAdminSeeder` bootstraps one loginable ROOT/staff account so the admin console has
a credential. It is `@Profile("dev")` and never loads under any other profile (no backdoor in
staging/prod). It is idempotent and no-ops if any ACTIVE ROOT/ADMIN already exists.

| Field | Value |
|---|---|
| Phone (account key) | `+255700000001` |
| Password | `Admin@12345` (or `TAARIFU_DEV_ADMIN_PASSWORD` if you set it) |
| TOTP secret | `JBSWY3DPEHPK3PXP` (the RFC 4648/6238 test vector — reproducible in any authenticator) |

Because it holds a staff role, login is **two-step** (the staff MFA gate, N-4). The exact recipe is
also printed at WARN on dev startup.

```bash
BASE=http://localhost:8081/api/v1

# Step 1 — password login returns an MFA challenge (no token pair yet):
curl -s -X POST $BASE/auth/login/password \
  -H 'Content-Type: application/json' \
  -d '{ "accountKey": "+255700000001", "password": "Admin@12345" }'
# -> data.mfaRequired = true, data.mfaToken = "<challenge>"

# Step 2 — exchange the challenge + a current TOTP code (from an authenticator
#           seeded with JBSWY3DPEHPK3PXP) for the real token pair:
curl -s -X POST $BASE/auth/login/totp \
  -H 'Content-Type: application/json' \
  -d '{ "mfaToken": "<challenge>", "totp": "<6-digit code>" }'
# -> data.accessToken + data.refreshToken

# Use it:
curl -s $BASE/admin/users -H "Authorization: Bearer <accessToken>"
```

> To skip MFA locally (e.g. headless tests), set `TAARIFU_MFA_ENFORCED=false`. **Never** disable MFA
> in a real deployment.

A **plain citizen** account, by contrast, signs up with no MFA:
`POST /auth/otp/request` → `POST /auth/signup` (with the OTP) → you get a T1 token pair immediately.
In dev with the logging SMS stub, the OTP is written to the backend log (never to the response).

---

## 6. Calling the API — the things every caller must know

- **One envelope.** Every response is `ApiResponse`: `{ success, statusCode, message, data, meta,
  timestamp }`. Read `success`/`statusCode`; on error branch on **`data.code`** (the stable
  `ErrorCode`, e.g. `TIER_TOO_LOW`), never on `message` (which is localised).
- **Language.** Default responses are **Swahili**. Send `Accept-Language: en` for English.
- **Auth.** `Authorization: Bearer <accessToken>`. Access tokens last ~15 min; rotate the refresh
  token via `/auth/refresh` (single-use; reuse revokes the family).
- **Tiers.** Some actions need a live tier (T1/T2/T3). The server re-resolves the tier per request;
  a shortfall returns `403` with `data.code = TIER_TOO_LOW`. Binding acts (file report, sign
  petition, rate a rep, binding poll) also check **electoral scope + one-per-person** — and never a
  token balance (D18).
- **Pagination.** `?page=0&size=20&sort=createdAt,desc` (`size` capped at 100). Paged responses fill
  `meta = { page, size, total, totalPages }`.
- **Idempotency.** Send an `Idempotency-Key` header on create/submit endpoints (reports, signatures,
  OTP) so retries are safe.
- **Concurrency.** Updates carry the entity `version` (`If-Match` / body field); a stale version →
  `409 CONFLICT`.
- **Public vs authenticated.** Civic reference reads (`/regions`, `/representatives`,
  `/issue-categories`, `/public/reports`, `/responders`, published `/announcements/{id}`, engagement
  GETs) are open. Everything under `/me/*`, all writes, and every `/admin/*` + `/moderation/*` surface
  require a token plus the right role/tier (deny-by-default; method-level `@PreAuthorize`).

The full operation list, request DTOs, error codes, and the public/authenticated split are in
[docs/api/openapi.yaml](api/openapi.yaml) and the live Swagger UI.

---

## 7. Building, testing, and quality gates

```bash
cd backend
./mvnw -q -DskipTests package          # compile + assemble
./mvnw -q test                         # unit + integration (Testcontainers spins a real Postgres)
./mvnw -q test -Dtest=ModuleBoundaryTest   # ArchUnit boundary rules (must stay GREEN)
```

- **Tests:** JUnit 5 + Testcontainers (PostgreSQL) for repositories/controllers; contract tests
  against the OpenAPI spec; cover the edge cases the PRD calls out (offline sync, USSD/SMS,
  electoral-vs-residence, ID dedup, anonymous sensitive reports). Target ≥80% on core modules.
- **Boundaries:** `ModuleBoundaryTest` fails the build if a module reaches into another module's
  `domain`/`infrastructure`, if a controller has a transaction boundary, if an entity leaks past
  `api`, or if a `domain.port` imports a vendor type.
- **Schema:** never edit an applied Flyway migration — add a new `V<NNN>__<module>_<change>.sql`
  (range-partitioned per module; SQL comments mandatory).
- **Definition of Done (CLAUDE.md §9):** meets its acceptance criteria; **every component is
  documented with Javadoc/TSDoc/dartdoc**; has tests; passes lint + build + tests + SAST locally;
  updates the relevant ADR/OpenAPI/PRD cross-refs; honours security/PDPA + the locked decisions; is
  i18n-ready (SW/EN); and is reviewed (architect for cross-cutting; security for
  identity/PII/tokens/audit/moderation; tanzania-domain-expert for civic correctness).

---

## 8. How to add a feature (gitflow-lite)

1. **Design first.** No non-trivial code without an agreed design — an ADR (in `docs/adr/`) and/or an
   updated section in `docs/`, and for an API change the OpenAPI contract. Confirm the Definition of
   Ready: clear acceptance criteria (PRD US-x.y / UC-x), a design note where it touches >1 module, a
   test approach.
2. **Branch off `develop`:**
   ```bash
   git checkout develop && git pull
   git checkout -b feature/<short-name>
   ```
3. **Build a thin vertical slice** end-to-end (controller → service → domain → migration), inside
   your module(s). Cross a module boundary only via the callee's `com.taarifu.<m>.api` package
   (sync `*QueryApi`) or an event (async, via the outbox) — never its internals. Reference siblings
   by **public UUID**. Keep **no PII** in events/DTOs/logs and **no secrets** in source.
4. **Document every component** with the language's doc comment (Javadoc/TSDoc/dartdoc) — state the
   responsibility and the *why* for any non-obvious decision (security/PDPA/Tanzanian rule/edge case).
5. **Test** (unit on the domain; integration across boundaries with Testcontainers; contract for
   APIs) and keep the build + `ModuleBoundaryTest` GREEN.
6. **Commit** in small, focused, buildable Conventional Commits:
   `type(scope): summary` (`feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `build`, `ci`,
   `perf`, `style`). Reference the PRD/US/UC/ADR where useful. Push occasionally.
7. **Merge back into `develop`** (PR or fast-forward) at integration points; keep features small and
   short-lived; delete the merged branch.
8. **Release to `main` is PR-only**, from `develop`:
   `gh pr create --base main --head develop`. **Never commit or push to `main` directly.**

**Guardrails (do not violate):** don't edit/commit/push `main` directly; don't copy legacy clone
code; no secrets in source; don't bypass method-level authz; don't let tokens gate or buy
democratic-weight actions; don't expose PII or private reports publicly; don't redesign a locked
decision (PRD §19/§25.10) without a superseding ADR.

---

## 9. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Boot fails: JWT secret missing/weak | `TAARIFU_JWT_SECRET` unset or <256-bit. Generate `openssl rand -base64 48` into `.env`. |
| Boot fails: Flyway validation / schema mismatch | An entity diverged from the migrations. Add a new migration; never edit an applied one. Drop the dev DB with `docker compose down -v` to reset. |
| `401` on a "public" read | Pattern not in the security allow-list, or you prefixed `/api/v1` in a context-relative matcher. Public read patterns are GET-only and context-relative. |
| `403 TIER_TOO_LOW` | The action needs a higher live tier (complete profile → T2; verify ID → T3). |
| Staff login returns `MFA_REQUIRED` and you can't proceed | Complete the TOTP step (`/auth/login/totp`) with a code from the dev secret, or set `TAARIFU_MFA_ENFORCED=false` locally. |
| CORS error in the browser | Add your origin to `TAARIFU_CORS_ORIGINS` (comma-separated). Never use `*` with credentials. |
| OTP never arrives | In dev the SMS provider defaults to the logging stub — read the OTP from the backend log. |
| Port 8081/5432/6379 in use | Override `BACKEND_HOST_PORT` / `DB_HOST_PORT` / `REDIS_HOST_PORT` in `.env`, or `SERVER_PORT` for the app. |

---

## 10. Where to go next

- **Product truth & locked decisions:** [PRD.md](../PRD.md) (§17 API, §18 security, §7 RBAC/tiers,
  §19/§25.10 decisions).
- **Architecture:** [docs/architecture/ARCHITECTURE.md](architecture/ARCHITECTURE.md);
  cross-module + outbox in [ADR-0013](adr/ADR-0013-cross-module-integration.md) and
  [ADR-0014](adr/ADR-0014-outbox-event-bus.md).
- **API:** [docs/api/openapi.yaml](api/openapi.yaml) + the live Swagger UI.
- **Launch readiness:** [docs/LAUNCH-READINESS.md](LAUNCH-READINESS.md).
- **The team / specialist subagents:** [TEAM.md](../TEAM.md), `.claude/agents/`.
