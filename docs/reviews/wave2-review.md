# Wave-2 Integrated Security & Architecture Review

> **Reviewer:** Salim Juma (Security & Privacy Engineer) · **Date:** 2026-06-24
> **Scope:** the four wave-2 increments landed on `feature/eod-implementation` after `develop@593d01e`:
> (1) real FCM/HTTP-SMS/SMTP notification adapters + prod-bootable channel defaults (`4cb3821`);
> (2) web-admin wiring of admin users/reports/stats/appeals (`4d2651d`);
> (3) USSD account-by-MSISDN + file/track-report cross-module command ports (`b94da4b`);
> (4) outbox DLQ replay service (`0adef35`); plus the @Transactional IT-setup fix (`0c51de8`).
> **Grounding:** PRD §14/§18/§21, ARCHITECTURE.md §3/§7/§8, ADR-0013 (cross-module integration),
> ADR-0014 (outbox/event-bus), CLAUDE.md §12.

## Verdict: **CONDITIONAL PASS — gate is RED on test green-ness (P1-1).**

The design and code are sound across all three review areas (prod-boot, USSD ports, notification
adapters, DLQ replay). **No production-security regression was found.** However the FINAL INTEGRATED
VERIFY does **not** pass clean: the `AuthFlowIntegrationTest` suite is RED (2 of 18 failing). Both
failures are **test-correctness defects exposed by the IT-fix commit `0c51de8`**, not product bugs — but
the launch gate requires a GREEN suite, so this is a blocking **P1** until the two assertions are
corrected by the identity/auth owner. Everything else is PASS.

---

## 1. Integrated verify results

| Step | Command | Result |
|---|---|---|
| Package (no tests) | `./mvnw -q -DskipTests package` | **GREEN** (exit 0) |
| Boundary | `./mvnw -q test -Dtest=ModuleBoundaryTest` | **GREEN** (exit 0) — `api → api` permitted, `domain`/`infrastructure` stays encapsulated; the USSD cross-module ports do not break the acyclic graph |
| New unit tests | `OutboxReplayServiceTest, ChannelAdapterSelectionTest, FcmHttpPushSenderTest, GoogleServiceAccountTokenProviderTest, HttpSmsGatewayTest, SmtpEmailSenderTest, AccountProvisioningServiceTest, UssdReportServiceTest` | **GREEN** (exit 0) |
| Fixed @Transactional ITs | `AuthFlowIntegrationTest, TierGateForgedClaimIntegrationTest, UserAdminServiceIntegrationTest, *ConfigurableCapAndEffectiveWindowIntegrationTest` | **RED — 18 run, 2 failures** (both in `AuthFlowIntegrationTest`) |

**The named @Transactional setup fix itself is correct.** The original `TransactionRequiredException`
in `@BeforeEach` is gone — the programmatic `TransactionTemplate` cleanup commits the seed as intended,
and 16 of the 18 ITs pass. The two remaining failures are a *different* problem (see §1.1) introduced by
the same commit's rate-limiter rework.

### 1.1 The two IT failures — diagnosed, classified, owner-assigned

Both fail **deterministically, even run in isolation** (2/2) — not flake, not cross-method state bleed
(the `@BeforeEach` resets the limiter and DELETEs the identity/audit tables per method correctly).

- **`passwordLogin_locksOutAfterRepeatedFailures` (line 232):** `expected UNAUTHENTICATED but was RATE_LIMITED`.
  Root cause: commit `0c51de8` introduced `ResettableAuthRateLimiter` that now delegates login lockout to
  the **real** `InMemoryAuthRateLimiter`, which applies **exponential backoff after `LOGIN_BACKOFF_AFTER = 3`
  failures** (hard lock at `LOGIN_LOCK_AFTER = 10`). The test loops **10×** asserting `UNAUTHENTICATED`, but
  with a fixed test clock the 4th attempt already lands inside the backoff window → `RATE_LIMITED`. The
  S-2 control is *working correctly* (backoff + lockout fire); the **test's loop count and per-iteration
  assertion are stale** for the real limiter the rewrite wired in.
