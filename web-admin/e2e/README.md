# Taarifu admin console — Playwright E2E

Browser end-to-end tests for `web-admin`. These drive the real app in Chromium against a running
backend, catching integration bugs that the unit/API tests can't (CORS, token→role gating, i18n).

> These tests found 4 real bugs on first run: a CORS allow-list gap, a missing backend `RoleHierarchy`
> (ROOT locked out of moderation/reports), the i18n loader's `NG0200` DI cycle, and the `trustTier`
> claim mismatch. The specs now also guard against each regressing.

## Prerequisites
1. **PostgreSQL+PostGIS**: `docker compose up -d db` (repo root).
2. **Backend** on `:8081`, dev profile, MFA off for local:
   ```bash
   cd backend
   set -a; . ../.env; set +a   # .env holds the generated secrets + TAARIFU_MFA_ENFORCED=false
   export SPRING_PROFILES_ACTIVE=dev TAARIFU_DB_USER=taarifu TAARIFU_DB_PASSWORD=taarifu
   ./mvnw -q compile spring-boot:run
   ```
   The `@Profile("dev")` `DevAdminSeeder` creates the login: phone `+255700000001` / password `Admin@12345`.
3. **web-admin** dev server: `cd web-admin && npm start` (serves `:4200`; the dev proxy forwards `/api/v1` → `:8081`).

## Run
```bash
cd web-admin/e2e
npm install
npm run install:browser     # one-time: downloads Chromium
npm run test:e2e
```
Override the dev-server port (e.g. if `:4200` is taken): `E2E_BASE_URL=http://localhost:4300 npm run test:e2e`.

Screenshots are written to `e2e/shots/` (git-ignored).
