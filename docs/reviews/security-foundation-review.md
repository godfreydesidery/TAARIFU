# Security-Foundation Review — Backend Scaffold

**Reviewer:** Salim Juma (security-privacy-engineer)
**Date:** 2026-06-23
**Scope:** `backend/` — `SecurityConfig`, `JwtService`/`JwtAuthenticationFilter`/`JwtProperties`/`TokenType`/`CorsProperties`, identity PII handling (`Profile.idNo` / `CryptoPort` / `DevKeyCryptoAdapter` / `EncryptedStringConverter`), Flyway `V1`–`V3`, and `application*.yml`.
**Grounded in:** PRD §18, §19 (D13/D15/D18), §25.1; CLAUDE.md §3/§8/§12.
**Verdict:** Strong foundation. **No P1/P2 launch-gate blockers in what is scaffolded.** The historical regressions (wildcard-CORS-with-credentials, hardcoded/logged root password, missing `@EnableMethodSecurity`, refresh-token-as-access-token, public `/actuator/**`) are all closed at the foundation level. Findings below are mostly contracts to lock *before* the auth increment builds behaviour on top of this — cheap now, expensive later.

> Severity key: **Must-fix** = fix in/with the next increment before behaviour lands on it; **Should** = address in the auth increment; **Later** = production-hardening / pre-launch-gate.

---

## What is already correct (no action)

- **`@EnableMethodSecurity` is ON** (`SecurityConfig` L47) with `.anyRequest().authenticated()` deny-by-default (L100). Public surface is an explicit allow-list, not a hole. Directly fixes the prior "authenticated-only admin / missing `@PreAuthorize`" gap (PRD §18, §7.1).
- **CORS is correct:** explicit origins only, `allowCredentials` enabled *only* when concrete origins are configured (`SecurityConfig` L137), empty-by-default allow-list (`CorsProperties`). The exact spec violation from the prior repos cannot recur here.
- **No secrets in source:** JWT secret, CORS origins, crypto key, DB creds all `${ENV}` with empty (not baked) fallbacks (`application.yml` L81/L87/L90); `application-sample.yml` is placeholders only. `password_hash` is BCrypt-only, nullable for OTP accounts; no seed/root password anywhere.
- **tokenType is enforced on every verify** (`JwtService.verify` L92–96, `TokenType`): a refresh token cannot satisfy the access path and vice-versa. Refresh tokens carry no role/tier claims (L68). Refresh tokens are persisted **hashed** with family/used/revoked columns for rotation + reuse-detection (`RefreshToken`, `V3` L298). JWT subject is the immutable `publicId`, never phone/handle.
- **PII-at-rest is real, not stubbed:** `Profile.idNo` is AES-GCM field-encrypted via converter; dedup runs off a deterministic HMAC **blind index** (`id_hash`, unique) so `(idType,idNo)` uniqueness is enforced **without decrypting** (D15). Crypto sits behind `CryptoPort` so the dev adapter swaps for KMS with no entity change (EI-19). `verification_request.evidence_ref` stores an object-store key, never document bytes.
- **Audit attribution is wired** (`BaseEntity` `@CreatedBy/@LastModifiedBy` + `AuditorAwareImpl` → `publicId`, `SYSTEM_ACTOR` sentinel). Soft-delete/tombstone columns exist on every entity, supporting the §25.1 erasure-by-anonymisation model. Auth-layer 401/403 render the single envelope and **do not leak why** a token was rejected (`SecurityConfig` L101–107, filter L68–70).

---

## Findings

### Must-fix (lock the contract before the auth increment builds on it)

**MF-1 — JWT secret/key has no minimum-strength or presence guard; HS256 + symmetric secret is the wrong long-term primitive.**
`JwtProperties.secret` is bound from env with no validation. An empty/short secret (the empty default in `application.yml` L81, or a weak ops-set value) yields a startup that *appears* healthy but mints forgeable tokens — a silent T0→ROOT escalation path. HS256 also means the same secret signs **and** verifies, so any service that can verify can forge (PRD §18 "secrets from env", ADR-0007 targets RS256/ES256).
- Fail fast on startup if `secret` is absent or `< 32 bytes` (256-bit) of decoded entropy; reject the empty default rather than booting with it.
- Keep the migration to asymmetric RS256/ES256 (ADR-0007) on the auth-increment plan — it is a localised `JwtService` signer change, but it must happen before any staff/admin token is issued in a shared environment.
- Confirm `iss` is validated on `verify()` (currently issued but not checked) and add an `aud` claim if web-admin and citizen apps will ever diverge.

