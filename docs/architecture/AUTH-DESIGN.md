# Taarifu Backend — Authentication & Authorization Design

> **Status:** Accepted (auth increment). **Owner:** Solution Architect (David Okello).
> **Scope:** the auth & authz increment of the modular monolith (`/backend`). **DESIGN ONLY** — this is the contract the backend + security engineers build to. No application code is delivered with this document.
> **Builds on:** the `com.taarifu.common` shared kernel and the `com.taarifu.identity` entities/repos already on `develop` (`User`, `Profile`, `ProfileLocation`, `Role`, `RoleAssignment`, `RefreshToken`, `VerificationRequest`; `JwtService`, `JwtAuthenticationFilter`, `SecurityConfig`, `RequiresTier`, `CurrentUser`, `TokenType`, `CryptoPort`).
> **Grounding:** PRD §6.4 (one-account-additive-roles, D11–D16), §7 (RBAC + trust tiers T0–T3), §18 (security), §25.1 (lifecycle/erasure), §25.5 (tier downgrade); ARCHITECTURE.md §6 (security model); ADR-0007 (JWT decision) → superseded/extended by **ADR-0011**; CLAUDE.md §3/§8/§12.
> **Closes the review backlog:** `docs/reviews/security-foundation-review.md` items **MF-1, MF-2, MF-3, S-1…S-5, L-1**.

---

## 0. One-paragraph summary

This increment turns the auth **scaffold** into auth **behaviour**. A citizen signs up with **phone + OTP** and lands at **T1**; logs in by **password** or **OTP**; receives a **short access JWT + a rotating, single-use refresh JWT** whose family is revoked on reuse (theft signal); completes their profile to reach **T2**; and is verified (operator-assisted) to reach **T3**. Every protected call resolves the caller's **live trust tier from the database** (never the token claim — **MF-2**) through a `TierService` behind a `@RequiresTier` method interceptor, and any scoped action passes through a `ScopeGuard` that reads the live `RoleAssignment` scope (area/category/constituency — **MF-3**). Anti-automation is enforced with **Redis-backed OTP send/attempt limits**, **login lockout + exponential backoff**, and a **staff TOTP gate** keyed on `User.mfaEnabled` (**S-2**). JWTs are **hardened**: fail-fast on a weak/absent secret, `iss`+`aud` validation, and a planned **RS256/ES256** swap (**MF-1**). Every security-relevant decision is written to an **append-only `audit_event` store holding references/hashes, never raw PII, with tombstone-on-erasure** (**L-1**). OTP delivery goes through the `SmsGateway` **port** with a logging **dev stub**; the real aggregator lands later (**EI-3**). The new persisted state is three things: `otp_challenge`, `audit_event`, and Redis counters for rate-limit/lockout.

---

## 1. Scope, non-goals & the decisions this increment honours

### 1.1 In scope
- Signup (phone + OTP) → **T1**; profile completion → **T2**; ID verification request flow that, on approval, drives **T3** (the moderator-side approval action is owned by the `moderation` increment; this increment owns the **citizen-facing request** + the **tier resolution** that consumes its outcome).
- Login by **password** and by **OTP**; logout (single session + all-sessions).
- **JWT access + rotating refresh** with **reuse-detection → family revocation**.
- The **live server-side `TierService`** + `@RequiresTier` interceptor (**MF-2**).
- The **`ScopeGuard` authorization seam** (area/category/constituency from `RoleAssignment` — **MF-3**); the *seam and bean* land now even though most scoped **endpoints** arrive with `reporting`/`responders`.
- Anti-automation: OTP rate limits, login lockout/backoff, staff TOTP gate (**S-2**).
- JWT hardening: secret-strength fail-fast, `iss`/`aud` validation, RS256/ES256 migration plan (**MF-1**).
- The **append-only `audit_event`** store + the exact auth/security events it records (**L-1**).
- The **`SmsGateway` port + dev stub** for OTP delivery (**EI-3**).

### 1.2 Non-goals (explicitly deferred, seam-only here)
- **NIDA / voter-ID API integration** — operator-assisted at launch (D-Q2). The `IdentityVerificationProvider` port already exists; this increment uses the **operator-assisted/queue** path only.
- **Full scoped-endpoint enforcement** — the `ScopeGuard` bean + contract ship now; per-resource `@PreAuthorize("@scope...")` annotations land with the modules that own those resources (`reporting`, `responders`, `institutions`).
- **Electoral binding-action enforcement** — `@RequiresTier("T3")` + the integrity fence are designed here; the **binding endpoints themselves** (sign petition, rate MP, binding poll) are P2 (`engagement`/`accountability`). The fence rule is stated so those endpoints inherit it.
- **Staff SSO/OIDC** — ADR-0007 revisit trigger; native path is the baseline.
- **Real `SmsGateway` aggregator adapter** — dev stub now; aggregator adapter is the `communications` integration increment (EI-3).

### 1.3 Locked decisions this increment must not violate
- **D11/D15 — one account per person**: `User.phone` UNIQUE at signup; ID dedup via `profile.id_hash` blind index at verification. Signup **never** mints a second account for an existing phone — it offers login/recovery.
- **D12/§6.4 — additive roles on one account**: login/tier logic reads **all** of a user's `RoleAssignment`s; no mutually-exclusive `userType`.
- **D13/D16 — conflict-of-interest + electoral integrity**: the binding-action authorization path checks **tier + electoral scope + one-per-person only** and **must never read a token balance** (D18/§23.5). Designed into the fence; enforced where binding endpoints land.
- **D-Q9 / PDPA / §18 — privacy by default**: no PII (phone, `idNo`, OTP value, raw tokens) in logs or `audit_event`; PII only as references/hashes.

