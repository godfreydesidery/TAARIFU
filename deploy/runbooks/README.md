# Taarifu — Runbooks index

> Operational runbooks for the **orchestrated (Helm/Kubernetes) production topology**
> (the "grow-to" rung, deploy/README §5; ADR-0021). Written for a tired on-call at
> 3am on a flaky connection: lead with the command, then the why.

| Runbook | When to open it |
|---|---|
| [deploy-and-rollback.md](deploy-and-rollback.md) | Deploying a release; a deploy went wrong; one-command rollback. |
| [backup-restore-dr.md](backup-restore-dr.md) | Postgres backup/restore, RPO/RTO, DR drill, region failover. |
| [oncall.md](oncall.md) | Severity ladder, incident comms, escalation, election-period freeze. |

**Sibling (alert-driven, topology-agnostic):**
[`../observability/RUNBOOK.md`](../observability/RUNBOOK.md) — every Prometheus alert
links there by anchor (`#http5xx`, `#dlq-replay`, `#jvm`, `#backup-restore`, ...). These
runbooks cross-link into it rather than duplicate it (DRY).

Health/probe quick reference (context-path `/api/v1`):
- Liveness: `GET /api/v1/actuator/health/liveness`
- Readiness: `GET /api/v1/actuator/health/readiness` (DB)
- Aggregate: `GET /api/v1/actuator/health`
- Metrics (internal only): `GET /api/v1/actuator/prometheus`
