# AUTH-Increment Review — Identity Auth (OTP signup/login, password login, JWT rotation, tier/scope authz, audit)

**Reviewer:** Salim Juma (security-privacy-engineer)
**Date:** 2026-06-23
**Scope:** `backend/` auth increment — `JwtService`/`JwtProperties`, `TokenService`, `LoginService`, `OtpService`, `SignupService`, `ProfileService`, `TierService`/`TierResolver`/`RequiresTierAspect`, `ScopeGuardImpl`, `InMemoryAuthRateLimiter`, `AuditEvent`/`AuditEventWriter`, `JwtAuthenticationFilter`/`SecurityConfig`, entities (`User`/`Profile`/`RefreshToken`/`OtpChallenge`/`RoleAssignment`), Flyway `V4`/`V5`, and the auth test suite.
**Prior backlog:** `docs/reviews/security-foundation-review.md` (MF-1, MF-2, MF-3, S-2..S-5, L-1).
**Grounded in:** PRD §18, §25.1; CLAUDE.md §3/§8/§12; locked decisions D11–D16/D18.

## Verdict

**GO, with conditions.** This is a genuinely strong increment. Every Must-fix and Should from the foundation review is now implemented and — critically — **tested at the right altitude**: the MF-2 keystone ("forged `trustTier=T3` claim is ignored, live DB tier governs") is proven end-to-end against real Postgres; refresh reuse-detection revokes the family and fails closed; the audit store is append-only and PII-free. Authorization never trusts the token's tier/role claim. No P1 blockers.

The must-fix-before-merge list below is **two correctness fixes** (one anti-automation rollback bug, one scope time-window gap) plus tightening items. None reopen a foundation regression.

---

## Backlog item disposition

### MF-1 — JWT secret guard + RS256 plan + iss/aud validation — **RESOLVED**
- Fail-fast secret-strength guard: `JwtProperties` compact constructor rejects absent/`<32 byte` secret with a **secret-free** message (`JwtProperties.java:47-71`). Tested: `JwtHardeningTest.absentSecret_failsFastAtStartup` / `weakSecret_failsFastAtStartup`.
- `iss` **and** `aud` validated on every `verify()` (`JwtService.java:104-111`). Tested: `JwtHardeningTest.tokenFromAnotherIssuer_isRejected` / `tokenForAnotherAudience_isRejected`. Config wires both from env (`application.yml:82-84`).
- Clock-skew leeway on exp/nbf (`JwtService.java:93-103`).
- RS256/ES256 swap correctly deferred and localised to `JwtService` (documented `JwtService.java:32-35`). **Residual (not a blocker):** still HS256/symmetric — same secret signs and verifies, so any verifier can forge. Acceptable pre-shared-staff-token; the ADR-0011 swap must land **before** any staff/admin token is issued in a shared environment. Tracked, not open.

### MF-2 — Live server-side trust-tier resolver, claim never trusted — **RESOLVED**
- `TierResolver` port in `common` (`TierResolver.java:18-28`); `TierService` implements it from **live DB state** highest-predicate-first, deny-by-default to T0 for unknown/suspended/no-profile (`TierService.java:64-124`).
- `RequiresTierAspect` reads `publicId` from context, asks the resolver, **never consults the `trustTier` claim** (`RequiresTierAspect.java:62-84`), throws `TIER_TOO_LOW`, audits `AUTHZ_TIER_DENIED` with `required=Tx,live=Ty` (no PII).
- **Keystone test passes:** `TierGateForgedClaimIntegrationTest.forgedT3Claim_isBlocked_becauseLiveTierIsT1` mints a valid-signature token with a forged `T3` claim for a live-T1 user and asserts `403 TIER_TOO_LOW` (`:66-80`). This is exactly the regression the foundation review demanded land in the same PR as the first tier gate (`DemoTierController`).

### MF-3 — Scope-aware authorization seam (area/category/constituency) — **RESOLVED (with a validity-window gap, see N-2)**
- `ScopeGuardImpl` (`@taarifuAuthz`) reads the caller's **active** `RoleAssignment` scope from the DB, never the token (`ScopeGuardImpl.java:49-93`); deny-by-default; empty-set = unrestricted-within-role is the one explicit permissive case and is documented. `isNotSelf` ships now for the D16 conflict-of-interest check.
- Ancestor (District-covers-Wards) resolution is honestly deferred to when the geography closure public API is consumed (documented `:28-32`) — current matching is direct-or-unrestricted, never falsely permissive.
- **Note:** no scoped endpoint exists yet, so this is contract-correct but exercised only by `isNotSelf`. Add `canActOnArea/Category/InConstituency` tests when the first scoped endpoint lands.

