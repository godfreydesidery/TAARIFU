# Runbook — Deploy & Rollback (Helm/Kubernetes)

> Grounding: deploy/README §5/§7/§8, ARCHITECTURE §9, PRD §15/§16, CLAUDE.md §12.
> The backend is **stateless** — rollback = redeploy the previous good image/revision.
> A **migration** issue is different: see [backup-restore-dr.md](backup-restore-dr.md).

Set once per shell:
```bash
NS=taarifu-prod              # or taarifu-staging
REL=taarifu
```

---

## 1. Normal deploy (promote the SAME scanned image)

CI/CD (`.github/workflows/cd.yml`) does this automatically (gated). Manual path:
```bash
helm upgrade --install "$REL" deploy/helm/taarifu \
  --namespace "$NS" \
  -f deploy/helm/taarifu/values-prod.yaml \
  --set backend.image.repository="<registry>/taarifu-backend" \
  --set backend.image.tag="<git-sha>" \
  --atomic --wait --timeout 12m
```
- `--atomic` auto-rolls-back the release if the rollout is not healthy within `--timeout`
  (deploy/README §8). `--wait` blocks until pods are Ready.
- Always deploy the **immutable git-sha** CI built+scanned — never `:latest`. The chart
  **refuses** an empty `backend.image.tag` (fail-closed).

Verify (deploy/README §7):
```bash
kubectl -n "$NS" rollout status deploy/${REL}-backend
curl -fsS https://<host>/api/v1/actuator/health | grep -q '"status":"UP"' && echo OK
```

**Error-budget gate (PRD §15):** if, over the 10 min after deploy, 5xx > 1% or read p95
> 500 ms (Grafana / `HttpHigh5xxErrorRate` / `HttpReadLatencyP95High`), **roll back**.

---

## 2. One-command rollback (app-level regression)

```bash
helm history "$REL" -n "$NS"             # find the last good REVISION
helm rollback "$REL" <PREV_REVISION> -n "$NS" --wait --timeout 10m
# or roll back exactly one step:
helm rollback "$REL" 0 -n "$NS" --wait --timeout 10m
```
From CI: run the **CD** workflow with `workflow_dispatch` → `rollback=true`, pick the env.

Confirm health UP and the alert clears. The previous image is still in the registry
(immutable tags), so this is instant and reversible.

---

## 3. Deploy went wrong — decision tree

1. **Pods not Ready / CrashLoopBackOff** (`KubePodCrashLooping`):
   ```bash
   kubectl -n "$NS" get pods -l app.kubernetes.io/component=backend
   kubectl -n "$NS" logs deploy/${REL}-backend --tail=200
   kubectl -n "$NS" describe pod <pod>      # events: OOMKilled? ImagePullBackOff? probe fail?
   ```
   - `OOMKilled` → RUNBOOK [`#jvm`](../observability/RUNBOOK.md#jvm); raise memory limit.
   - `ImagePullBackOff` → wrong tag / missing imagePullSecret (deploy/README §11).
   - Readiness failing but liveness OK → DB unreachable (readiness group includes DB).
     Check the DB Service/managed endpoint + the `TAARIFU_DB_*` secret.
   - Boot fails fast on a missing secret → the core Secret is absent/incomplete
     (deploy/helm/secrets/). Boot also fails fast on a weak JWT secret (MF-1).

2. **Schema/entity mismatch on startup** (Flyway/`ddl-auto=validate`): the pod logs name
   the migration/table. → RUNBOOK [`#migration-failed`](../observability/RUNBOOK.md#migration-failed).
   Prefer roll-forward with a corrective migration; a schema rollback may need a restore
   ([backup-restore-dr.md](backup-restore-dr.md)). `--atomic` already reverted the *app*,
   but the DB change may have applied — read the log.

3. **App is Ready but unhealthy / error budget burning** → `helm rollback` (§2).

---

## 4. MFA-enforced check (EVERY prod/staging deploy)

`TAARIFU_MFA_ENFORCED` MUST be `true` (N-4). The chart defaults it to `"true"`; confirm
it was not overridden:
```bash
kubectl -n "$NS" exec deploy/${REL}-backend -- printenv TAARIFU_MFA_ENFORCED   # expect: true
```
If `false` in a real env: fix the values overlay, redeploy, and note it on the audit
trail (RUNBOOK [`#mfa`](../observability/RUNBOOK.md#mfa)).

---

## 5. Helm chart sanity before a deploy

```bash
helm lint deploy/helm/taarifu -f deploy/helm/taarifu/values-prod.yaml --set backend.image.tag=test
helm template "$REL" deploy/helm/taarifu -f deploy/helm/taarifu/values-prod.yaml \
  --set backend.image.tag=<git-sha> | kubectl apply --dry-run=server -f -
```

---

## 6. Election-period freeze (deploy/README §9)

During an election period the CD prod job is blocked by the `CHANGE_FREEZE` repo variable.
Only a critical incident fix ships, with `CHANGE_FREEZE_OVERRIDE=true` and a documented
justification on the audit trail. No infra action that could be read as partisan.
