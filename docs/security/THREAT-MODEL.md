# Taarifu — Threat Model (STRIDE) & Launch Security Gate

| | |
|---|---|
| **Owner** | Salim Juma — Senior Security & Privacy Engineer |
| **Scope** | Backend modular monolith (`com.taarifu.*`), as implemented on `develop` |
| **Frame** | PRD §18 (Security/Privacy/Compliance), §21 (integrations DI1–DI7, EI-1…EI-19), §24 (responder data-sharing), §25 (edge cases), §26 (risks R10–R13, R27) |
| **Method** | STRIDE per trust boundary + a ranked residual-risk register; companion privacy analysis in [DPIA.md](DPIA.md) |
| **Status** | Living document — re-run per feature touching identity / PII / tokens / audit / moderation (CLAUDE.md §11) |

> This model is grounded in the **code as it exists**, not the aspiration. Where a control is implemented it is named with its class; where it is a residual gap it is called out plainly and carried into the launch gate. The single product source of truth is [PRD.md](../../PRD.md).

---

## 1. System overview & assets

Taarifu connects citizens to representatives and government/parastatal/private responders. The crown-jewel assets, ranked by breach impact (R10 > everything):

| # | Asset | Sensitivity | Where it lives |
|---|---|---|---|
| A1 | **National ID / voter ID** (`idNo`, `idType`) | **Highest** (R10 — catastrophic) | `profile.id_no` (field-encrypted), blind index `profile.id_hash` |
| A2 | **Verification evidence** (document/selfie refs) | Highest | object store (signed URLs), `VerificationRequest` |
| A3 | **MSISDN / phone, email** | High PII | `user.phone` (unique), `user.email` |
| A4 | **ProfileLocation** (home/residence pins, GPS) | High PII (§9.0 — never public) | `profile_location` |
| A5 | **Credentials / tokens** (BCrypt hash, refresh tokens, TOTP secret) | High | `user.password_hash`, `refresh_token`, `user` MFA secret |
| A6 | **Audit log** (tamper-evident) | Integrity-critical | `audit_event` (append-only, hash-chained) |
| A7 | **Binding civic actions** (signatures, ratings, electoral scope) | Integrity-critical (R5/R14/R15/R18) | engagement + accountability modules |
| A8 | **Sensitive/anonymous reports** (corruption, GBV) | Safety-critical (§25.3) | `report` (forced PRIVATE) |
| A9 | **Report content + attachments** (PII-bearing) | Medium–High | `report`, `media_object` |

---

## 2. Trust boundaries (data-flow diagram)

```
                                  ┌──────────────────────────── INTERNET (untrusted) ───────────────────────────┐
                                  │                                                                              │
   ┌────────────┐  TLS    ┌───────▼────────┐   ┌──────────────┐   ┌───────────────┐   ┌────────────────────────┐ │
   │ Citizen    │────────▶│ TB-1 PUBLIC    │   │ TB-2 AUTH/OTP│   │ TB-3 USSD      │   │ TB-4 ADMIN / STAFF     │ │
   │ app / PWA  │         │ REST API       │   │ signup/login │   │ webhook (S2S)  │   │ console (MFA-gated)    │ │
   └────────────┘         │ permitAll GETs │   │ refresh/MFA  │   │ aggregator     │   │ ADMIN/MOD/ROOT         │ │
   ┌────────────┐         └───────┬────────┘   └──────┬───────┘   └──────┬────────┘   └───────────┬────────────┘ │
   │ Feature    │  USSD/SMS                            │                  │                        │              │
   │ phone      │─────────────────────────────────────┼──────────────────┘                        │              │
   └────────────┘                                      │                                           │              │
                                  ┌───────────────────▼───────────────────────────────────────────▼───────────┐ │
                                  │              JwtAuthenticationFilter (stateless) + @EnableMethodSecurity     │ │
                                  │              deny-by-default · @PreAuthorize · @RequiresTier · ScopeGuard    │ │
                                  └───────────────────────────────────┬──────────────────────────────────────--┘ │
                                                                      │  (in-process module boundaries)           │
   ┌──────────────────────────────────────────────────────────────---▼─────────────────────────────────────────┐ │
   │  identity · geography · reporting · responders · engagement · accountability · moderation · communications   │ │
   │  tokens · media · analytics · admin · institutions · ussd        + common (security/audit/crypto/outbox)     │ │
   └───────┬──────────────┬──────────────┬───────────────┬───────────────┬──────────────┬───────────────────────┘ │
           │ TB-7 CRYPTO   │ TB-6 MEDIA    │ TB-5 NIDA/VOTER│ TB-8 PAYMENTS  │ TB-9 MODERATION│ TB-10 OUTBOX/EVENTS    │ │
           ▼              ▼              ▼               ▼ (Phase 2)      ▼                ▼                        │
   ┌──────────────┐ ┌──────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────────────────┐ │
   │ KMS / Vault  │ │ ObjectStr│ │ IdentityVeri-│ │ M-Pesa/Tigo/ │ │ ContentSafety│ │ outbox → bus → handlers   │ │
   │ (EI-19) PII  │ │ +Malware │ │ ficationProv │ │ Airtel/Card  │ │ port (EI-18) │ │ (no-PII payloads)         │ │
   │ field keys   │ │ Scanner  │ │ (EI-1/EI-2)  │ │ (EI-19 sec)  │ │              │ │                          │ │
   └──────────────┘ └──────────┘ └──────────────┘ └──────────────┘ └──────────────┘ └──────────────────────────┘ │
                                  └───────────────────────── DATA STORES: PostgreSQL · Redis · S3 ───────────────┘
```