### S-2 — Login lockout/backoff + OTP anti-automation — **RESOLVED (one rollback bug, see N-1)**
- Login: `AuthRateLimiter.allowLoginAttempt` pre-check, exponential backoff after 3, hard lock after 10/15-min window, reset on success (`InMemoryAuthRateLimiter.java:76-120`). Uniform `UNAUTHENTICATED` for unknown/wrong/disabled + **constant-ish dummy-BCrypt** on miss (`LoginService.java:46-47,101-114`) — anti-enumeration + anti-timing. Tested: `AuthFlowIntegrationTest.passwordLogin_locksOutAfterRepeatedFailures`, `InMemoryAuthRateLimiterTest`.
- OTP: send-rate cap per recipient-hash, 6-digit `SecureRandom`, single-use, TTL ~5 min, attempt cap, code stored **hashed only** (`OtpService.java:89-156`). Code never logged; SMS stub redacts recipient and never logs the body (`LoggingSmsGatewayStub.java:45-49,64-70`).
- **MFA (TOTP) for staff is NOT in this increment.** `User.mfaEnabled` column exists but nothing enforces TOTP on ROLE_MODERATOR/ADMIN/ROOT. Acceptable now (no staff-role login path is exposed) but **must be enforced before any staff role can authenticate** — see N-4. Re-flagged from S-2, not closed.

### S-3 — Refresh rotation reuse-detection invariants — **RESOLVED**
- One transaction, **row write-lock** (`findByTokenHashForUpdate`, `PESSIMISTIC_WRITE`, `RefreshTokenRepository.java:38-40`): signature/type/iss/aud → row exists → not revoked → **`used=true` ⇒ revoke whole family + 403** → not expired → mark used + mint exactly one new token in the same family (`TokenService.java:106-157`). Fails closed on every negative branch.
- Only the **hash** is ever stored/looked up (`TokenService.sha256Hex`, `RefreshToken.tokenHash` unique). `revokeFamily` marks rows `revoked=true` (soft, not deleted) so `@SQLRestriction("deleted=false")` does not hide them from later reuse lookups — correct.
- Tested four ways unit (`TokenServiceTest`) **and** end-to-end (`AuthFlowIntegrationTest.refreshRotation_andReuseDetection_revokesFamily`, which also asserts the new same-family token is killed). Exactly the invariants the review pinned.

### S-4 — PII redaction in logs / serialization — **RESOLVED**
- No `@ToString`/`@Data`/Lombok on `User`/`Profile`/`OtpChallenge`/`RefreshToken`; explicit accessors with "never log" Javadoc (`OtpChallenge.java:33-34,146-153`). No `log.*`/`System.out`/`printStackTrace` anywhere in identity/security/audit; the only logger is the SMS stub, which masks the MSISDN and logs body length not body.
- Entities never cross the wire: controllers return DTOs only; `MeView` carries the **owner's own** phone/email by design and never `idNo` or others' data (`ProfileService.java:170-185`). `idNo` remains field-encrypted (foundation) and absent from all DTOs.
- `server.error.include-message=never` + `include-stacktrace=never` (`application.yml:53-54`). Audit reasons are machine codes; integration test asserts **no raw phone in any audit text column** (`AuthFlowIntegrationTest.assertNoRawPhoneInAudit`).
- **Residual (not a blocker):** no global log-scrubbing pattern / structured-logging filter for `Authorization`/`phone`/`id_no` as a backstop against future careless `log.info`. Recommend adding one before more modules log. Low severity.

### S-5 — AuditorAware / actor attribution can't be forged anonymously — **RESOLVED**
- `AuditEvent.actorPublicId` is `null` for anonymous, `SYSTEM_ACTOR` sentinel for system, never a spoofable id (`AuditEvent.java:67-72`, `V5:47`). `ScopeGuardImpl.isNotSelf` and `RequiresTierAspect` derive the actor from `CurrentUser.current()` which only returns a principal when `auth.isAuthenticated()` and the details are a real `CurrentUser` (`CurrentUser.java:43-49`). The filter sets that principal only after a verified ACCESS token (`JwtAuthenticationFilter.java:64-86`).

