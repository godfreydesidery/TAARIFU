# ADR-0021: Production deployment topology — start-lean compose, grow to a Helm/Kubernetes chart; managed in-country Postgres; secrets via Sealed/External Secrets; CD with one-command rollback

> **Numbering note:** authored as "ADR-0019" per the originating task, but ADR-0019
> (USSD completion) and ADR-0020 (analytics reads) were taken by concurrent Phase-2
> wave-1 work; renumbered to **ADR-0021** to avoid a collision. No content change.

**Status:** Accepted · 2026-06-25 · DevOps/SRE (Peter Nyerere)
**Grounding:** PRD §15 (NFRs/SLOs: 99.9% monthly availability, reads p95 < 500ms, writes p95 < 1s; cost-efficiency; observability), §16 ("start lean — managed VM + systemd/compose; grow to orchestrated containers when load + team justify it"), §18 (no secrets in source; TLS; CORS allow-list; PII encrypted at rest), §21 (graceful degradation), §23 (Phase-2 money rails get stricter SLOs/custody), D-Q9 (in-country-where-feasible hosting; cloud-portable), R10 (no real PII in non-prod), R12 (residency = Legal sign-off), R27 (shortcode/registration lead time). ARCHITECTURE.md §2 (pinned stack), §8 (outbox/DLQ), §9 (observability/health), §10 (extract-to-service). deploy/README.md (the start-lean compose topology this ADR's chart grows from). LAUNCH-READINESS.md §2.2D/P3/P4 (KMS/hosting/backups/alerts are P0/P1 gates). CLAUDE.md §5 (CI gates), §12 (guardrails).
**Builds on:** ADR-0005 (Flyway + `ddl-auto=validate` — migrations deploy with the app), ADR-0014 (outbox/DLQ — the metrics this layer alerts on), ADR-0011 (JWT/MF-1 — the secret the chart fail-fasts on).

## Context

The platform is a **modular monolith** (one deployable, ARCHITECTURE §1) with PostgreSQL+PostGIS, Redis, an Angular admin SPA, and a Flutter app. `deploy/` already ships the **start-lean topology**: a `docker-compose.prod.yml` single host (db + redis + backend + Prometheus/Alertmanager/Grafana), `.env.prod.example`, an alert-driven `RUNBOOK.md`, alert rules, and a Grafana overview dashboard. CI (`ci.yml`) gates build → test → SAST → container-scan and pushes opportunistically.

What is **missing for the in-country production rung** (the LAUNCH-READINESS P0/P1 ops gates, D-Q9 enabler): an **orchestrated topology** for when concurrent load + the team justify horizontal scale (HPA, PDB, rolling deploys, network isolation); a **secrets layer** for Kubernetes that keeps zero secrets in git; a **delivery (CD) pipeline** that promotes the *same scanned image* to staging behind an approval gate with **one-command rollback**; **alerting + dashboards as code** for the SLOs incl. the outbox DLQ; **on-call + DR runbooks** with RPO/RTO; and a recorded **topology decision** for where PII-bearing Postgres lives (D-Q9/R12).

Forces:

1. **KISS / right-sizing (PRD §16, ARCHITECTURE §1).** Do not impose Kubernetes before load + team justify it. The compose host stays the default; Kubernetes is the *grow-to* rung, with a **clean migration seam** so cutover is config, not rewrite.
2. **Residency is a first-class constraint (D-Q9, R12).** PII (NIDA/voter IDs, MSISDN, evidence) stays in-country where feasible; the design must be **cloud-portable** so no single vendor/jurisdiction is load-bearing. Where PII physically lands is a **Legal sign-off**, not an SRE default.
3. **No secret in source, ever (PRD §18, CLAUDE.md §12).** The prior repos logged root passwords and shipped public actuator + CORS `*`-with-credentials. Design those out: the chart references secrets **by name only**; the Secret objects come from Sealed/External Secrets.
4. **SLOs + reversibility (PRD §15, §16).** 99.9% availability and p95 budgets demand HA (>1 replica, PDB, anti-affinity, HPA), probes that gate traffic on real readiness (incl. DB), and a **rollback path that is one command**.
5. **Migrations deploy with the app (ADR-0005).** Flyway runs on startup then `validate` fails fast. The topology must tolerate a slow first-boot migrate (startup probe) and must NOT auto-DDL.

## Decision

### 1. Two topologies, one seam — start lean, grow to Helm/Kubernetes

- **Lean (default, today):** `deploy/docker-compose.prod.yml` single host. Unchanged.
- **Orchestrated (grow-to):** a **Helm chart** at `deploy/helm/taarifu` deploying the backend (Deployment + HPA + PDB), Redis, the Angular admin static host, and **optionally** an in-cluster PostGIS StatefulSet — behind an ingress with TLS (cert-manager) and a deny-by-default NetworkPolicy. The **same immutable, scanned image** runs in both; the cutover is choosing which `deploy/...` to apply. The trigger to adopt Kubernetes is the same extract-to-service spirit as ARCHITECTURE §10: do it when load/HA/independent-scaling justify it, not before.

### 2. Database placement — managed, in-country Postgres preferred; in-cluster is a fallback

The chart defaults `postgresql.deployInCluster=false`: production **connects to a managed, in-country PostGIS** so backups/PITR/HA are the provider's concern and the residency footprint is explicit (D-Q9, R12). The in-cluster StatefulSet (single replica) exists only for **dev clusters and air-gapped in-country racks** with no managed PostGIS — never a national prod load without a real operator (e.g. CloudNativePG). This keeps the durable, PII-bearing store on infrastructure Legal can sign off, and keeps us portable (the JDBC URL is the only thing that moves).

### 3. Probes, scale, resilience (PRD §15)

- **startupProbe** (liveness path, ~150s budget) shields a slow Flyway migrate from being killed; **livenessProbe** is cheap and excludes deps (a slow DB must not kill the pod); **readinessProbe** hits the readiness group `readinessState,db` (application.yml) so traffic only routes to a pod that can serve.
- **HPA** (CPU+memory, scale-down stabilisation) for announcement-burst/election spikes; **PDB** `minAvailable: 1`; **podAntiAffinity** spreads replicas; **RollingUpdate maxUnavailable: 0** for zero-downtime; **resource requests/limits** set (scheduler + HPA + OOM safety); JVM `MaxRAMPercentage` so it respects the cgroup limit.

### 4. Secrets — referenced by name; created by Sealed Secrets or External Secrets

The chart **never** contains a secret value; it references `existingSecret` names and maps documented keys (`TAARIFU_JWT_SECRET`, `TAARIFU_CRYPTO_DEV_KEY`, `TAARIFU_DB_PASSWORD`, + integration keys) onto env. The Secret objects are created out-of-band by **(A) Sealed Secrets** (ciphertext in git; controller key stays in-cluster/in-country — the zero-dependency default) or **(B) External Secrets Operator** (only the Vault/KMS *reference* in git; better rotation). `deploy/helm/secrets/` ships the guidance + placeholder examples + a `.gitignore` that blocks plaintext. The interim `TAARIFU_CRYPTO_DEV_KEY` is flagged for replacement by a real **KMS** adapter (L-3, a P0 gate).

### 5. Delivery (CD) — promote the same image, gated, with one-command rollback

`.github/workflows/cd.yml`: **build+test (Testcontainers ITs) → build image → Trivy scan (gate) → push immutable `:git-sha` → deploy staging** (bound to the `staging` GitHub Environment for required-reviewer approval) → **deploy prod on a release tag** (bound to `production`, behind an **election change-freeze** check). Deploys use `helm upgrade --install --atomic --wait` (auto-rollback on a failed rollout) + a post-deploy **health smoke** (rolls back if not UP). A `workflow_dispatch rollback=true` job does an on-demand **`helm rollback`** (one command). The deploy step **fails closed** until hosting creds (D-Q9) are signed off — dry-run with no creds; never invents credentials.

### 6. Observability & runbooks as code

The existing app/SLO alert rules (`alerts.yml`: 5xx, latency p95, outbox DLQ, JVM) apply to **both** topologies (DRY). A new `alerts-k8s.yml` adds platform alerts (CrashLoop, stuck rollout, OOMKilled, HPA maxed, PVC full). A second Grafana dashboard (`taarifu-adapters-slo.json`) adds error-budget burn, outbox throughput, per-adapter circuit state (DI6), HikariCP, and pod-Ready. On-call + deploy + **backup/restore + DR (with proposed RPO/RTO)** runbooks live in `deploy/runbooks/`, cross-linking the alert-anchored `observability/RUNBOOK.md`.

## Consequences

**Positive:** a real in-country production rung that honours D-Q9/R12 and the §15 SLOs; HA + reversibility (atomic deploy + one-command rollback); zero secrets in git by construction; alerting/dashboards/runbooks as code; a clean lean→orchestrated seam so we adopt Kubernetes only when justified.

**Negative / costs:** Kubernetes is operational surface the team must be ready for — the lean compose path remains valid until that readiness exists. NetworkPolicy needs a capable CNI (Calico/Cilium). The in-cluster DB option is deliberately weak (single replica) to discourage running prod on it.

**Fail-closed guardrails:** chart refuses an empty/`:latest` image tag; backend boots only with the required secrets (and a strong JWT secret, MF-1); CD deploy dry-runs without creds; prod blocked during a change freeze; `TAARIFU_MFA_ENFORCED` defaults true.

## CENTRAL NEEDS (decisions this ADR is blocked on — ask, do not invent)

1. **Hosting provider + final PII residency placement (D-Q9, R12)** — and whether it offers **in-country managed PostGIS with PITR**. Drives DB placement (§2), RTO/RPO (§0 of the DR runbook), and the egress CIDRs in the NetworkPolicy. **Owner: Legal + SRE. Blocks prod.**
2. **Container registry choice + credentials** (ghcr.io / in-country registry) — the `REGISTRY*` + `imagePullSecret`. CD scans but cannot push/deploy without it. **Owner: SRE.**
3. **Secret-store pattern** — Sealed Secrets (default) vs External Secrets → depends on whether an **in-country Vault/KMS** exists. And the **real KMS** for the PII data key (replace `TAARIFU_CRYPTO_DEV_KEY`, L-3, P0). **Owner: SRE + Security.**
4. **RTO/RPO ratification + DR-drill cadence** with the provider + Legal (the DR runbook §0 numbers are proposed). **Owner: SRE + Legal.**
5. **Orchestration go-decision** — when do load + team readiness justify cutting from compose to this chart? Until then the chart is reviewed-and-ready, not the running prod. **Owner: SRE.**