---

## 2. The 12-factor of this increment: components added to the shared kernel + identity

> All new components carry **mandatory Javadoc** (CLAUDE.md §8): responsibility, params/returns, thrown errors, and the security/PDPA "why".

| Component | Package | Responsibility |
|---|---|---|
| `AuthController` | `identity.api.controller` | Thin REST surface: signup, OTP request/verify, login (password/OTP), refresh, logout. Validates + delegates; wraps in `ApiResponse`. |
| `ProfileController` | `identity.api.controller` | `me`, profile completion (→ T2), location pin, verification request submit. |
| `SignupService` | `identity.application.service` | Phone+OTP signup → `User(PENDING→ACTIVE)` + `Profile` + T1. Idempotent on phone. |
| `OtpService` | `identity.application.service` | Issues/verifies OTP challenges via `OtpChallenge` + `SmsGateway`; enforces send/attempt limits. |
| `LoginService` | `identity.application.service` | Password + OTP login; lockout/backoff; staff TOTP gate; issues token pair. |
| `TokenService` | `identity.application.service` | Issues the access+refresh pair, persists hashed refresh, **rotation + reuse-detection → family revocation**. Wraps `JwtService`. |
| `TierService` | `identity.application.service` (public API) | **Live** T0–T3 resolution from the DB for a `publicId`. The single source of truth for `@RequiresTier` (**MF-2**). |
| `TotpService` | `identity.application.service` | TOTP secret provisioning + verification for staff MFA (`User.mfaEnabled`). |
| `RequiresTierAspect` | `common.security` | The method-security interceptor enforcing `@RequiresTier` against `TierService` (**MF-2**). |
| `ScopeGuard` (bean `@taarifuAuthz`) | `common.security` | `@PreAuthorize`-callable scope checks reading live `RoleAssignment` (**MF-3**). |
| `AuthRateLimiter` | `common.security` | Redis token-bucket / counter primitive for OTP + login anti-automation (**S-2**). |
| `AuditEventService` | `common.audit` (public API) | Append-only writer for `audit_event`; references/hashes only, no raw PII (**L-1**). |
| `SmsGateway` (port) + `LoggingSmsGatewayStub` (adapter) | `communications.domain.port` / `...infrastructure.adapter` | OTP/alert send seam; dev stub logs a redacted record, never the OTP value (**EI-3**). |
| `JwtKeyProvider` / hardened `JwtProperties` | `common.security` | Secret-strength fail-fast, `iss`/`aud`, signer abstraction for the RS256/ES256 swap (**MF-1**). |

> **Module placement rationale (ARCHITECTURE §3):** `TierService` and `ScopeGuard` need identity data but are consumed by *every* module's authz. The **port/contract** (`TierResolver` interface, `ScopeGuard` bean) lives in `common.security`; the **implementation** that queries `RoleAssignment`/`Profile` lives in `identity` and is wired as the `common` interface bean. This keeps `common` dependency-free (it owns the interface) while the live query stays in `identity` (no cross-module entity leak). `AuditEventService` lives in `common.audit` because every module audits.

---

## 3. Flow 1 — Signup (phone + OTP) → T1

**Goal:** a citizen with only a phone reaches **T1** (PRD §7.3, US-0.1). One account per phone (D11/D15).

```
Client                         AuthController / SignupService / OtpService            SmsGateway        DB / Redis
  │ POST /auth/otp/request {phone, purpose=SIGNUP}                                       │                  │
  │──────────────────────────────►│ AuthRateLimiter.checkOtpSend(phone)  ───────────────┼──────────────►  Redis: otp:send:{phone}
  │                               │ if phone already a User → still 202 (no enumeration) │                  │
  │                               │ OtpService.issue(phone, SIGNUP):                      │                  │
  │                               │   create OtpChallenge(code_hash, ttl=5m, max=5)  ────┼──────────────►  otp_challenge
  │                               │   SmsGateway.send(phone, localized OTP text)  ───────►│ (dev stub logs  │
  │ ◄─────────── 202 {challengeId, expiresAt}                                            │  redacted)       │
  │                               │ audit AUTH_OTP_REQUESTED (ref=phone_hash)  ──────────┼──────────────►  audit_event
  │                                                                                       │                  │
  │ POST /auth/otp/verify {challengeId, code}                                            │                  │
  │──────────────────────────────►│ AuthRateLimiter.checkOtpAttempt(challengeId) ────────┼──────────────►  Redis
  │                               │ OtpService.verify: hash(code)==code_hash, !expired,   │                  │
  │                               │   attempts<max, single-use → consume                  │                  │
  │                               │ SignupService.completeSignup(phone):                  │                  │
  │                               │   upsert User(status=ACTIVE, trust_tier=T1)  ─────────┼──────────────►  app_user
  │                               │   create Profile(phone_verified=true)  ───────────────┼──────────────►  profile
  │                               │   grant CITIZEN RoleAssignment(ACTIVE)  ──────────────┼──────────────►  role_assignment
  │                               │   TokenService.issuePair(user) (rotating refresh)     │                  │
  │                               │ audit AUTH_SIGNUP_COMPLETED, AUTH_TIER_CHANGED(T0→T1)  │                  │
  │ ◄────────── 201 {accessToken, refreshToken, user{publicId, tier:T1}}                  │                  │
```

