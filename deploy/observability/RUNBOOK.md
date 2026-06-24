# Taarifu — Operations Runbook

> Written for a tired on-call at 3am on a flaky connection. Lead with the action.
> Grounding: ARCHITECTURE.md §8 (outbox/DLQ), §9 (observability), deploy/README.md
> (§6 migrations, §7 health/smoke, §8 rollback, §9 election neutrality), PRD §15 (SLOs).
> Alert annotations link here by anchor (e.g. `#dlq-replay`).

Health/probe quick reference (context-path `/api/v1`):
- Liveness: `GET /api/v1/actuator/health/liveness`
- Readiness: `GET /api/v1/actuator/health/readiness` (DB)
- Aggregate: `GET /api/v1/actuator/health`
- Metrics (internal only): `GET /api/v1/actuator/prometheus`

---

## Severity ladder

| Severity | Means | Action |
|---|---|---|
| critical | citizen-facing outage / error-budget burn / DLQ growing | page on-call now; declare incident; consider rollback |
| warning | SLO at risk / DLQ non-empty / saturation | investigate within the hour; fix before it escalates |

---

<a id="backenddown"></a>
## BackendDown — `up{job="taarifu-backend"} == 0`

The scraper can't reach the backend's Prometheus endpoint.

1. Is the app actually up? `curl -fsS http://<host>:8081/api/v1/actuator/health` — expect `"status":"UP"`.
2. If health is **UP** but the alert fires, the **metrics endpoint is missing**. Most likely the
   `micrometer-registry-prometheus` runtime dependency is NOT on the classpath (CENTRAL NEED).
   Confirm: `curl -s http://<host>:8081/api/v1/actuator/prometheus | head` — a 404 / empty body = missing dep.
   Fix: add the dependency to `backend/pom.xml`, rebuild, redeploy (this runbook's owner cannot edit pom.xml).
3. If health is **down**: check container/process — `docker compose -f deploy/docker-compose.prod.yml ps`,
   then `... logs --tail=200 backend`. Restart if a transient crash: `... up -d backend`.
4. If it won't start: check DB/Redis health (deps must be healthy first) and recent migrations
   (a Flyway/`ddl-auto=validate` mismatch fails fast — see `#migration-failed`).
5. Still down after a deploy -> **rollback** (`#rollback`).

---

<a id="http5xx"></a>
## HttpHigh5xxErrorRate — 5xx ratio > 1%

Error budget is burning (PRD §15: 99.9% monthly).

1. Which endpoints? Grafana "Request rate by status class" + drill the `http_server_requests` by `uri`.
2. Correlate with a recent deploy (`#rollback`), a DB/Redis blip, or an adapter outage
   (SMS/NIDA/FCM/payments). Graceful degradation should keep reads alive (deploy/README §7) —
   if a dependency is down and the citizen path hard-failed, that is a bug to file.
3. Mitigate: roll back the suspect release; or scale out; or disable the failing optional adapter
   (feature flag) so the path degrades instead of 500ing.
4. Confirm the ratio falls below 1% and the alert resolves.

---

<a id="latency"></a>
## HttpReadLatencyP95High / HttpWriteLatencyP95High

Read p95 > 500ms or write p95 > 1s (PRD §15).

1. DB first: slow queries, lock waits, connection-pool exhaustion. Check Postgres
   (`pg_stat_activity`), pool metrics, and recent migrations that added scans.
2. Redis latency (OTP/idempotency/USSD path).
3. Downstream adapter latency (per-adapter dashboards, DI6) — an open circuit should shed fast.
4. Saturation: see `#jvm` (CPU/heap). Scale out if saturated.
5. If a release regressed latency -> `#rollback`.

---

<a id="dlq-replay"></a>
## OutboxDlqNonEmpty / OutboxDlqGrowing — replay the DLQ

The gauge `taarifu_outbox_failed` > 0 means one or more outbox events hit the attempt cap and went
**terminal FAILED** (the dead-letter queue). The citizen's transaction already committed (DI3); only the
**side-effect** (notification, feed fan-out, SLA clock, routing) was dropped. **Fix the cause first, then replay** —
replaying before the cause is fixed just re-fails the rows.

**Step 1 — find the cause.** The FAILED rows carry `last_error` and `event_type`. Inspect via the DB
(do NOT log payloads — privacy, PRD §18):
```sql
SELECT event_type, count(*) , max(processed_at) AS last_failed
FROM outbox_event WHERE status = 'FAILED' GROUP BY event_type ORDER BY 2 DESC;
```
Common causes: a downstream dependency was down (now recovered) or a handler bug (now deployed).

**Step 2 — fix it.** Recover the dependency or deploy the handler fix. Confirm the cause is gone before replaying.

**Step 3 — replay.** Replay re-queues `FAILED -> PENDING` (attempts reset, due immediately); it is
idempotent and pins `WHERE status='FAILED'`, so a PROCESSED row is never re-fired (no duplicate effect).
Handlers must still dedup on `eventId` (at-least-once).

> NOTE: there is **no admin HTTP endpoint** for replay yet (the `OutboxReplayService` bean exists in
> `com.taarifu.common.outbox`; the admin/ops surface is a follow-up — see the service's "CENTRAL NEEDS" note).
> Until that ships, replay is invoked operationally one of two ways:

- **Preferred (when the admin replay endpoint exists):** call it scoped by `eventType` and re-run until it returns 0.
- **Interim (no endpoint):** re-queue in SQL inside a transaction (mirrors `requeueFailedBatch`: reset to a
  clean PENDING slate so the relay re-dispatches on its next poll). Bound the batch so the relay doesn't surge:
```sql
-- Replay one bounded batch of the whole DLQ (repeat until 0 rows updated).
UPDATE outbox_event
SET status = 'PENDING', attempts = 0, last_error = NULL,
    processed_at = NULL, next_attempt_at = now()
WHERE id IN (
  SELECT id FROM outbox_event WHERE status = 'FAILED'
  -- AND event_type = '<EVENT_TYPE>'    -- optional: scope to one handler's failures
  ORDER BY id LIMIT 100
);
```
**Step 4 — watch.** The gauge should drain toward 0 within a few relay polls. If rows re-FAIL, the cause is
not fixed — stop and go back to Step 1.

---

<a id="jvm"></a>
## JvmHeapPressure / ProcessCpuHigh

1. Heap > 90%: check for a leak (heap dump if recurring) vs. under-provisioned `-Xmx`. If legitimate load,
   raise the memory request/limit and `up -d` (or scale out).
2. CPU > 85% sustained: profile the hot path; scale out (add an instance) if it's genuine load.
3. Watch for `OOMKilled` / CrashLoopBackOff — those page as `BackendDown`.

---

<a id="mfa"></a>
## MFA-enforced reminder (do this on EVERY prod/staging deploy)

`TAARIFU_MFA_ENFORCED` **must be `true`** in prod and staging (N-4). It may be set `false` ONLY for local
testing. After any deploy, confirm it is not accidentally disabled:
```bash
docker compose -f deploy/docker-compose.prod.yml exec backend printenv TAARIFU_MFA_ENFORCED   # expect: true
```
If it shows `false` in a real environment: fix `deploy/.env.prod` (or the secret manager) immediately and
redeploy — staff accounts are without a second factor until you do. This is a security incident if it was
live for any window; note it on the audit trail.

---

<a id="backup-restore"></a>
## Backup & restore — Postgres volume

The durable citizen data lives in the `taarifu-db-data` named volume. Back it up before every prod migration
(deploy/README §6) and on a schedule. RPO/RTO targets are a sign-off item with the hosting provider (D-Q9, R12).

**Logical backup (preferred — portable, point-in-time-ish):**
```bash
# Dump (run from the host; writes a compressed dump next to deploy/)
docker compose -f deploy/docker-compose.prod.yml exec -T db \
  pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc > taarifu-$(date +%Y%m%d-%H%M%S).dump
```
**Restore (into a fresh/empty DB):**
```bash
# 1. Stop the backend so nothing writes during restore.
docker compose -f deploy/docker-compose.prod.yml stop backend
# 2. Restore the dump.
cat taarifu-<stamp>.dump | docker compose -f deploy/docker-compose.prod.yml exec -T db \
  pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists
# 3. Bring the backend back; Flyway validates the schema on startup.
docker compose -f deploy/docker-compose.prod.yml up -d backend
# 4. Smoke: GET /api/v1/actuator/health -> UP, then a thin read-path check.
```
**Volume snapshot (filesystem-level, host-only, app stopped):**
```bash
docker compose -f deploy/docker-compose.prod.yml stop backend db
docker run --rm -v taarifu-db-data:/data -v "$PWD":/backup alpine \
  tar czf /backup/db-volume-$(date +%Y%m%d-%H%M%S).tgz -C /data .
docker compose -f deploy/docker-compose.prod.yml up -d db backend
```
> **PDPA / residency (R10/R12):** backups carry PII (encrypted ID fields stay ciphertext, but MSISDN etc. are
> present). Store backups IN the approved jurisdiction, encrypted at rest. A right-to-erasure request must not
> be silently re-imported by a restore — track erasures so a restore can re-apply them.
> **DR drill:** restore into a throwaway instance on a cadence and verify health — an untested backup is not a backup.

---

<a id="migration-failed"></a>
## Migration failed on startup

`ddl-auto=validate` + Flyway `validate-on-migrate` fail fast on a schema/entity mismatch (deploy/README §6).
1. Read the startup log — it names the offending table/column or migration.
2. Prefer **roll forward** with a corrective forward-only migration. Only apply a down-migration if it was
   validated. Otherwise restore the pre-deploy snapshot to a new instance and fail over (`#backup-restore`).
3. Never edit an applied migration — add a new `V<NNN>__...` (ARCHITECTURE §4.1).

---

<a id="rollback"></a>
## Rollback (one command)

The backend is stateless; rollback = redeploy the previous known-good **image tag**.
```bash
# Set TAARIFU_IMAGE in deploy/.env.prod to the previous good tag, then:
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod up -d backend
```
If a **migration** caused the issue, prefer rolling forward with a fix; a schema rollback may need a restore
(`#backup-restore`). Use **expand -> migrate -> contract** so an app rollback never breaks the DB (deploy/README §6).

---

## Election-period neutrality (deploy/README §9)

During election periods: **change freeze** (no non-critical deploys), heightened monitoring, capacity headroom,
and no infra action that could be read as favouring any party. All admin/infra actions stay on the immutable
audit trail (PRD §638). During a freeze, only critical-severity incident fixes ship — document the justification.