**MF-2 — `RequiresTier` is a declared-but-unenforced annotation; the integrity fence is not yet wired.**
`RequiresTier` ships with no interceptor (by its own Javadoc), and the JWT carries a `trustTier` **hint**. Until the live-tier re-resolver exists, any high-stakes gate written against the token claim is trusting a client-presentable value — exactly the D13/§18 "re-check server-side" rule it is meant to satisfy. This is fine *today* (no protected action exists — geography reads are public), but it is a **landmine**: the first `@PreAuthorize("...T3...")` against the claim is a real authorization bug.
- Auth increment must land the server-side live-tier resolver **in the same PR** as the first tier-gated endpoint, and the integrity fence must check **tier + electoral scope + one-per-person only**, never a token balance (D18, §23.5). Add a regression test asserting a forged/elevated `trustTier` claim is ignored.

**MF-3 — Method-level scope (area/category/constituency) has no enforcement seam yet.**
PRD §18 requires RBAC **+ attribute scope**, and `role_assignment` carries `constituency_id` + `role_assignment_area`/`_category` sets. Roles flow into the token as `ROLE_*` authorities, but there is **no scope-aware authorization component** — `hasRole(...)` alone will pass a MODERATOR/REP for areas/constituencies outside their grant. This is the half of "deny-by-default" that URL+role config cannot express.
- Before the first scoped admin/responder/representative endpoint, introduce a scope-checker (e.g. a `@PreAuthorize` bean method `@taarifuAuthz.canActOn(#areaId, #categoryId)`) reading the live `RoleAssignment` scope, **not** the token. Pair with `CurrentUser` (already carries roles/tier) but resolve scope server-side. Owner: backend + security review.

### Should (address within the auth increment)

**S-1 — Blind-index + encryption share one root key with only SHA-256 domain separation; no per-tenant/rotation story for the index.**
`DevKeyCryptoAdapter` derives the AES key and HMAC index key from the same configured key via `SHA-256("enc:"/"idx:" + raw)`. Adequate for dev, but: (a) plain SHA-256 is not a KDF — use HKDF for the production/KMS adapter; (b) the **blind index is deterministic and unsalted across the whole table**, so its compromise enables offline equality/enumeration against the (small, structured) national/voter-ID keyspace. Document this as accepted residual for dev only and ensure the KMS adapter (EI-19) uses HKDF-derived sub-keys and treats the index key as separately rotatable. Not a launch blocker, but call it out in the threat model now so it is not forgotten at the gate.

**S-2 — No password/OTP anti-automation primitives exist yet (expected, but must be tracked).**
PRD §18 mandates lockout/backoff, OTP rate-limiting, and anti-automation; none are scaffolded (correct — no auth controller yet). Flag so it is not assumed "done":
- Login: per-account + per-IP attempt counter, exponential backoff, lockout; constant-time failure response (no user-enumeration via timing or distinct error).
- OTP: send-rate cap per MSISDN, attempt cap, short TTL (§25.1 "minutes"), single-use, no OTP value in logs.
- MFA (TOTP) for staff (`User.mfaEnabled` column exists) — enforce on ROLE_MODERATOR/ADMIN/ROOT before those roles can act.

**S-3 — Refresh-rotation reuse-detection logic is modelled in DB but not implemented; pin the invariants in tests.**
`RefreshToken` has `family_id/used/revoked` but rotation/reuse-revocation is behaviour the auth increment owns. Add tests asserting: presenting a `used=true` token revokes the whole `family_id`; rotation issues exactly one new live token per family; revoked/expired tokens fail closed. Store only the hash (already enforced by schema unique on `token_hash`).