**Rules**
- **No user enumeration:** `POST /auth/otp/request` returns `202` whether or not the phone exists (S-2; PRD §18). If the phone already has an account, the OTP is for **login/recovery**, never a second signup (D11).
- **OTP secret hygiene:** the challenge stores only a **hash** of the code (`HMAC-SHA-256` via `CryptoPort` blind-index sub-key, same primitive as `id_hash`), never the plaintext; the OTP value is never logged (S-4).
- **Tier set:** `trust_tier=T1` and `profile.phone_verified=true` are written in **one transaction**; the tier is **not** trusted from any client input — it is computed by `TierService` rules (§7).
- **Idempotency:** `Idempotency-Key` on `/auth/otp/verify` and `/auth/signup` (ARCHITECTURE §5.4) so a retried submit on a flaky 2G/3G link does not double-create or double-issue (PRD §15, §25.6).

---

## 4. Flow 2 — Login (password and OTP)

Two credential modes onto the same token issuance (PRD §18; ADR-0007).

### 4.1 Password login

```
POST /auth/login/password { phone | email, password }
  → AuthRateLimiter.checkLogin(accountKey, clientIp)   // lockout/backoff (S-2)
  → resolve User by phone (or email); CONSTANT-TIME path whether or not found (no enumeration)
  → BCryptPasswordEncoder.matches(password, password_hash)
  → if User.mfaEnabled (staff): require TOTP step → 200 {mfaRequired:true, mfaToken}  (see §9.3)
  → on success: AuthRateLimiter.resetLogin(...); TokenService.issuePair(user)
  → audit AUTH_LOGIN_SUCCEEDED  (failure → AUTH_LOGIN_FAILED, ref=account_hash, reasonCode)
  → 200 { accessToken, refreshToken, user{...} }
```

### 4.2 OTP login (passwordless / recovery)
- `POST /auth/otp/request {phone, purpose=LOGIN}` then `POST /auth/login/otp {challengeId, code}`.
- Same `OtpService` path as signup, but the verified phone **must** map to an existing `ACTIVE` user; otherwise `202`-style non-committal response (no enumeration). On success → token pair.
- OTP login is the **degradation path** for forgotten passwords and for accounts that never set a password (`password_hash IS NULL`, OTP-only accounts).

**Failure semantics (anti-enumeration + anti-timing, S-2/§18):** wrong password, unknown account, and disabled account all return the **same** `401 UNAUTHENTICATED` envelope with a generic message; the precise reason is only in `audit_event` (reason-coded), never to the client. Account status is checked: `SUSPENDED`/`DISABLED` → generic `401` to the client, reason-coded in audit.

---

## 5. Flow 3 — JWT access + ROTATING refresh with reuse-detection

**Decision (ADR-0011):** stateless **access JWT (~15 min)** + **rotating, single-use refresh JWT (~30 days)**; refresh tokens persisted **hashed** in `refresh_token` with `family_id/used/revoked` (already modelled). Reuse of a consumed token **revokes the whole family** (S-3).

### 5.1 Rotation + reuse-detection algorithm (the invariant the engineers build + test)

```
POST /auth/refresh { refreshToken }          // body or Authorization: Bearer (REFRESH type)
  1. JwtService.verify(token, REFRESH)        // signature, expiry, tokenType=REFRESH, iss, aud
  2. h = sha256(token); row = refresh_token.findByTokenHash(h)
  3. if row == null            → 401 (unknown/forged)              audit AUTH_REFRESH_REJECTED
  4. if row.revoked            → 401                                audit AUTH_REFRESH_REJECTED
  5. if row.used == true       → REUSE DETECTED:
         revokeFamily(row.family_id)  // revoke=true on ALL live rows in the family
         403 + force re-login          audit AUTH_REFRESH_REUSE_DETECTED, AUTH_FAMILY_REVOKED
  6. if expiresAt < now        → 401                                audit AUTH_REFRESH_EXPIRED
  7. SUCCESS (single-use rotation, one DB tx):
         row.used = true
         new = persist RefreshToken(hash=sha256(newToken), family_id=row.family_id, used=false)
         issue new access token
         audit AUTH_TOKEN_REFRESHED
  8. return { accessToken, refreshToken: newToken }
```

**Invariants to pin in tests (S-3):**
1. Presenting a `used=true` token **revokes the entire `family_id`** and fails closed.
2. Each successful rotation issues **exactly one** new live token per family; the old one is `used`.
3. `revoked` / expired tokens fail closed (no silent acceptance).
4. Only the **hash** is ever stored (DB unique on `token_hash`); the raw token exists only on the client.
5. Concurrency: rotation runs under an optimistic lock / `SELECT … FOR UPDATE` on the row so two simultaneous refreshes (offline replay) cannot both succeed — the second sees `used=true` and triggers reuse handling.

**Logout:**
- `POST /auth/logout` → revoke the presented refresh token's row (`revoked=true`). Access token expires naturally (short TTL — the accepted trade-off, ADR-0007/0011).
- `POST /auth/logout/all` → revoke **all** the user's live refresh families (`revoked=true` for all rows where `user_id=me`). Used on password change, suspected compromise, device loss. `audit AUTH_LOGOUT_ALL`.

### 5.2 Token claims (access)
`sub` = `User.publicId` (immutable — never phone/handle, ADR-0006); `iss` = configured issuer; `aud` = configured audience (web-admin vs citizen-app may diverge — MF-1); `iat`/`exp`; `tokenType=ACCESS`; `roles` (hint); `trustTier` (**hint only** — re-resolved live, MF-2). Refresh token carries **no** roles/tier (already enforced).

---

## 6. Flow 4 — Profile completion → T2

**Goal:** T1 → **T2** (PRD §7.3): complete profile (name, geography, contacts) with email/phone verified; unlocks official reports, comments, Q&A, survey responses.