**Boundary inventory:**

| TB | Boundary | Trust transition | Primary STRIDE focus |
|---|---|---|---|
| TB-1 | Public REST API | anonymous → read public civic data | Information disclosure, Tampering |
| TB-2 | Auth / OTP / refresh / MFA | anonymous → authenticated principal | Spoofing, Elevation, DoS |
| TB-3 | USSD webhook (server-to-server) | aggregator → unauthenticated session | Spoofing, Tampering, Repudiation |
| TB-4 | Admin / staff surface | citizen → staff (MOD/ADMIN/ROOT) | Elevation, Repudiation |
| TB-5 | NIDA / voter ID verification | app → external registry (PII egress) | Information disclosure |
| TB-6 | Media upload/serve | app → object store + scanner | Tampering (malware), Disclosure (EXIF/geo) |
| TB-7 | PII crypto / KMS | app → key material | Information disclosure |
| TB-8 | Payments (Phase 2) | app → mobile-money aggregator | Tampering (integrity fence), Repudiation |
| TB-9 | Moderation pipeline | UGC → auto-assist → human | Tampering, Disclosure (doxxing) |
| TB-10 | Outbox / event bus | producer → async handlers | Information disclosure (PII leak) |

---

## 3. STRIDE analysis per boundary

Legend — **Status**: ✅ mitigated (implemented) · ⚠️ partial · ❌ open (carried to §5 + gate).

### TB-1 — Public REST API

| STRIDE | Threat | Mitigation (implemented) | Status |
|---|---|---|---|
| **S** | Anonymous caller forges identity to read private data | No principal on public paths; everything else `anyRequest().authenticated()` (`SecurityConfig`). Public allow-list is **context-relative GET-only** (`PUBLIC_GET_PATTERNS`) | ✅ |
| **T** | Param tampering to read another user's resource | Public ids are non-enumerable UUIDs; private reads enforced server-side (404-not-403 to defeat enumeration, per `SecurityConfig` announcements note) | ✅ |
| **R** | Caller denies an action | `AuditEvent` append-only hash-chain on security-relevant decisions | ✅ |
| **I** | Private report / ProfileLocation leaks via a public list | Sensitive reports forced PRIVATE (§25.3); `ProfileLocation` never serialised publicly (§9.0); single-segment `/announcements/*` deliberately scoped | ✅ |
| **D** | Volumetric flood on public reads | Stateless reads, pagination (§17). **No app-layer global rate-limit / WAF yet** — relies on infra/edge | ⚠️ |
| **E** | Public GET pattern accidentally exposes a write/admin op | Allow-list is GET-only **and** `@PreAuthorize` still runs under `permitAll()` (defence in depth — `SecurityConfig` Javadoc) | ✅ |

### TB-2 — Authentication / OTP / refresh / MFA