**S-4 — PII-redaction in logs is asserted by convention, not enforced.**
Comments correctly say "never log idNo," and crypto failure messages omit plaintext (`DevKeyCryptoAdapter` L77). But there is no structural guard: a `Profile.toString()`/serializer/`log.debug("{}", profile)` would leak the decrypted `idNo`. Add (auth increment): no Lombok `@ToString` on `Profile`/`User`; a Jackson rule or DTO boundary that never serialises `idNo`; and ideally a log-scrubbing pattern for `id_no`/`phone`/`Authorization`. `server.error.include-message=never` is already set — good.

**S-5 — `AuditorAwareImpl` trusts `authentication.isAuthenticated()` + principal-is-UUID; confirm no anonymous-auth path forges createdBy.**
With stateless JWT this is fine, but once any `AnonymousAuthenticationToken` or test principal can reach the auditor, attribution could silently fall to a non-UUID and skip to `SYSTEM_ACTOR`, or (worse, future) a spoofable id. Keep the principal strictly the verified `publicId` and add a test that an unauthenticated write records `SYSTEM_ACTOR` and an authenticated one records the token subject.

### Later (production hardening / pre-launch-gate)

**L-1 — No dedicated immutable audit-event store yet (only per-row `BaseEntity` audit columns).** PRD §18/§25.1 require an *immutable audit trail* that records security/verification/multi-hat/erasure events as append-only entries holding **references/hashes, not raw PII**, with erasure writing a new tombstone event rather than mutating history. The per-row `created_by/updated_by` columns are not that. Design the `audit_event` append-only table (or outbox-backed log) when the first auditable action (verification decision, role grant, electoral change, takedown) lands. Owner: solution-architect + security.

**L-2 — Secure transport/headers + actuator hardening are deployment concerns, confirm at the gate.** TLS-everywhere, HSTS/secure headers, and `/actuator/health` detail exposure (currently `include: health,info` — good; verify `health.show-details` is not `always` in prod and that no management port is public). Add SAST/DAST/SCA + container scan to CI per CLAUDE.md §5.

**L-3 — Key/secret rotation runbook.** Dev uses a single static crypto key (cannot rotate — adapter Javadoc says so). Pre-launch must run KMS envelope encryption (EI-19) with a documented rotation + lease-cache + degradation procedure, and JWT signing-key rotation. Route hosting/residency of national/voter-ID key material to Legal (D-Q9, PDPA §15).

---

## Top findings (act-on-next)

1. **MF-1** — Guard the JWT secret: fail-fast on absent/<256-bit secret; plan the RS256/ES256 swap before any shared-env staff token; validate `iss`.
2. **MF-2** — Wire the live server-side trust-tier resolver **in the same PR** as the first `@RequiresTier`/tier-gated endpoint; never trust the token `trustTier` claim.
3. **MF-3** — Add the scope-aware authorization seam (area/category/constituency from live `RoleAssignment`) before the first scoped admin/responder/representative endpoint; `hasRole` alone is insufficient.
4. **S-2** — Track login lockout/backoff + OTP anti-automation + staff TOTP as explicit auth-increment deliverables (not implied by the scaffold).
5. **L-1** — Design the append-only `audit_event` store (references/hashes, no raw PII; tombstone-on-erasure) before the first auditable security/verification action.

**Foundation files reviewed:**
`d:\My_Works\TAARIFU\backend\src\main\java\com\taarifu\common\security\SecurityConfig.java`,
`...\common\security\JwtService.java`, `...\JwtAuthenticationFilter.java`, `...\JwtProperties.java`, `...\TokenType.java`, `...\CorsProperties.java`, `...\RequiresTier.java`, `...\CurrentUser.java`,
`...\common\domain\port\CryptoPort.java`, `...\common\infrastructure\adapter\DevKeyCryptoAdapter.java`, `...\common\infrastructure\persistence\EncryptedStringConverter.java`,
`...\identity\domain\model\Profile.java`, `...\User.java`, `...\RefreshToken.java`,
`...\common\persistence\AuditorAwareImpl.java`, `...\common\domain\model\BaseEntity.java`,
`d:\My_Works\TAARIFU\backend\src\main\resources\db\migration\V3__identity.sql`,
`d:\My_Works\TAARIFU\backend\src\main\resources\application.yml`, `...\application-sample.yml`.