```
PATCH /api/v1/profiles/me { firstName, lastName, email?, dateOfBirth?, gender?, nationality? }
POST  /api/v1/profiles/me/locations { wardPublicId, associationType, isPrimary }   // D11 primary pin
POST  /auth/otp/request {channel=EMAIL, purpose=VERIFY}  → verify → profile.email_verified=true
  → on each change, TierService.recompute(user):  promotes to T2 when the T2 predicate holds (§7.2)
  → audit AUTH_TIER_CHANGED(T1→T2), PROFILE_UPDATED (field names only, NEVER values)
```

- **Location pin** derives the administrative chain + constituency from the ward (geography public API; PRD §9.0). `ProfileLocation` is **private PII** — never in a public DTO or log.
- **T2 predicate** is evaluated server-side by `TierService`; the client cannot self-assert T2.

---

## 7. Flow 5 — LIVE server-side trust-tier resolver + `@RequiresTier` enforcement (MF-2)

> **The single most important authorization rule in this increment.** The `trustTier` JWT claim is a **convenience hint for the UI** and is **never** an authorization input. Every tier-gated action resolves the **live** tier from the database **on that request**.

### 7.1 `TierService` (the live resolver)

- Interface `TierResolver` in `common.security`; implementation `TierServiceImpl` in `identity.application.service`.
- `TrustTier resolveLiveTier(UUID userPublicId)` computes the tier from **current DB state**, applying the PRD §7.3 predicates in order (highest first), and is the **only** authority for gating:

| Tier | Live predicate (all must hold), evaluated highest-first |
|---|---|
| **T3** | T2 predicate ∧ `profile.id_verified = true` (a NIDA/voter-ID `VerificationRequest` of type `ID` is `APPROVED`). |
| **T2** | T1 predicate ∧ profile complete (`first_name`,`last_name` present, ≥1 `ProfileLocation`) ∧ (`email_verified` ∨ `phone_verified`) per §7.3. |
| **T1** | `phone_verified = true` (or `email_verified`) and account `ACTIVE`. |
| **T0** | otherwise (guest / `PENDING` / `SUSPENDED`/`DISABLED` → treated as T0 for action gating). |

- **Downgrade-aware (§25.5):** if `id_verified` is later revoked, `resolveLiveTier` immediately returns T2 again — **new T3 actions are blocked**, prior actions stand (default non-fraud path). No token reissue is required because the token tier is never trusted.
- **Caching:** the resolver MAY cache per-`publicId` in Redis with a **short TTL (≤30s)** *and* explicit invalidation on any tier-affecting write (verification decision, profile change, suspension). The cache is a latency optimization, never an authority bypass — a cache miss recomputes from the DB. Document the TTL as the staleness bound (well under the access-token TTL).

### 7.2 `@RequiresTier` enforcement (the interceptor — MF-2)

- A `RequiresTierAspect` (`@Around` advice / `AuthorizationManager`) on methods annotated `@RequiresTier("T3")`:
  1. reads the caller's `publicId` from `CurrentUser`;
  2. calls `TierService.resolveLiveTier(publicId)`;
  3. compares against the annotation's minimum;
  4. on failure throws `ApiException(ErrorCode.TIER_TOO_LOW)` → `403` envelope with machine code `TIER_TOO_LOW` and a localized "verify your identity to continue" message + the required tier in `data`.
- **Landing rule (MF-2, non-negotiable):** the resolver + interceptor ship **in the same PR** as the first tier-gated endpoint. A **regression test** asserts that a **forged/elevated `trustTier` claim is ignored** — an attacker minting a token with `trustTier:T3` (or replaying after downgrade) is still blocked because the live DB tier governs.
- **Integrity-fence contract (D18/§23.5):** any future binding-action method is annotated `@RequiresTier("T3")` **and** checks **electoral scope + one-per-person** (owned by the binding modules) and **must never read a token/wallet balance** in its authorization path. This document fixes the rule so `engagement`/`accountability` inherit it; a unit test there will assert no wallet read on the authz path.

---

## 8. Flow 6 — Scope-aware authorization seam (area/category/constituency — MF-3)

**Problem (MF-3):** `hasRole('RESPONDER_AGENT')` alone lets any agent act on **any** area/category. RBAC must be **role × scope**. The scope lives on `RoleAssignment` (`areaIds[]`, `categoryIds[]`, `constituency`).

### 8.1 `ScopeGuard` (bean `@taarifuAuthz`)
- Registered as a Spring bean named `taarifuAuthz` so method security can call it:
  `@PreAuthorize("@taarifuAuthz.canActOnArea(#areaPublicId) and @taarifuAuthz.canActOnCategory(#categoryPublicId)")`
- Methods (all read **live** `RoleAssignment` for the `CurrentUser`, **not** the token):
  - `boolean canActOnArea(UUID areaPublicId)` — true if the user holds an `ACTIVE` assignment whose `areaIds` is **empty (unrestricted)** or **contains** `areaPublicId`, *or* contains an **ancestor** of it (resolved via the geography public API closure-table — a District grant covers its Wards). Empty set = unrestricted within that role.
  - `boolean canActOnCategory(UUID categoryPublicId)` — analogous over `categoryIds` (resolved via `reporting` public API when it exists).
  - `boolean canActInConstituency(UUID constituencyPublicId)` — true if an `ACTIVE` assignment's `constituency` matches (e.g. a `REPRESENTATIVE` limited to their own constituency).
  - `boolean isNotSelf(UUID subjectPublicId)` — the **conflict-of-interest** check (D13/D16): blocks rating/resolving/answering/moderating **self or own work**; pairs with `AuditEventService` (all multi-hat actions audited).
- **Deny-by-default:** an unknown/empty grant → `false`. Absence of any matching `ACTIVE` assignment → `false`.