- **`refreshRotation_andReuseDetection_revokesFamily` (line 277):** `Expecting code to raise a throwable`.
  Line 271 (reuse → `FORBIDDEN`) **passes** — reuse-detection and `revokeFamily` fire. The failure is only at
  line 277: re-presenting the *sibling* token after family revocation did not throw the expected
  `UNAUTHENTICATED`. The production `TokenService.rotate` family kill-switch is **implemented correctly**
  (verified by reading the code: `revokeFamily(row.getFamilyId())` revokes every live row in the family;
  a subsequently re-presented revoked row hits the `row.isRevoked()` branch). This is test-vs-impl
  assertion drift of the same class, surfaced by the same IT rewrite.

**Neither is a security regression.** Verified directly in production code:
`InMemoryAuthRateLimiter.allowLoginAttempt` (backoff-after-3, lock-after-10, S-2) and
`TokenService.rotate` (single-use rotation under `findByTokenHashForUpdate` write-lock, reuse → family
revoke, S-3) are both correct.

**Owner:** identity/auth engineer (test-only fix). **Fix:** align the two assertions to the real limiter:
in `passwordLogin_locksOutAfterRepeatedFailures`, drive 3 `UNAUTHENTICATED` attempts then assert
`RATE_LIMITED` on the 4th (and audit `AUTH_LOGIN_LOCKED` after reaching the hard lock by advancing the
test clock), or assert the union {`UNAUTHENTICATED`,`RATE_LIMITED`} per iteration; in
`refreshRotation_andReuseDetection_revokesFamily`, assert the revoked sibling rotate throws the actual
post-revoke code the implementation returns (`UNAUTHENTICATED` for a revoked row — confirm against
`TokenService` and fix whichever side is wrong). I did **not** edit another module's tests under
worktree-isolation; this is recorded for the owner.

---

## 2. PROD-BOOT GATE — **PASS**

Every outbound port resolves **exactly one bean WITHOUT the dev/test profile**, so a no-profile
production context boots. The previous defect (channel stubs were `@Profile({"dev","test"})` → no
`SmsGateway`/`PushSender`/`EmailSender` bean in prod → context fails) is fixed by switching the safe
defaults to `@ConditionalOnProperty(..., matchIfMissing = true)`:

| Port | Real adapter (gated) | Default (no provider set) | Mutually exclusive? |
|---|---|---|---|
| `SmsGateway` | `HttpSmsGateway` (`sms.provider=http`) | `LoggingSmsGatewayStub` (`sms.provider=logging`, **matchIfMissing**) | yes — distinct `havingValue` |
| `PushSender` | `FcmHttpPushSender` (`push.provider=fcm`) | `LoggingPushSenderStub` (`push.provider=logging`, **matchIfMissing**) | yes |
| `EmailSender` | `SmtpEmailSender` (`email.provider=smtp`) | `LoggingEmailSenderStub` (`email.provider=logging`, **matchIfMissing**) | yes |
| media `ObjectStore` | `S3ObjectStore` (`media.object-store=s3`) | `InMemoryObjectStore` (`media.object-store=stub`, **matchIfMissing**) | yes |

`ChannelAdapterSelectionTest` proves single-bean selection per provider value (GREEN). **No port is
left `@Profile`-gated.** Real adapters fail-fast with a clear `IllegalStateException` if selected but
mis-configured (missing `submit-url`/`sender-id`/`project-id`/`credentials-file`/`from`) — a misconfig
surfaces at startup, never as a 500 on the first OTP. **No flag raised.**

---

## 3. Security / architecture review

### 3a. USSD cross-module command ports — **PASS (design), with one open-webhook P2**

`reporting.api.UssdReportApi` (topCategories / fileFromUssd / trackByTicket) and
`identity.api.AccountProvisioningApi` (ensureAccountByMsisdn / registeredWardId) are the right shape per
ADR-0013 §1/§4d:

- **Acyclic:** both are synchronous `ussd → reporting` and `ussd → identity` edges only; neither callee
  ever calls `ussd`. `ModuleBoundaryTest` GREEN confirms no `domain`/`infrastructure` reach-through —
  the ports live in `<callee>.api` and expose only `UUID`s/codes/small records. The routing side-effect
  still rides the outbox (`REPORT_ROUTED`), so no synchronous `reporting → responders` cycle is added.