### L-1 — Immutable append-only audit store (refs/hashes, tombstone-on-erasure) — **RESOLVED**
- `AuditEvent` is deliberately **not** a `BaseEntity`: no version/updated/deleted columns; insert-once (`AuditEvent.java:19-46`). `AuditEventWriter` hashes the client IP before persist and chains `entry_hash = H(prev_hash ∥ canonical(non-PII fields))` (`AuditEventWriter.java:64-102`). Canonical form **excludes** every potentially-PII field by construction.
- `V5__common_audit.sql` enforces append-only at the **database**: grants `INSERT, SELECT` and `REVOKE UPDATE, DELETE, TRUNCATE` from the runtime role (`:98-108`), with a documented dev fallback when the least-privileged role is absent. Erasure path = append `IDENTITY_ERASED` tombstone (catalogued). Tested: `AuditEventWriterTest` (IP hashed, chain set), `AuthFlowIntegrationTest` (events written, no raw PII).

---

## New findings

> Severity: **P2** = fix before merge; **P3** = fix in this increment's follow-up; **P4** = hardening/track.

**N-1 (P2) — OTP verify-attempt DB counter is rolled back on every wrong code; the per-challenge DB cap is non-functional.**
`OtpService.verify` is `@Transactional`; on a wrong/invalid code it calls `challenge.registerFailedAttempt()` then throws `ApiException` (a `RuntimeException`) (`OtpService.java:130-150`). Default Spring rollback reverts the managed entity's `attempts++`, so `otp_challenge.attempts` never advances across failed attempts and `isVerifiable`'s `attempts < maxAttempts` DB cap (`OtpChallenge.java:142-143`) never trips. The flow is still bounded by the **in-memory** `allowOtpVerifyAttempt` (caps at 5, not transactional, survives rollback — `OtpService.java:140-143`), so this is **defense-in-depth degraded, not an open hole** — but the in-memory limiter is single-instance and lost on restart (by its own Javadoc), so on a multi-instance deploy or a restart an attacker regains attempts while the DB counter that was meant to be the durable backstop reads 0.
- **Fix:** persist the failed-attempt increment in its own committed unit (mirror the audit `REQUIRES_NEW` pattern, or do a direct `UPDATE ... SET attempts = attempts + 1` outside the rolling-back tx), **or** add `@Transactional(noRollbackFor = ApiException.class)` to `verify` (simplest; but then ensure no other state must roll back on that path — currently only the increment is written before the throw, so `noRollbackFor` is safe here). Add a Testcontainers test that 5 wrong codes burn the challenge **by the DB counter** with the in-memory limiter disabled/bypassed.

**N-2 (P2) — `ScopeGuard` and tier/role resolution ignore `RoleAssignment.effectiveFrom/effectiveTo`; an expired or not-yet-effective grant still authorizes.**
`ScopeGuardImpl.activeAssignments()` filters only on `RoleStatus.ACTIVE` (`ScopeGuardImpl.java:105-110`) and `TokenService.mintPair` derives role claims from `findByUser` with no time filter (`TokenService.java:195-199`). The entity carries `effective_from`/`effective_to` (`RoleAssignment.java:96-102`) precisely so a grant can be future-dated or time-boxed (e.g. an election-period responder mandate). A grant that is `ACTIVE` but past its `effective_to`, or before its `effective_from`, will currently pass scope checks and emit a role authority. For an election-period system this is an integrity/neutrality risk (a lapsed representative/responder still acting).
- **Fix:** add `effectiveFrom <= now AND (effectiveTo IS NULL OR effectiveTo > now)` to the active-assignment predicate used by **both** the scope guard and the role-claim source (one shared repository query, DRY). Add a test for a lapsed grant being denied. If time-boxing is genuinely out of scope for this increment, that is an explicit product decision — route to architect — but the columns existing and being silently ignored is the dangerous middle state.

**N-3 (P3) — Audit hash-chain head read is racy under `REQUIRES_NEW`; concurrent auth events can fork/duplicate `prev_hash` and silently weaken tamper-evidence.**
`AuditEventWriter.currentChainHead()` does a `SELECT ... ORDER BY id DESC LIMIT 1` then computes `entry_hash` and inserts, all under `REQUIRES_NEW` with no serialization (`AuditEventWriter.java:73-84`). Two concurrent audit writes (very common: a login burst, a refresh storm) can both read the same head and both chain off it — two rows share a `prev_hash`, and a later deletion of one becomes harder to detect because the chain is no longer linear. `sha256Hex` also swallows failures to `""` (`:108-116`), so a hashing fault yields an empty link rather than a flagged break.
- **Fix (this is documented as best-effort, so this is a P3 not a P2):** either serialize the head read+insert (advisory lock / single-writer / DB sequence-anchored chain), or drop the linear chain in favour of a per-row HMAC over `(id, canonical)` plus a periodic Merkle checkpoint, which needs no global ordering. At minimum, change the comment to state the chain is non-linear under concurrency so an auditor does not over-trust it, and emit a distinct marker (not `""`) on hash failure.

