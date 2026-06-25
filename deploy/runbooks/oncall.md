# Runbook — On-call & Incident Command

> Calm incident command for a citizen-facing civic platform. Grounding:
> ARCHITECTURE §9, deploy/README §9 (election neutrality), PRD §15 (SLOs), §18/§638
> (immutable audit trail). Alerts page via Alertmanager (`../observability/alertmanager.yml`).

---

## Severity ladder (matches the alert `severity` label)

| Severity | Means | First action | Comms |
|---|---|---|---|
| **critical** | citizen-facing outage, error-budget burn, DLQ growing, CrashLoop, PVC full | page on-call NOW; declare incident; consider rollback | open incident channel; status update within 15 min |
| **warning** | SLO at risk, DLQ non-empty, saturation, HPA maxed | investigate within the hour; fix before it escalates | note in ops channel |

Alertmanager routes `critical → oncall-page`, `warning → chat-warnings`
(`../observability/alertmanager.yml`). A critical inhibits the matching warning.

---

## Incident loop (calm, repeatable)

1. **Acknowledge** the page. Stop the bleeding before diagnosing.
2. **Assess** blast radius: which citizen path? (file a report / find-my-rep / login /
   notifications). Graceful degradation should keep reads alive (deploy/README §7) — if
   the citizen path hard-failed on a dependency outage, that is a bug to file.
3. **Mitigate first, fix second.** The fastest safe mitigations:
   - Recent deploy suspected → **rollback** ([deploy-and-rollback.md](deploy-and-rollback.md) §2).
   - One optional adapter down (SMS/FCM/email) → it should self-degrade (circuit-breaker).
     If it is hard-failing a path, disable that provider (flip the provider env to `logging`)
     and redeploy so the path degrades instead of 500ing.
   - Saturation → HPA should scale; if maxed, raise `maxReplicas` (deploy/README §9 headroom).
   - DB unreachable → check the managed endpoint / Service + the `TAARIFU_DB_*` secret.
4. **Communicate** on a cadence (every 15–30 min for a critical). Lead with citizen impact.
5. **Resolve**: confirm the alert clears + the SLO recovers.
6. **Post-incident review** (blameless): timeline, root cause, what masked it, action items.
   Record on the **immutable audit trail** for infra/admin actions (PRD §638).

---

## Per-alert next step (the runbook anchors)

| Alert | Go to |
|---|---|
| `BackendDown` / `KubePodCrashLooping` | [RUNBOOK #backenddown](../observability/RUNBOOK.md#backenddown) |
| `HttpHigh5xxErrorRate` | [RUNBOOK #http5xx](../observability/RUNBOOK.md#http5xx) |
| `HttpReadLatencyP95High` / `HttpWriteLatencyP95High` | [RUNBOOK #latency](../observability/RUNBOOK.md#latency) |
| `OutboxDlqNonEmpty` / `OutboxDlqGrowing` | [RUNBOOK #dlq-replay](../observability/RUNBOOK.md#dlq-replay) |
| `JvmHeapPressure` / `ProcessCpuHigh` / `KubePodOOMKilled` / `HpaMaxedOut` | [RUNBOOK #jvm](../observability/RUNBOOK.md#jvm) |
| `KubeDeploymentRolloutStuck` | [deploy-and-rollback.md](deploy-and-rollback.md) §3 |
| `PersistentVolumeFillingUp` | [backup-restore-dr.md](backup-restore-dr.md) |

---

## Escalation

1. Primary on-call (page). 2. Secondary on-call (15 min no-ack). 3. SRE lead. 4. For
PII/security/erasure → **security-privacy** owner. For routing/data-correctness →
**Eng**. For residency/legal → **Legal/Program**. Keep the loop small; one incident
commander at a time.

---

## Election-period operational neutrality (deploy/README §9) — STRICT

During election periods:
- **Change freeze** — only critical incident fixes ship (CD prod blocked by `CHANGE_FREEZE`;
  override only with documented justification on the audit trail).
- **Heightened monitoring** + capacity **headroom** (raise `minReplicas`/`maxReplicas`
  ahead of expected spikes; do NOT wait for the HPA to chase a surge).
- **No infra action that could be read as favouring any party.** All admin/infra actions
  stay on the immutable audit trail (PRD §638).