### 8.2 What lands now vs later
- **Now:** the `ScopeGuard` bean, its contract, unit tests over `RoleAssignment` fixtures, and `isNotSelf` (used the moment any "act on someone" endpoint exists).
- **Later (with the owning module):** the actual `@PreAuthorize("@taarifuAuthz...")` annotations on `reporting`/`responders`/`institutions`/`engagement` endpoints. The seam guarantees those endpoints have nothing to invent — they wire the guard.

---

## 9. Flow 7 — Anti-automation (S-2)

All counters live in **Redis** (ephemeral, §25.1 "minutes–days"); none in Postgres. Keys are **hashed** (no raw phone/MSISDN in a key that could surface in logs/metrics).

### 9.1 OTP send + attempt limits
| Control | Default (config-tunable) | Key |
|---|---|---|
| OTP **send** rate per phone | 1 per 60s, 5 per hour, 10 per day | `otp:send:{phone_hash}` (sliding window) |
| OTP **send** rate per IP | 20 per hour | `otp:send:ip:{ip}` |
| OTP **verify** attempts per challenge | 5, then challenge burned | `otp_challenge.attempts` (DB) + `otp:try:{challengeId}` (Redis) |
| OTP **TTL** | 5 minutes, single-use | `otp_challenge.expires_at` |
- Breach → `429 RATE_LIMITED` envelope with `Retry-After`. OTP value **never** logged (S-4). Burned/expired challenges auto-expire (no cleanup job needed for Redis; a periodic sweep soft-deletes stale `otp_challenge` rows).

### 9.2 Login lockout + exponential backoff
| Control | Default | Key |
|---|---|---|
| Failed-attempt counter per account | window 15 min | `login:fail:{account_hash}` |
| Backoff | exponential w/ jitter after 3 fails (e.g. 1s,2s,4s,8s…) | enforced server-side before the password check |
| Lockout | after 10 fails in window → 15 min lock | `login:lock:{account_hash}` |
| Per-IP guard | 50 fails/hour/IP → IP soft-block | `login:fail:ip:{ip}` |
- Lockout response is the **same generic `401`/`429`** (no enumeration). `audit AUTH_LOGIN_LOCKED`. Successful login **resets** the counter.

### 9.3 Staff TOTP gate (`User.mfaEnabled`)
- For accounts holding `MODERATOR`/`ADMIN`/`ROOT` (and any account with `mfa_enabled=true`), password success is **not** sufficient:
  - Step 1: `POST /auth/login/password` → `200 {mfaRequired:true, mfaToken}` (a short-lived, single-purpose MFA challenge token — **not** an access token; `tokenType=MFA_CHALLENGE`, ~5 min, carries no roles/tier).
  - Step 2: `POST /auth/login/totp {mfaToken, totp}` → `TotpService.verify` → on success issue the real token pair.
