# Taarifu — Secrets management (Kubernetes)

> **Golden rule (PRD §18, CLAUDE.md §12): NO secret is ever committed.** Not in this
> chart, not in values, not in git. The Helm chart only references Secrets *by name*
> (`existingSecret`); the Secret objects are created out-of-band by ONE of the two
> patterns below. Both keep ciphertext-only (or no secret at all) in git.

Grounding: `application.yml` (the `TAARIFU_*` env surface), ARCHITECTURE §6 (KMS/JWT),
deploy/README §5, PRD §18, LAUNCH-READINESS §2.2D (KMS/secrets are a **P0** gate).

---

## 1. Which Secrets the chart expects

The chart maps documented keys from two named Secrets onto the backend env:

### `taarifu-backend-secrets` (REQUIRED — backend fails fast at boot without these)

| Secret key | Backend env | Maps to (application.yml) | Notes |
|---|---|---|---|
| `TAARIFU_JWT_SECRET` | `TAARIFU_JWT_SECRET` | `taarifu.security.jwt.secret` | >= 256-bit. Boot FAILS if absent/weak (MF-1). `openssl rand -base64 48` |
| `TAARIFU_CRYPTO_DEV_KEY` | `TAARIFU_CRYPTO_DEV_KEY` | `taarifu.security.crypto.dev-key` | Field-level PII key (EI-19). **Interim** — replace with a real KMS adapter for prod (L-3). |
| `TAARIFU_DB_PASSWORD` | `TAARIFU_DB_PASSWORD` | `spring.datasource.password` | DB password; shared with the in-cluster DB if `deployInCluster=true`. |

### `taarifu-integration-secrets` (optional — only when `backend.secrets.integrations.enabled=true`)

| Secret key | Backend env | Maps to | Channel / use |
|---|---|---|---|
| `TAARIFU_SMS_API_KEY` | `TAARIFU_SMS_API_KEY` | `taarifu.communications.sms.api-key` | SMS aggregator auth (OTP + alerts). Needs shortcode live (R27, D-Q7). |
| `TAARIFU_USSD_GATEWAY_SECRET` | `TAARIFU_USSD_GATEWAY_SECRET` | `taarifu.ussd.gateway.secret` | Server-to-server webhook auth (fail-closed if unset). |
| `TAARIFU_MEDIA_SCANNER_SECRET` | `TAARIFU_MEDIA_SCANNER_SECRET` | `taarifu.media.scan-callback.secret` | Malware-scanner verdict callback auth (MF-3). |
| `SPRING_DATA_REDIS_PASSWORD` | `SPRING_DATA_REDIS_PASSWORD` | `spring.data.redis.password` | Only if the managed Redis requires auth. |
| `SPRING_MAIL_USERNAME` / `SPRING_MAIL_PASSWORD` | same | `spring.mail.*` | SMTP transport (email provider=smtp). |
| `TAARIFU_BOOTSTRAP_ADMIN_PASSWORD` | same | `taarifu.bootstrap.admin.password` | First-ROOT bootstrap (one audited first-boot only; then disable). |

> **Mobile-money (Phase 2 — DB seam only today, D19):** when the token-purchase rails go
> live (PRD §23), the provider credentials (M-Pesa/Tigo/Airtel/HaloPesa consumer key/secret,
> shortcode, passkey) land in a **separate** `taarifu-payments-secrets` with **stricter
> custody + rotation** (money-movement SLO). They are NOT referenced by this MVP chart.

FCM uses a service-account JSON **file**, not an env value — mount it as a Secret volume
(`TAARIFU_FCM_CREDENTIALS_FILE` points at the mount path). See `external-secret.example.yaml`.

---

## 2. Pattern A — Sealed Secrets (Bitnami) — air-gapped / in-country-first friendly

Best when you want the *ciphertext in git* (GitOps) and no external secret store.
The controller's private key lives only in-cluster (in the approved jurisdiction) —
fits D-Q9 (no secret leaves the country).

```bash
# 1. Install the controller once per cluster.
helm repo add sealed-secrets https://bitnami-labs.github.io/sealed-secrets
helm install sealed-secrets sealed-secrets/sealed-secrets -n kube-system

# 2. Author the plaintext Secret LOCALLY (never commit this file — it is git-ignored).
kubectl create secret generic taarifu-backend-secrets \
  --namespace taarifu-prod \
  --from-literal=TAARIFU_JWT_SECRET="$(openssl rand -base64 48)" \
  --from-literal=TAARIFU_CRYPTO_DEV_KEY="$(openssl rand -base64 32)" \
  --from-literal=TAARIFU_DB_PASSWORD="$(openssl rand -base64 24)" \
  --dry-run=client -o yaml > /tmp/taarifu-backend-secrets.plain.yaml

# 3. SEAL it (encrypts with the controller's public cert). The OUTPUT is safe to commit.
kubeseal --controller-namespace kube-system --format yaml \
  < /tmp/taarifu-backend-secrets.plain.yaml \
  > deploy/helm/secrets/sealed/taarifu-backend-secrets.sealed.yaml

# 4. Shred the plaintext; apply the sealed object (the controller decrypts in-cluster).
shred -u /tmp/taarifu-backend-secrets.plain.yaml
kubectl apply -f deploy/helm/secrets/sealed/taarifu-backend-secrets.sealed.yaml
```

The `sealed/` dir holds **only** SealedSecret (ciphertext) objects — committable.
See `sealed-secret.example.yaml` for the shape (placeholder ciphertext).

## 3. Pattern B — External Secrets Operator (ESO) → Vault / cloud KMS

Best when a central secret store (HashiCorp Vault, or a cloud secret manager hosted
in-country) is the source of truth and rotation happens there. ESO syncs a real
K8s Secret from the store; **only the reference (path) is in git**, never the value.

```bash
helm repo add external-secrets https://charts.external-secrets.io
helm install external-secrets external-secrets/external-secrets -n external-secrets --create-namespace
# Configure a SecretStore pointing at your in-country Vault/KMS, then apply the
# ExternalSecret objects in external-secrets/ (they declare which store keys map to
# which Secret keys). See external-secret.example.yaml.
```

---

## 4. Rotation (L-3 — KMS envelope encryption + rotation runbook)

- **JWT signing:** rotate on schedule / on suspected compromise. Prefer asymmetric
  (RS256/ES256) so verification keys can be published and rotated without redeploying
  every client (MF-1, ARCHITECTURE §6.1). With ESO, rotate in Vault → ESO re-syncs →
  rolling restart picks it up.
- **Crypto/PII key (`TAARIFU_CRYPTO_DEV_KEY`):** this is the **interim** field-level key.
  Production target is a real KMS adapter (EI-19) with envelope encryption + a documented
  re-encrypt path. Rotating the data key requires a re-encrypt job — do NOT rotate it
  blind (you will orphan ciphertext). Track in the KMS rotation runbook (L-3, LAUNCH §2.2C).
- **DB / integration creds:** rotate in the managed provider / Vault; ESO re-syncs.
- **NEVER** rotate a secret by editing a committed file — there are none to edit.

## 5. CENTRAL NEEDS (decisions this layer is waiting on)

- Which pattern (Sealed Secrets vs ESO) — depends on whether an **in-country Vault/KMS**
  exists (D-Q9). Sealed Secrets is the zero-dependency default; ESO is better at rotation.
- The **real KMS** for the PII data key (replace `TAARIFU_CRYPTO_DEV_KEY`) — **P0** (L-3).
- Registry pull credentials (an `imagePullSecret`) — see deploy/README §11.