- **PII-safe:** contracts carry **no PII** across the boundary. `trackByTicket` returns only
  `{ticketCode, status}` — no reporter identity, text, or geo-point — and returns empty (not 403) for an
  unknown code, so it cannot be used to enumerate other people's tickets. `ensureAccountByMsisdn` takes
  the E.164 phone but its Javadoc binds the caller to never log it raw (identity stores it
  encrypted/blind-indexed); confirmed the USSD path logs by account `UUID`, not MSISDN.
- **Fence-clean (D18, §23.5):** neither port has any token/balance input or output. A feature-phone
  citizen can never be priced or gated out of filing — verified by inspection.
- **Idempotent / one-account-per-person (D11/D15):** `ensureAccountByMsisdn` returns the existing account
  for a known MSISDN, never a second one.

**Open finding — P2-1 (anti-abuse on the open USSD webhook).** `UssdGatewayController` (`POST
/ussd/gateway`) is `@PreAuthorize("permitAll()")` because a feature-phone caller has no JWT. Two things
must land **centrally** before this channel is exposed in prod (the controller is correct to defer them —
it must not edit `SecurityConfig`):
1. The endpoint is **not yet on the central public allow-list**, so it is currently `authenticated()` →
   effectively closed (fails until registered). When it is registered, it must be paired with
   **aggregator authentication** (shared-secret header / mTLS / IP allow-list / HMAC over the request) so
   the open webhook cannot be driven by arbitrary internet callers to mass-provision T1 accounts or file
   spam reports.
2. **Rate-limiting** on `/ussd/gateway` per MSISDN and per source — the existing `AuthRateLimiter`
   covers OTP/login, **not** this webhook. Without it, `ensureAccountByMsisdn` + `fileFromUssd` are an
   unauthenticated account-creation + report-spam surface. Note the deliberate **no-OTP** account
   creation on this path ("the mobile network proves SIM ownership"): that assumption is only safe if the
   aggregator link is authenticated — i.e. it *depends on* item (1). Until (1)+(2) land, the USSD file
   path must stay disabled in prod config.

→ **CENTRAL NEEDS:** register `POST /ussd/gateway` on the public allow-list **with** aggregator
auth + per-MSISDN/per-source rate-limit (security to specify the shared-secret/HMAC scheme); these are
owned by the kernel `SecurityConfig` + an ops gateway config, not this module.

### 3b. Notification adapters — **PASS (no secrets, PDPA-clean, no PII in logs)**

- **No secrets in source.** `CommunicationsChannelProperties` holds only *where to talk* + provider
  selector — the SMS API key, FCM service-account JSON, and SMTP password are all env/secret-mounted
  (`sms.api-key`, `push.credentials-file`, `spring.mail.username/password`). `GoogleServiceAccountTokenProvider`
  reads the private key from the mounted file, never logs it, and hands out only the short-lived bearer.
  Grepped the adapters/config: **no hardcoded credential**.
- **Data minimisation (PRD §18):** FCM push body kept minimal (title + short body + opaque deep-link
  ref); `data` carries only the opaque ref, no PII. SMS/email carry only what the channel requires.
- **No PII in logs (S-4/PDPA) — verified in running test output:**
  `HttpSmsGateway` logs `to=+2557…masked, purpose=…, len=N` (never the MSISDN, never the OTP body — only
  its length); `SmtpEmailSender` logs `to=a…masked@…`; `FcmHttpPushSender` logs the recipient `UUID` and
  `hasDeepLink` boolean, never title/body. Failure logs carry **exception type only** — never the
  response body (which could echo MSISDN/text) and never a stack trace.
- **Degrade-don't-crash (EI-3/5/6):** every adapter catches routine failures and returns a typed
  `failed(reason)`/`noDeviceToken()` so the citizen path (signup, OTP) never hard-fails on a provider
  outage; OTP is never solely SMS-dependent (R29). Per-request timeouts bound thread pile-up.

One non-blocking note: `FcmHttpPushSender.resolveDeviceToken` is a documented `// TODO(wiring)` stub
returning `null` (no device-token registry yet) → always falls back to SMS. Correct and safe for MVP;
flagged only so the registry increment (resolve token from identity's public API + prune
`UNREGISTERED`/`INVALID`) is tracked. **No flag raised** on the adapter itself.

