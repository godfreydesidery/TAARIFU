# Runbook — Backup, Restore & Disaster Recovery (PostgreSQL/PostGIS)

> The durable citizen data lives in PostgreSQL. **An untested backup is not a backup.**
> Grounding: deploy/README §6/§8, ARCHITECTURE §4 (Flyway/PostGIS, soft-delete),
> PRD §15 (availability), §18 + §25.1 (PDPA: erasure, encryption), D-Q9/R12 (residency),
> R10 (no real PII in non-prod). Cross-links RUNBOOK [`#backup-restore`](../observability/RUNBOOK.md#backup-restore).

---

## 0. RPO / RTO targets (PROPOSED — pending hosting + Legal sign-off, D-Q9)

These are the **proposed** targets the deployment is engineered toward. Final numbers
are a sign-off item with the chosen hosting provider (deploy/README §11).

| Tier | RPO (max data loss) | RTO (max downtime) | How achieved |
|---|---|---|---|
| **Prod (managed DB, target)** | **<= 5 min** | **<= 60 min** | Managed PITR (WAL archiving) + cross-zone standby; restore-to-point-in-time. |
| **Prod (in-cluster DB, fallback)** | **<= 24 h** (last nightly) + WAL if configured | **<= 2 h** | Nightly `pg_dump` to in-country object storage + volume snapshot; manual restore. |
| **Staging** | best-effort (no PII, R10) | best-effort | Nightly logical dump; synthetic data only. |

> **Decision driver:** the managed-DB path is strongly preferred precisely because it
> hits a 5-min RPO without us operating WAL archiving by hand (ADR-0021). If we run the
> in-cluster StatefulSet, RPO degrades to the backup cadence — size the cadence to the
> RPO you can defend to Legal.

---

## 1. Backup (managed DB — preferred)

The managed provider owns automated backups + PITR. Operator actions:
- Confirm **automated daily backups + WAL/PITR are ENABLED** and retained >= 30 days.
- Confirm backups are stored **in the approved jurisdiction**, encrypted at rest (R12).
- **Before every prod migration:** take a manual snapshot (deploy/README §6):
  ```bash
  # provider-specific; e.g. an on-demand snapshot via the provider CLI/console.
  ```

## 2. Backup (in-cluster StatefulSet — fallback)

Logical dump from the DB pod to in-country object storage:
```bash
NS=taarifu-prod; REL=taarifu
POD=$(kubectl -n "$NS" get pod -l app.kubernetes.io/component=db -o jsonpath='{.items[0].metadata.name}')
kubectl -n "$NS" exec "$POD" -- \
  pg_dump -U taarifu -d taarifu -Fc > taarifu-$(date +%Y%m%d-%H%M%S).dump
# then upload the .dump to the in-country, encrypted object store and delete the local copy.
```
Schedule this as a CronJob (target: nightly + pre-migration). PVC volume snapshots
(VolumeSnapshot CRs) are a coarser, faster complement.

---

## 3. Restore (into a fresh/empty DB)

```bash
NS=taarifu-prod; REL=taarifu
# 1. Stop writes: scale the backend to 0 so nothing writes during restore.
kubectl -n "$NS" scale deploy/${REL}-backend --replicas=0
# 2. Restore the dump into the (empty) DB.
POD=$(kubectl -n "$NS" get pod -l app.kubernetes.io/component=db -o jsonpath='{.items[0].metadata.name}')
cat taarifu-<stamp>.dump | kubectl -n "$NS" exec -i "$POD" -- \
  pg_restore -U taarifu -d taarifu --clean --if-exists
# 3. Bring the backend back; Flyway VALIDATES the schema on startup (fails fast on mismatch).
kubectl -n "$NS" scale deploy/${REL}-backend --replicas=3
kubectl -n "$NS" rollout status deploy/${REL}-backend
# 4. Smoke: health UP + a thin read-path check (deploy/README §7).
```
Managed DB: use the provider's **restore-to-point-in-time** to a NEW instance, then
repoint `postgresql.external.host` (values overlay) and `helm upgrade`.

---

## 4. DR drill (do this on a cadence — quarterly target)

1. Restore the latest backup into a **throwaway** instance (never over prod).
2. Boot a backend against it (`TAARIFU_DB_URL` → the throwaway); confirm health UP and
   Flyway `validate` passes (proves schema + data are consistent).
3. Run a thin read-path check (e.g. find-my-rep for a seeded ward).
4. **Time it.** Record actual restore time vs the RTO target; file a ticket if it slips.
5. Tear down the throwaway. Log the drill on the audit trail.

---

## 5. PDPA / residency constraints on backups (R10/R12, §25.1) — non-negotiable

- Backups carry PII. National/voter **ID fields stay ciphertext** (field-level AES-GCM,
  EI-19), but **MSISDN, names, report bodies are present** — so backups are PII.
  Store them **in the approved jurisdiction**, **encrypted at rest**, access-controlled.
- **Right-to-erasure vs restore (the trap):** an erasure (anonymisation, §25.1) must NOT
  be silently re-imported by a restore. **Track erasures** (an append-only erasure log /
  tombstones) so that after any restore you **re-apply** outstanding erasures. Make this
  a step in the restore checklist above. A restore that resurrects erased PII is a PDPA
  incident — note it on the audit trail and remediate immediately.
- **No real PII in non-prod (R10):** never restore a prod backup into dev/staging. Use a
  synthetic/anonymised dataset for non-prod restores.

---

## 6. Region failover (multi-zone / cross-region, where legal allows)

- **Within the in-country region:** rely on the managed DB's cross-zone standby +
  multiple backend replicas spread across nodes (podAntiAffinity). Zone loss → standby
  promotes; backend pods reschedule. Target RTO <= 60 min.
- **Cross-region:** only where Legal permits PII to replicate to that region (R12). If
  not permitted, DR is restore-from-backup **within the same jurisdiction** — accept the
  higher RTO rather than violate residency. **This is a Legal decision, not an SRE one.**

---

## 7. CENTRAL NEEDS (DR sign-off blockers)

- Hosting provider + whether it offers in-country **managed PostGIS with PITR** (D-Q9).
- **RPO/RTO targets ratified** with the provider + Legal (the table in §0 is proposed).
- The **erasure-tracking** mechanism (so restores don't resurrect erased PII) — needs the
  backend's audit/erasure log (L-1, security-privacy-engineer's lane).
- Cross-region replication legality for PII (R12) — Legal.