**N-4 (P3) — Staff TOTP (MFA) is unenforced; `mfaEnabled` is decorative.**
Carried from S-2. No code consults `User.mfaEnabled`; no second-factor step exists in `LoginService`. Before **any** ROLE_MODERATOR/ADMIN/ROOT account can authenticate (no such login path is exposed in this increment, which is why this is P3 not P2), staff login must require TOTP. Add the second-factor challenge to the password/OTP login path gated on `mfaEnabled` (or on holding a staff role), and a test that a staff account cannot complete login without the second factor. **Do not ship a staff-login surface without this.**

**N-5 (P4) — `JwtAuthenticationFilter` stores the principal in `Authentication.getDetails()`, which is unconventional and collides with the usual web-details slot.**
`authenticate()` puts the rich `CurrentUser` into `setDetails(principal)` (`JwtAuthenticationFilter.java:84-86`) and `CurrentUser.current()` reads it back from `getDetails()` (`CurrentUser.java:45`). It works and is internally consistent, but `getDetails()` conventionally holds `WebAuthenticationDetails` (remote IP / session) — the unused `WebAuthenticationDetailsSource` import at `:11` is a tell. A future filter/library that sets web details would silently break `CurrentUser.current()` and thus tier/scope resolution (fail-closed to "unauthenticated", so safe, but a latent outage). **Prefer** making `CurrentUser` the `principal` (it already is partially — `getPrincipal()` is the bare UUID; consolidate to the record) and remove the dead import. Low risk; tidy before more code depends on the details slot.

**N-6 (P4) — In-memory rate limiter is the only anti-automation layer and is single-instance/non-durable.**
`InMemoryAuthRateLimiter` is correct and honestly documented as dev-default (`:12-30`), yielding to a `redisAuthRateLimiter` via `@ConditionalOnMissingBean`. But until that Redis adapter exists, **all** of login lockout, OTP send-rate, and OTP verify-cap are per-instance and reset on restart. Combined with N-1 (DB OTP counter rolled back), a multi-instance production deploy currently has **no shared durable anti-automation**. Not a merge blocker for a single-instance/dev posture; **must be a launch-gate item** — the Redis adapter is a precondition for the production anti-automation claim in PRD §18.

**N-7 (P4) — Logout/refresh idempotency leaks nothing, but `logout` resolves `row.getUser().getPublicId()` for audit, touching a lazy association after a soft-delete restriction.** Minor: confirm a revoked/`deleted` user row doesn't NPE the audit actor on `logout` (`TokenService.java:166-173`); the `@SQLRestriction` on `User` could make `getUser()` return a proxy that resolves to filtered state. Add a test or null-guard the actor.

---

## Must-fix before merge

1. **N-1 (P2)** — Make the OTP failed-attempt DB increment durable (commit it / `noRollbackFor=ApiException`); test the DB attempt-cap independently of the in-memory limiter.
2. **N-2 (P2)** — Honour `RoleAssignment.effectiveFrom/effectiveTo` in **both** the scope guard and the role-claim source; test a lapsed grant is denied. (Or get an explicit architect decision to defer time-boxing — but stop silently ignoring the columns.)

## Strongly recommended in this increment (do not let slip to "later")

3. **N-3 (P3)** — Fix or correctly caveat the audit chain-head race; don't swallow hash failures to `""`.
4. **N-4 (P3)** — Land staff TOTP enforcement **before** exposing any staff-role login; gate on `mfaEnabled`/staff role with a test.

## Track to launch gate (not merge blockers)

5. **N-6 / MF-1 residual** — Redis-backed `AuthRateLimiter` and the RS256/ES256 signer swap are both **launch-gate preconditions** for the PRD §18 anti-automation and token-integrity claims. Add SAST/DAST/SCA + container scan to CI (CLAUDE.md §5). Confirm `actuator` health-details are not `always` in prod and no management port is public (foundation L-2).
6. **N-5 / N-7 (P4)** — Tidy the principal/details slot and the logout lazy-user actor.

---

**Bottom line:** The hard parts are right — the live-tier keystone, refresh reuse-detection, append-only PII-free audit, anti-enumeration login, and the no-PII-in-logs discipline are all implemented **and** tested at the correct altitude. Fix N-1 and N-2 (both small, both have a clear fix), land N-3/N-4 in close follow-up, and this increment merges clean. None of the foundation regressions (wildcard-CORS, logged root password, missing method security, refresh-as-access, public actuator) can recur on this code.