### 3c. DLQ replay — **PASS (FAILED-only, audited, PII-safe)**

`OutboxReplayService` is correct per ADR-0014 revisit-trigger (c):
- **FAILED-only, pinned in SQL** (`requeueFailedById` / `requeueFailedBatch` carry `WHERE status='FAILED'`),
  so a `PROCESSED` row is **never** reset (no re-fire of an already-delivered effect) and an in-flight
  `PENDING` row is never disturbed. Re-queue resets to a clean slate (`attempts=0`, `last_error=NULL`,
  `processed_at=NULL`, `next_attempt_at=now()`).
- **Idempotent** (replaying the same id/window twice moves 0 the second time) and **bounded** (explicit
  or default `purgeBatchSize` cap) so a mass replay can never trigger an unbounded relay surge. At-least-once
  + idempotent-handler contract still applies on replay (it only resets delivery state).
- **PII-safe:** logs by event id / event type / counts only — never the payload or `last_error` text.
- **Correctly defers the HTTP surface** — no controller in `common.outbox`; the admin/ops replay endpoint
  is a CENTRAL NEED for the admin module, with its own `@PreAuthorize("hasRole('ADMIN')")` + audit.
  `OutboxReplayServiceTest` GREEN.

---

## 4. Prioritized must-fix list

| # | Pri | Finding | Owner | Action |
|---|---|---|---|---|
| P1-1 | **P1 — blocks gate** | `AuthFlowIntegrationTest` RED (2/18): `passwordLogin_locksOutAfterRepeatedFailures` (line 232, `UNAUTHENTICATED` vs real backoff `RATE_LIMITED` after 3) and `refreshRotation_andReuseDetection_revokesFamily` (line 277, revoked-sibling rotate error-code drift). **Test-only** — production S-2/S-3 controls verified correct; no security regression. Exposed by the `0c51de8` limiter rework. | identity/auth eng | Align both assertions to the real `InMemoryAuthRateLimiter` thresholds (backoff-after-3, lock-after-10) and the actual post-family-revoke error code. Re-run the IT suite GREEN. |
| P2-1 | P2 | Open USSD webhook (`POST /ussd/gateway`, `permitAll`) has **no aggregator auth and no rate-limit**; combined with no-OTP T1 account creation + report filing it is an unauthenticated account-creation/spam surface. Currently closed only because it is not yet on the public allow-list. | security + kernel/SecurityConfig + ops | Register the path **with** aggregator shared-secret/HMAC/IP-allow-list **and** per-MSISDN/per-source rate-limit. Keep the USSD file path disabled in prod config until both land. |
| P3-1 | P3 | Admin/ops DLQ-replay HTTP endpoint not yet built (service-only). | admin eng | Add an `ADMIN`-gated, audited replay endpoint in the admin module (CENTRAL NEED). |
| P4-1 | P4 (track) | FCM device-token registry is a `// TODO(wiring)` stub (always SMS fallback). Safe for MVP. | communications + identity eng | Wire token resolution from identity's public API + prune on `UNREGISTERED`/`INVALID`. |

### Central integration needs (not editable under worktree isolation)
- Register `POST /ussd/gateway` on the public allow-list **with aggregator auth + rate-limit** (P2-1).
- Admin module: `ADMIN`-gated audited DLQ-replay endpoint over `OutboxReplayService` (P3-1).
- The bleed-in uncommitted edit to `HttpSmsGatewayTest.java` (a `TestableHttpSmsGateway` test-double
  refactor) is **not part of this wave** and was left untouched / uncommitted per isolation.

## Decision

**CONDITIONAL PASS.** Prod-boot gate, USSD ports, notification adapters, and DLQ replay all PASS with no
secrets, no PII leakage, fence-clean integrity, and an acyclic boundary. The launch gate stays **RED on
P1-1** (test green-ness) until the two `AuthFlowIntegrationTest` assertions are corrected — a test-only
fix; the underlying auth controls are sound. Resolve P1-1 to flip to full PASS; P2-1 is a hard
prerequisite before the USSD channel is exposed in production.