| STRIDE | Threat | Mitigation (implemented) | Status |
|---|---|---|---|
| **S** | Credential stuffing / OTP brute force / account takeover | BCrypt (`SecurityConfig.passwordEncoder`); `AuthRateLimiter` — OTP send 1/60s, OTP verify ≤5/challenge, login lockout @10/15min + exponential backoff @3 (`InMemoryAuthRateLimiter`); one-account-per-phone (D15) | ⚠️ (limiter is **in-memory**, not multi-instance — Redis required) |
| **S** | Refresh token replayed as access token | `TokenType` claim re-validated on every `verify()`; refresh carries **no** roles/tier (`JwtService`) | ✅ |
| **S** | Forged JWT (weak/absent secret) | `JwtProperties` **fails fast** if secret < 256 bits; `iss`+`aud` validated on verify; HS256 over env secret | ✅ |
| **T** | Token claim tampering to elevate role/tier | Signature verified; roles/tier are **hints only** — live re-check server-side (`TierService`, `@RequiresTier`, MF-2) | ✅ |
| **R** | User disputes login / lockout | Auth outcomes audited (`AuditEvent`, hashed client IP) | ✅ |
| **I** | OTP / phone leak in logs | Rate-limiter keys are **hashed** identifiers; OTP value never logged; PII redaction invariant | ✅ |
| **D** | OTP-send flooding (SMS cost / enumeration) | Per-recipient send throttle; anti-enumeration on open endpoints | ⚠️ (in-memory) |
| **E** | Staff login without second factor | `MfaLoginGate.requiresSecondFactor` (login) **and** `isStaffMfaSatisfied` (endpoint) — defence in depth; default `mfa.enforced=true` | ✅ |

### TB-3 — USSD webhook (server-to-server)

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| **S** | Attacker spoofs the aggregator to inject sessions / bind to a victim MSISDN | `UssdGatewayController` is `permitAll()` at method layer. **No shared-secret / IP allow-list / HMAC on the request yet** — flagged in code (`CENTRAL INTEGRATION NEEDS`) and **not registered** in `SecurityConfig` public list | ❌ |
| **T** | Replayed/forged session payload manipulates report intake | Session state in Redis keyed by MSISDN+sessionId; idempotency on intake. Origin authenticity still unproven (see S) | ⚠️ |
| **R** | Feature-phone action repudiated | Session + resulting domain action audited | ✅ |
| **I** | Menu strings leak PII to wrong handset | Plain `CON/END` strings hold no national-ID/PII; account linked by MSISDN only | ✅ |
| **E** | USSD path creates over-privileged account | USSD linkage creates **T1** only (EI-4); high-trust actions still gated | ✅ |