- **Enrollment:** `POST /auth/mfa/totp/setup` (authenticated) returns a provisioning secret/URI (otpauth://) once; `POST /auth/mfa/totp/activate {totp}` sets `mfa_enabled=true`. The TOTP secret is stored **encrypted** via `CryptoPort` (envelope; same primitive as `Profile.idNo`), never plaintext.
- **Policy:** staff roles **cannot act** until `mfa_enabled=true` — a `MODERATOR`/`ADMIN`/`ROOT` assignment without active MFA is blocked at login by `LoginService` (and re-checked: a staff role granted to an existing session forces MFA enrollment on next sensitive action). `audit AUTH_MFA_ENROLLED`, `AUTH_MFA_CHALLENGE_FAILED`.

---

## 10. Flow 8 — JWT hardening + RS256/ES256 migration (MF-1)

### 10.1 Fail-fast + claim validation (lands in this increment)
- **Secret-strength guard:** on startup, validate `taarifu.security.jwt.secret` is present and **≥ 32 bytes (256-bit)** of decoded entropy; **reject the empty default and boot fails** with a clear, secret-free error. A `JwtProperties` `@PostConstruct`/validator (or a fail-fast `ApplicationRunner`) enforces it. This closes the silent T0→ROOT forge path (MF-1).
- **`iss` validation:** `JwtService.verify` must check the `iss` claim equals the configured issuer (currently issued, not checked — MF-1). Reject on mismatch.
- **`aud` validation:** add an `aud` claim on issuance and validate it on verify; configure distinct audiences if web-admin and citizen-app diverge. A token minted for one audience must not authorize the other.
- **`nbf`/clock skew:** allow a small (~30s) leeway; reject not-yet-valid tokens.

### 10.2 RS256/ES256 migration plan (MF-1)
The swap is **localized to `JwtService` + key provisioning** — callers (filter, controllers) are unaffected (already true by design).

```
Phase A (this increment): introduce JwtKeyProvider behind JwtService.
   - SymmetricKeyProvider (HS256) keeps working for dev/test.
   - Header carries `kid`; verify() selects key by kid.
Phase B (before any STAFF/ADMIN token is issued in a SHARED environment — the MF-1 gate):
   - Switch prod/staging to AsymmetricKeyProvider (RS256 or ES256).
   - Private signing key from KMS/secret-manager (CryptoPort-adjacent); public JWK(s) published at
     a read-only `/api/v1/.well-known/jwks.json` so an extracted service / resource server verifies
     without the signing secret (ARCHITECTURE §10 resource-server pattern).
   - Dual-key window: verify accepts old+new `kid` during rotation; issue only with new `kid`.
Phase C: rotate signing keys on a schedule; old `kid` retired after max access-token TTL.
```
- **Why before shared-env staff tokens:** with HS256 the same secret signs **and** verifies — any service that can verify can forge a ROOT token. Asymmetric signing means only the signer holds the private key (MF-1, PRD §18, ADR-0007).

---

## 11. Flow 9 — Append-only `audit_event` store (L-1)

**Decision (ADR-0011):** a dedicated **append-only** `audit_event` table, **separate** from the per-row `BaseEntity` audit columns (which answer "who last touched this row", not "what security/verification/multi-hat events happened"). It holds **references/hashes, never raw PII**; **erasure adds a new tombstone event, never mutates history** (PRD §18, §25.1).

### 11.1 Shape (`audit_event`)
| Column | Type | Notes |
|---|---|---|
| `id` | bigint identity | internal PK |
| `public_id` | uuid unique | public id |
| `occurred_at` | timestamptz not null | event time (server) |
| `event_type` | varchar(64) not null | from the catalogue §11.2 (CHECK or reference) |
| `actor_public_id` | uuid | who acted (the authenticated subject); `SYSTEM_ACTOR` sentinel for system events; **null** for anonymous |
| `subject_public_id` | uuid | the entity/account acted upon (e.g. account being verified) |
| `actor_roles` | varchar(255) | role names active at action time (multi-hat audit, D16) |
| `outcome` | varchar(16) not null | `SUCCESS` / `FAILURE` / `DENIED` |
| `reason_code` | varchar(64) | machine reason (e.g. `INVALID_CREDENTIALS`, `LOCKED`, `REUSE_DETECTED`) |
| `client_ip_hash` | varchar(64) | **hashed** IP, never raw (PDPA) |
| `correlation_id` | uuid | request/trace id (joins to logs/OTel) |
| `detail_ref` | varchar(512) | object-store key / reference to non-PII detail, **never inline PII** |
| `prev_hash` / `entry_hash` | varchar(64) | optional hash-chain for tamper-evidence (entry_hash = H(prev_hash ∥ canonical(row))) |

- **Append-only enforcement:** no `UPDATE`/`DELETE` from application code; a DB role/grant restricts the table to `INSERT`+`SELECT` (the relay/service has no update grant). `BaseEntity`'s soft-delete is **not** used here — audit rows are never soft-deleted.
- **Write path:** `AuditEventService.record(event)` is called **inside** the same transaction as the audited state change where atomicity matters (verification decision, role grant), and **out-of-band** (best-effort, post-response) for high-frequency low-stakes events (login attempts) to protect p95. For cross-module/async cases, audit can ride the **transactional outbox** to an `audit_event` sink (ARCHITECTURE §8) — same append-only target.
- **Tombstone-on-erasure (§25.1):** on a verified erasure, PII is severed elsewhere; here a **new** `IDENTITY_ERASED` event is appended (subject tombstoned to `anonymized_user_#`), history untouched.

### 11.2 Exactly which auth/security events are recorded
| `event_type` | When | Notes |
|---|---|---|
| `AUTH_OTP_REQUESTED` | OTP send issued | ref = phone_hash; **no** code, **no** raw phone |
| `AUTH_OTP_VERIFIED` / `AUTH_OTP_FAILED` | OTP verify outcome | reason_code on fail; never the code |
| `AUTH_SIGNUP_COMPLETED` | signup → T1 | subject = new user |
| `AUTH_LOGIN_SUCCEEDED` / `AUTH_LOGIN_FAILED` | login outcome | failures reason-coded (`INVALID_CREDENTIALS`/`UNKNOWN`/`DISABLED`) — uniform to client, precise here |
| `AUTH_LOGIN_LOCKED` | lockout tripped | anti-automation signal (S-2) |
| `AUTH_TOKEN_REFRESHED` | successful rotation | family_id ref |
| `AUTH_REFRESH_REJECTED` / `AUTH_REFRESH_EXPIRED` | bad refresh | |
| `AUTH_REFRESH_REUSE_DETECTED` / `AUTH_FAMILY_REVOKED` | stolen-token signal | **high-priority alert** (S-3) |
| `AUTH_LOGOUT` / `AUTH_LOGOUT_ALL` | session revocation | |
| `AUTH_MFA_ENROLLED` / `AUTH_MFA_CHALLENGE_FAILED` | staff TOTP | (S-2) |
| `AUTH_TIER_CHANGED` | T0↔T1↔T2↔T3 transitions | from/to tier in reason_code; both promotion + downgrade (§25.5) |
| `AUTH_VERIFICATION_REQUESTED` | citizen submits ID/rep/org evidence | evidence is an object-store **ref**, never bytes |
| `AUTHZ_TIER_DENIED` | `@RequiresTier` block | required vs live tier (MF-2 evidence) |
| `AUTHZ_SCOPE_DENIED` | `ScopeGuard` block | area/category/constituency mismatch (MF-3) |
| `AUTHZ_SELF_ACTION_BLOCKED` | conflict-of-interest block | D13/D16 |
| `ROLE_GRANTED` / `ROLE_REVOKED` / `ROLE_STATUS_CHANGED` | role lifecycle (e.g. Citizen→MP) | multi-hat audit (D12/D16) — *consumed* here, written where admin grants land |
| `IDENTITY_ERASED` | erasure tombstone | §25.1 — append, never mutate |

> Verification **decision** events (`APPROVE`/`REJECT`) and `ROLE_GRANTED` are **written by** the `moderation`/`admin` increments using the same `AuditEventService`; this document defines the contract and the event types so they are consistent.

---

## 12. Flow 10 — `SmsGateway` port + dev stub (EI-3)

- **Port** `SmsGateway` in `communications.domain.port`:
  ```
  SmsSendResult send(SmsMessage message);   // message: e164 recipient, body, sender-id ref, purpose, locale
  // SmsSendResult: providerMessageId?, accepted/queued/failed, reason
  ```
  - UCS-2 capable (full Swahili — sender must not lose diacritics); body is built by `OtpService`/notification from i18n templates (SW default, EN), **never** containing PII beyond the OTP itself.
- **Dev stub** `LoggingSmsGatewayStub` (adapter, active in `dev`/`test`): logs a **redacted** record (`to=+255…masked, purpose=SIGNUP, len=6, accepted`) and returns `accepted`. **It never logs the OTP value** (S-4). For local/CI flows it MAY expose the OTP through a dev-only test hook (e.g. a `test` Redis key / `@Profile("test")` accessor) so automated E2E can complete signup with **zero external calls** (ARCHITECTURE §7 stub principle).
- **Degradation (EI-3):** real adapter (later) does multi-route least-cost + DLR webhooks; on route failure → queue + backoff, and OTP can fall back to **email** channel. The citizen path never hard-fails on an SMS provider outage (PRD §21 DI2).
- **Idempotency:** `send` is keyed by challenge id so a relay retry does not double-send.

---

## 13. Data additions the engineers must build

> Numbering: existing identity baseline is `V3__identity.sql`; per ARCHITECTURE §4.1 identity changes live in the `V1xx` range, but the repo currently uses sequential `V1/V2/V3`. **Recommendation:** continue sequentially with **`V4__identity_auth.sql`** (otp_challenge) and **`V5__common_audit.sql`** (audit_event), and note the range-scheme in each file header. Forward-only; SQL comments mandatory (CLAUDE.md §8).

### 13.1 New table — `otp_challenge` (DB)
PII-light, short-lived OTP challenge (PRD §25.1 "minutes"). Postgres (not Redis) so a challenge survives a Redis flush mid-flow and is auditable; the **counters** stay in Redis.

| Column | Type | Notes |
|---|---|---|
| `id` / `public_id` / `version` / audit / soft-delete | (BaseEntity) | standard |
| `phone` / `email` | varchar | the target; **one of**; phone in E.164. (Consider storing `target_hash` only if policy forbids storing the raw target; default: raw target is acceptable here as it is transient and already on `app_user`, but it is **never logged**.) |
| `purpose` | varchar(16) | `SIGNUP` / `LOGIN` / `VERIFY` (enum) |
| `channel` | varchar(8) | `SMS` / `EMAIL` |
| `code_hash` | varchar(64) | HMAC-SHA-256 of the OTP (CryptoPort sub-key); **never** the plaintext code |
| `expires_at` | timestamptz | TTL ~5 min |
| `attempts` | int not null default 0 | capped (5) |
| `max_attempts` | int not null default 5 | |
| `consumed` | boolean not null default false | single-use |
| `user_id` | bigint null FK app_user | null for signup-before-account |

Indexes: `ix_otp_challenge_phone`, `ix_otp_challenge_expires` (sweep), unique on `public_id`.

### 13.2 New table — `audit_event` (DB, append-only)
Shape per §11.1. **App has INSERT+SELECT only** (DB grant); no UPDATE/DELETE; not soft-deleted. Indexes: `ix_audit_event_actor`, `ix_audit_event_subject`, `ix_audit_event_type_time (event_type, occurred_at)`, `ix_audit_event_correlation`.

### 13.3 New entity column (optional, this increment) — staff TOTP secret
- `app_user.mfa_totp_secret` `varchar(512)` **encrypted** via `EncryptedStringConverter` (envelope, CryptoPort), nullable; set on TOTP activation. (Alternative: a separate `user_mfa` table — recommended if more MFA factors land later; for KISS a nullable encrypted column on `app_user` is sufficient now.) Migration `V4` adds it; entity gains an encrypted field with `@ToString` exclusion (S-4).

### 13.4 Login-attempt + OTP counters — **Redis, not DB**
All anti-automation counters/locks (§9) are **Redis** keys with TTLs (ephemeral, cheap, auto-expiring; §25.1). **No** login-attempt table. Keys are **hashed** (`account_hash`, `phone_hash`, `ip`), carry only counts/timestamps, and are documented in the `AuthRateLimiter` Javadoc. Permanent record of *outcomes* lives in `audit_event`, not Redis.

### 13.5 No change required to existing tables
`app_user`, `profile`, `role`, `role_assignment`, `refresh_token`, `verification_request` already model everything else this increment needs (the security review confirmed the columns: `family_id/used/revoked`, `phone_verified/email_verified/id_verified`, `mfa_enabled`, `electoral_changed_at`). The only existing-table change is the optional §13.3 TOTP-secret column.

---

## 14. Endpoint list with tier requirements

> All under `/api/v1`. **Authz column** = the binding rule. `@RequiresTier` is enforced by the **live** resolver (MF-2), `@PreAuthorize` by method security, `@taarifuAuthz` by `ScopeGuard` (MF-3). Public = no token. Authenticated = any valid access token (T1+). All responses use the single `ApiResponse` envelope.

### 14.1 Authentication & token endpoints (`AuthController`)
| Method | Path | Authz / tier | Notes |
|---|---|---|---|
| POST | `/auth/otp/request` | **Public** (no token) | rate-limited; `202`, no enumeration; `Idempotency-Key` |
| POST | `/auth/otp/verify` | **Public** | consumes challenge; rate-limited |
| POST | `/auth/signup` | **Public** | phone+OTP → T1; one-account-per-phone (D11) |
| POST | `/auth/login/password` | **Public** | constant-time; staff → MFA step |
| POST | `/auth/login/otp` | **Public** | passwordless / recovery |
| POST | `/auth/login/totp` | **Public** (carries `mfaToken`) | staff TOTP step (S-2) |
| POST | `/auth/refresh` | **Public** (carries REFRESH token) | rotation + reuse-detection (S-3) |
| POST | `/auth/logout` | **Authenticated (T1+)** | revoke this refresh token |
| POST | `/auth/logout/all` | **Authenticated (T1+)** | revoke all families |
| GET | `/.well-known/jwks.json` | **Public** | published verification keys (RS256/ES256 phase B, MF-1) |

### 14.2 Profile, tier & MFA endpoints (`ProfileController`)
| Method | Path | Authz / tier | Notes |
|---|---|---|---|
| GET | `/profiles/me` | **Authenticated (T1+)** | own profile; never returns `idNo`/locations of others |
| PATCH | `/profiles/me` | `@RequiresTier("T1")` | profile completion → may promote to **T2** |
| POST | `/profiles/me/locations` | `@RequiresTier("T1")` | pin location (D11); private PII |
| PATCH | `/profiles/me/locations/{publicId}/electoral` | `@RequiresTier("T2")` + cooldown (D13) | set `isElectoral`; cooldown-guarded, audited |
| POST | `/profiles/me/verification` | `@RequiresTier("T2")` | submit ID/rep/org evidence (object-store ref) → drives **T3** on approval |
| GET | `/profiles/me/tier` | **Authenticated (T1+)** | returns **live** tier (UI hint source; same resolver) |
| POST | `/auth/mfa/totp/setup` | **Authenticated (T1+)** | provisioning secret (once) |
| POST | `/auth/mfa/totp/activate` | **Authenticated (T1+)** | sets `mfa_enabled=true` |

### 14.3 Tier requirements for downstream civic actions (designed here, enforced where the endpoint lands)
> Stated so the owning modules wire `@RequiresTier`/`@taarifuAuthz` consistently; **not** built in this increment.
| Action (future endpoint) | Tier | Extra authz |
|---|---|---|
| Follow rep/area, subscribe feed, save item, anonymous-eligible poll | **T1** | — |
| File **official** report, comment in moderated discussion, ask Q&A, respond to survey | **T2** | `isNotSelf` where applicable |
| **Sign petition, rate MP, vote binding poll, create organisation** | **T3** | **integrity fence:** tier + electoral scope (`canActInConstituency`) + one-per-person; **never** read token balance (D18) |
| Responder triage/resolve a report | role `RESPONDER_AGENT` | `@taarifuAuthz.canActOnArea` ∧ `canActOnCategory` ∧ `isNotSelf` (D16) |
| Representative announce / answer Q&A in own constituency | role `REPRESENTATIVE` | `@taarifuAuthz.canActInConstituency` ∧ `isNotSelf` |
| Moderate content | role `MODERATOR` + active TOTP | `isNotSelf` (no self-moderation, D16) |
| Admin reference-data / role grant | role `ADMIN`/`ROOT` + active TOTP | full `@PreAuthorize` |

---

## 15. NFR budget, degradation & test bar

- **Latency (PRD §15):** login/refresh p95 < 1s (writes); `me`/tier p95 < 500ms (reads, with the ≤30s tier cache). OTP send returns fast (`202`) — the SMS send is fire-and-forget through the gateway.
- **Availability / degradation:** SMS provider outage → OTP falls back to email + queue/backoff (EI-3); the citizen path never hard-fails. Redis outage → rate-limit **fails closed** for OTP/login (deny with `RATE_LIMITED`/`503`) rather than open (security over availability for the auth surface), while public reads continue.
- **Cost (PRD §15):** OTP SMS is **metered and rate-limited** (the most expensive auth action); prefer email where the user has a verified email; small payloads.
- **Test bar (CLAUDE.md §9/§10, ≥80% core):**
  - Unit: `TierService` predicates (incl. **forged claim ignored**, MF-2; downgrade §25.5); rotation/reuse invariants (S-3); rate-limit/lockout (S-2); `ScopeGuard` incl. ancestor-area + `isNotSelf` (MF-3); JWT fail-fast + iss/aud (MF-1).
  - Integration (Testcontainers Postgres + Redis): full signup→T1→T2 and refresh-reuse → family revocation; `@RequiresTier` end-to-end with a tampered token.
  - Contract: OpenAPI for every endpoint in §14; envelope-shape tests with the Angular/Flutter clients.
  - Audit: assert each §11.2 event is written with **no raw PII** and append-only (no UPDATE/DELETE grant).

---

## 16. Review-backlog traceability

| Review item | Where addressed |
|---|---|
| **MF-1** JWT secret fail-fast + iss/aud + RS256/ES256 | §10, ADR-0011 |
| **MF-2** live tier resolver, never trust claim, same-PR landing | §7, §14, ADR-0011 |
| **MF-3** scope-aware authz seam | §8, §14.3 |
| **S-1** HKDF/rotation for blind-index/KMS adapter | noted §3 (CryptoPort reuse) + ADR-0011 consequences; full KMS in EI-19 increment |
| **S-2** OTP/login anti-automation + staff TOTP | §9 |
| **S-3** refresh rotation reuse-detection invariants | §5.1, §15 tests |
| **S-4** PII redaction (no `@ToString`, no OTP/idNo in logs) | §3, §9, §12, §13.3 |
| **S-5** auditor never forged by anonymous path | §11 (`SYSTEM_ACTOR` sentinel, hashed actor), §15 tests |
| **L-1** append-only `audit_event`, refs/hashes, tombstone-on-erasure | §11, §13.2, ADR-0011 |

## 17. Cross-references
- **Decision of record:** [ADR-0011-authentication-and-tokens.md](../adr/ADR-0011-authentication-and-tokens.md) (extends ADR-0007).
- **Foundation security:** ARCHITECTURE.md §6; `docs/reviews/security-foundation-review.md`.
- **Product truth:** PRD §6.4, §7, §18, §25.1, §25.5; **engineering rules:** CLAUDE.md §3/§8/§12.
</content>
</invoke>