### TB-4 — Admin / staff surface

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| **S** | Citizen session reaches a staff endpoint | `@EnableMethodSecurity` deny-by-default; staff endpoints gate `hasRole(...) and @mfa.isStaffMfaSatisfied()` | ✅ |
| **T** | Staff scope bypass (act outside assigned area/category) | `ScopeGuard`/`ScopeGuardImpl` attribute scoping (area/category/constituency); effective-window-aware role query (N-2) | ✅ |
| **R** | Admin denies a privileged action | Every admin/grant action audited with active roles (`actor_roles`), multi-hat recorded (D16) | ✅ |
| **I** | Admin reads PII beyond need | Least-privilege reads; `idNo` decrypted only on lawful need; verification queue scoped | ⚠️ (column-level read-access policy to be verified in pen-test) |
| **E** | Privileged bootstrap account ships to prod | `DevAdminSeeder` is `@Profile("dev")` — never instantiated in prod; idempotent; first prod admin via audited grant path. **Prod-safe first-admin provisioning runbook still owed** | ⚠️ |
| **E** | Self-action / conflict of interest | D13/D16 self-action guardrails (can't resolve own report, rate self, moderate own content) | ✅ |

### TB-5 — NIDA / voter-ID verification (PII egress)

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| **I** | ID egress to a third party without basis | MVP adapter is `OperatorAssistedVerificationAdapter` — **no external call**, no `idNo` logged; data-minimised field set (EI-1/EI-2, DI5) | ✅ |
| **S** | Forged "verified" result elevates to T3 | Operator-assisted path requires human `MODERATOR` approval; `markIdVerified` sets the flag, live `TierService` returns T3 (no client-trusted tier) | ✅ |
| **T** | Tamper with `isElectoral` to double-influence | Voter-ID authoritative; manual change cooldown anchored on **profile** (`electoralChangedAt`) — survives remove-then-re-add (V-1 fix); audited (D13/R18) | ✅ |
| **E** | ID dedup bypass → multiple accounts | Unique blind index `ux_profile_id_hash` over `(idType,idNo)` — hard DB guarantee without decryption (D15) | ✅ |
| **I** | Future NIDA adapter logs/over-shares ID | Port contract forbids leaking vendor/PII into domain; **review required when the real NIDA/registry adapter lands** | ❌ (future) |

### TB-6 — Media upload / serve

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| **T** | Malware upload served to other citizens | `MalwareScanner` port; quarantine→clean bucket flow; unscanned media held, not served (EI-8) | ✅ (stub scanner; real engine for prod) |
| **I** | EXIF/GPS in a photo deanonymises a sensitive reporter | EXIF/geo strip on intake; sensitive-category attachments stripped + location coarsened (§25.3) | ⚠️ (verify strip runs before any serve path) |
| **S** | Forged signed URL / direct object access | Pre-signed, time-bound URLs via `ObjectStore`; no public bucket listing | ✅ |
| **D** | Oversized upload exhausts storage | Size/content-type limits + clear guidance (§25.6) | ⚠️ |

### TB-7 — PII crypto / KMS

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| **I** | DB dump exposes national/voter IDs | `EncryptedStringConverter` (AES-GCM authenticated) on `id_no`; ciphertext-only column; blind index is keyed HMAC | ✅ (dev key adapter; **prod = KMS envelope EI-19**) |
| **T** | Tamper with ciphertext | AES-GCM tag detects modification (`DevKeyCryptoAdapter`) | ✅ |
| **I** | Key compromise / no rotation | `CryptoPort` abstraction lets KMS adapter swap in with **no entity change**. Single static dev key cannot rotate — **prod KMS + rotation + lease caching owed** | ❌ (prod) |

### TB-8 — Payments (Phase 2)

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| **T** | Tokens used to buy democratic weight | **Integrity fence (D18):** tokens meter actions, **never** gate/buy binding civic weight; `StubPaymentProvider` only (no money movement in MVP) | ✅ (design); re-verify at Phase-2 build |
| **R** | Double-charge / disputed mobile-money txn | Idempotency keys, no-double-charge, clear status (§25.6); `PaymentProvider` port + DLR | ✅ (design) |
| **I** | MSISDN over-shared with aggregator | MSISDN is PII; minimised field set; in-country residency review (D-Q9) | ⚠️ (Phase 2) |

### TB-9 — Moderation pipeline

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| **T** | Harmful content / doxxing at scale | Hybrid pipeline (D-Q8): `ContentSafety` auto-assist → human moderators (`ModerationQueueService`) → community `FlagService`; `SeverityPolicy` prioritises GBV/safety | ✅ (auto-assist Phase 2; degrades to human) |
| **I** | PII auto-detection gap → doxxing | Swahili-aware assist as **assist-only**, human-in-the-loop (R20/R21); PII-detection to prevent doxxing | ⚠️ |
| **R** | Disputed takedown | `AppealService` — different moderator handles appeal; outcomes audited (§25.8) | ✅ |
| **E** | Moderator moderates own content | Self-action guardrail (D16), `SubjectAuthorResolver` | ✅ |
| **T** | Censorship-by-flag (false flagging) | Flag reputation + threshold + human review; penalise abusive flaggers (§25.7) | ⚠️ |

### TB-10 — Outbox / event bus

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| **I** | PII leaks via replayable/queryable event payload | **Hard invariant:** `OutboxEvent.payload` = ids/codes/enums **only, never PII**; consumers re-read aggregate by id; `last_error` redacted (ADR-0014) | ✅ |
| **T** | Lost/duplicated side effect | Transactional outbox commits atomically with domain mutation; retries w/ backoff; DLQ + metric; idempotent handlers (DI3/DI4) | ✅ |
| **R** | Replay used to forge events | Replay re-reads current aggregate state; no authority carried in payload | ✅ |

---

## 4. Cross-cutting integrity controls (electoral neutrality, R5/R14/R15/R18)

- **One person = one account:** unique phone at signup + unique `id_hash` blind index at verification (D15). Defeats sockpuppets/SIM-farms at the identity layer.
- **Binding actions scoped to the single `isElectoral` location** (D13), voter-ID-authoritative, profile-anchored cooldown — prevents cross-constituency double-influence.
- **Tokens never buy democratic weight** (D18 integrity fence) — payments and civic weight are architecturally separate.
- **Election-period neutrality:** controls, audit and moderation are identity-blind to party/candidate; no candidate-promotion surface (R26). Any feature that could read as partisan is a security/T&S blocker.

---

## 5. Ranked residual-risk register

| Rank | ID | Residual risk | Trace | Likelihood | Impact | Owner | Mitigation / gate condition |
|---|---|---|---|---|---|---|---|
| 1 | **TR-1** | **USSD webhook unauthenticated** — no shared-secret/IP-allow-list/HMAC; aggregator origin unproven | TB-3, R29 | M | H | Eng/SRE | Add HMAC/shared-secret + IP allow-list; register path centrally in `SecurityConfig`. **Blocks USSD go-live (Phase 2), not MVP** |
| 2 | **TR-2** | **Prod KMS envelope encryption + key rotation** not wired — dev uses a single static key | TB-7, R10, EI-19 | M | **H** | Eng/SRE | KMS/Vault adapter behind `CryptoPort`; rotation + lease caching. **P1 launch blocker for any prod PII** |
| 3 | **TR-3** | **Rate-limiting is in-memory** — not shared across instances; lost on restart | TB-2, R11/R29 | M | H | Eng/SRE | Redis-backed `AuthRateLimiter`; multi-instance OTP/login limits. **P2 launch gate** |
| 4 | **TR-4** | **Pen-test not executed** on auth/reporting/admin surfaces | TB-1/2/4, R11 | M | H | Security | Independent pen-test, no open P1/P2 before ship |
| 5 | **TR-5** | **Prod-safe first-admin bootstrap** runbook owed (dev seeder is dev-only, correct) | TB-4, R11 | L | H | Eng/SRE | Documented audited first-admin provisioning; confirm no `dev` profile in prod |
| 6 | **TR-6** | **Secrets-manager wiring + rotation for new real adapters** (`HttpSmsGateway`, `SmtpEmailSender`, FCM) — keys read from env, not yet a managed secret store with rotation | TB-5/6, DI5, R27 | M | M | Eng/SRE | Bind aggregator API key / SMTP / FCM service-account via secret manager; rotation policy |
| 7 | **TR-7** | **Anonymous sensitive-report handling** end-to-end (no `reporterProfile`, pseudonymous token, EXIF/geo strip, location coarsening, GBV duty-of-care) needs verification | TB-1/6/9, §25.3, R20 | M | **H** | Eng/T&S | Confirm no identity linkage stored; verify strip+coarsen on every shared/aggregate path |
| 8 | **TR-8** | **App-layer abuse/anti-automation gaps** — global API rate-limit, flag-reputation, signing-velocity anomaly detection partial | TB-1/9, §25.7, R14/R15 | M | M | Eng/T&S | Edge/WAF + velocity heuristics before high-traffic waves |
| 9 | **TR-9** | **Media EXIF/geo strip + scanner** are stubs; verify strip precedes any serve and prod scanner engine wired | TB-6, EI-8 | M | M | Eng | Real `MalwareScanner` engine; assert strip-before-serve |
| 10 | **TR-10** | **Future NIDA/real-registry adapter** PII-egress review (data minimisation, no ID logging, residency) | TB-5, EI-1/2, R10/R12 | L (Phase 2) | H | Security/Legal | Mandatory security+Legal review when the real adapter lands |

---

## 6. Launch security gate — checklist & status

A release does **not** ship until every P1/P2 item is GREEN. The gate is the Security & Privacy Engineer's call (CLAUDE.md §11).

| # | Gate item | Severity | Status | Evidence / condition |
|---|---|---|---|---|
| G1 | `@EnableMethodSecurity` deny-by-default; admin/staff endpoints method-gated | P1 | ✅ DONE | `SecurityConfig`, `@PreAuthorize`, `MfaLoginGate` |
| G2 | Rotating refresh tokens; refresh ≠ access (`TokenType` re-validated) | P1 | ✅ DONE | `JwtService`, `RefreshToken` |
| G3 | JWT secret fail-fast (≥256-bit); `iss`+`aud` validated | P1 | ✅ DONE | `JwtProperties`, `JwtService.verify` |
| G4 | National/voter ID **field-level encrypted at rest**; blind-index dedup | P1 | ✅ DONE (dev key) / ❌ **prod KMS** | `Profile`, `EncryptedStringConverter`, `DevKeyCryptoAdapter` → **TR-2** |
| G5 | PII redacted from logs / outbox / audit / analytics | P1 | ✅ DONE | `OutboxEvent` invariant, `AuditEvent` (hashed IP), adapters never log `idNo` |
| G6 | Immutable, tamper-evident audit trail | P1 | ✅ DONE | `AuditEvent` append-only hash-chain (INSERT+SELECT only) |
| G7 | No secrets in source; all via env/secret manager | P1 | ✅ DONE (env) / ⚠️ **managed store + rotation** | `application.yml` `${VAR}`, `JwtProperties`, `CommunicationsChannelProperties` → **TR-6** |
| G8 | CORS allow-listed (no wildcard-with-credentials) | P1 | ✅ DONE | `SecurityConfig.corsConfigurationSource` |
| G9 | Staff MFA enforced (TOTP), `mfa.enforced=true` default | P1 | ✅ DONE | `MfaLoginGate`, `TotpService` |
| G10 | One-account-per-person (phone + ID dedup) | P1 | ✅ DONE | `user.phone` unique, `ux_profile_id_hash` |
| G11 | Electoral integrity (single `isElectoral`, cooldown, voter-ID authoritative) | P1 | ✅ DONE | `Profile.electoralChangedAt`, `ElectoralScopeService` (D13) |
| G12 | Tokens never buy democratic weight (integrity fence) | P1 | ✅ DONE (design) | D18; `StubPaymentProvider` only in MVP |
| G13 | **Prod KMS envelope encryption + key rotation** | P1 | ❌ OPEN | **TR-2** — blocks prod PII |
| G14 | **Redis-backed rate-limiting** (multi-instance OTP/login) | P2 | ❌ OPEN | **TR-3** |
| G15 | **Pen-test on auth/reporting/admin — no open P1/P2** | P1 | ❌ OPEN | **TR-4** |
| G16 | **Prod-safe admin bootstrap** (dev seeder confirmed dev-only + runbook) | P2 | ⚠️ PARTIAL | `DevAdminSeeder @Profile("dev")` ✅; runbook owed → **TR-5** |
| G17 | **Anonymous sensitive-report handling** verified end-to-end | P1 | ⚠️ PARTIAL | §25.3; verify no linkage + EXIF/geo/coarsen → **TR-7** |
| G18 | **USSD webhook authentication** (HMAC/IP allow-list) | P2 (Phase-2 gate) | ❌ OPEN | **TR-1** — blocks USSD go-live |
| G19 | **Real malware scanner + EXIF/geo strip-before-serve** | P2 | ⚠️ PARTIAL | EI-8 stubs → **TR-9** |
| G20 | **SAST / SCA / container scan** clean in CI | P2 | ⚠️ VERIFY | CI pipeline (§5 infra) — confirm gating + no high CVEs |
| G21 | **Legal/privacy sign-off** (PDPA, hosting/residency, consent, erasure) | P1 | ❌ OPEN | See [DPIA.md](DPIA.md) §6 — route to Legal |

**Go / No-Go:** **NO-GO for production at this snapshot.** Open P1 blockers: **G13 (prod KMS), G15 (pen-test), G17 (anonymous sensitive reports), G21 (Legal/PDPA sign-off)**. The implemented control baseline (G1–G12) is strong and closes every documented prior-repo defect (logged root password, ineffective `@PreAuthorize`, refresh-as-access, public actuator, wildcard CORS). The remaining gaps are productionisation (KMS, Redis, scanning), one Phase-2 boundary (USSD auth), and external assurance (pen-test, Legal). USSD (G18) gates the Phase-2 channel, not MVP.

---

*Companion: [DPIA.md](DPIA.md) — PII data inventory and PDPA 2022/2023 lawful-basis / consent / retention / erasure / cross-border analysis.*
