# PROFILE & VERIFICATION Increment Review — T1→T2→T3, ID/voter verification, first scoped staff endpoint, staff TOTP (N-4)

**Reviewer:** Salim Juma (security-privacy-engineer)
**Date:** 2026-06-23
**Branch:** `feature/profile-verification`
**Scope:** `backend/` profile & verification increment — `VerificationService`, `VerificationReviewService`, `VerificationScopeResolver`, `LocationService`, `TotpService`, `MfaLoginGate`, the verification/MFA controllers, the two `IdentityVerificationProvider` adapters, `TokenType.MFA_CHALLENGE` + `JwtService.issueMfaChallengeToken`, `LoginService` MFA branch, `User` TOTP columns, Flyway `V6`, and the new test suite (`VerificationFlowIntegrationTest`, `StaffMfaVerificationEndpointIntegrationTest`, `MfaLoginGateTest`).
**Prior backlog:** `docs/reviews/auth-increment-review.md` (N-1..N-7; N-4 is blocking here).
**Grounded in:** PRD §6.4 (D11–D16), §7.3 (tiers), §9.0 (multi-location, primary vs electoral), §18 (security/verification), §25.4 (`isElectoral` edge cases), §25.5 (downgrade), §21 (DI/EI degradation); VERIFICATION-DESIGN; ADR-0011/0012; CLAUDE.md §3/§8/§12.

## Verdict

**GO, with two must-fix-before-merge items (both P2, both small and localised).** This is again a strong, correctly-altituded increment. The headline regression item — **N-4 staff TOTP — is RESOLVED and proven end-to-end**: a staff account cannot mint a real token pair without completing TOTP (login gate) *and* cannot reach the staff surface without a live staff role + `mfaEnabled` (endpoint gate), with both halves independently tested against the real security stack on Postgres. D13 electoral integrity, D15 dedup, and D16 conflict-of-interest are all implemented and tested; `idNo` stays field-encrypted and never reaches a log/DTO/audit; evidence is an object-store ref. The Moderator gate uses live role+scope, not token claims.

The two blockers are **not** N-4 and **not** a foundation regression: (V-1) the manual-electoral-change cooldown can be bypassed by deleting-and-re-adding the electoral pin because `electoral_changed_at` lives per-row, not per-profile — a direct D13 double-influence hole; and (V-2) the TOTP second-factor step has no replay/used-once binding, so a captured `MFA_CHALLENGE`+code pair is replayable inside the step window. Fix both and this merges clean.

---

## Required-verification disposition

### (1) N-4 — staff TOTP enforced on any staff-role login — **RESOLVED**

A staff account **cannot authenticate without the second factor**, enforced in two independent places (defence in depth, VERIFICATION-DESIGN §7.2):

- **Login gate.** `LoginService.firstFactorPassed` calls `mfaLoginGate.requiresSecondFactor(user)` for **both** password and OTP login (`LoginService.java:137,160,237`). `requiresSecondFactor` returns true if `mfaEnabled` **or** the account holds an active+effective staff role (`MfaLoginGate.java:68-70,96-104`). On the staff branch it issues **only** a short-lived `MFA_CHALLENGE` token — never a token pair (`LoginService.java:250-251`). A staff account that never enrolled TOTP gets `MFA_REQUIRED` and **no token at all** (`LoginService.java:240-248`). The real pair is minted only by `completeTotpLogin`, which verifies the `MFA_CHALLENGE` token, rate-limits the code, and checks TOTP against the **active** secret (`LoginService.java:173-215`).
- **Endpoint gate.** `@PreAuthorize("hasRole('MODERATOR') and @mfa.isStaffMfaSatisfied()")` on every Moderator method (`VerificationReviewController.java:67,86-88,108-110`). `isStaffMfaSatisfied()` re-reads the account live and requires **`mfaEnabled` AND a live effective staff role** (`MfaLoginGate.java:80-93`) — so a forged/stale `hasRole('MODERATOR')` token claim with `mfaEnabled=false` is refused.
- **Token type isolation.** `MFA_CHALLENGE` carries no roles/tier, is verified for type on every use, and is accepted **only** at `/auth/login/totp` (`TokenType.java:19-25`, `JwtService.java:85-87,126-130`). It can never be presented as an access token.

**Tested:** `StaffMfaVerificationEndpointIntegrationTest` proves: un-enrolled staff login → `MFA_REQUIRED` (`:98-109`); enrolled staff → challenge then TOTP issues the pair (`:111-128`); an honest CITIZEN access token → 403 on the queue (`:130-138`); **a real MODERATOR-role token with `mfaEnabled=false` → 403** (`:140-150`) — this is the keystone N-4 proof that a staff authority alone does not open the staff surface. `MfaLoginGateTest` proves a staff-role holder requires the second factor **even before enrolment** (`:64-74`).

> Residual (not a blocker, track): the coarse `hasRole('MODERATOR')` term still trusts the token's role claim, but it is ANDed with the live `@mfa.isStaffMfaSatisfied()` (live role re-check) and `@taarifuAuthz` (live scope), so the token claim is never the *sole* gate — consistent with the auth-review MF-2/S-5 posture. When `ADMIN`/`ROOT` login surfaces land, the same `@mfa` gate must be applied; it already covers all three staff roles (`MfaLoginGate.java:42-43`).

### (2) D13 electoral integrity — **PARTIAL** (voter-ID authority + binding-scope correct; manual-change cooldown is bypassable → **V-1, P2**)

- Voter-ID sets `isElectoral` **authoritatively**: approve → `LocationService.setElectoralAuthoritative` resolves ward→constituency via geography, auto-creates an electoral-only pin if the constituency matches no existing location (PRD §25.4 branch 2), demotes any prior electoral, bypasses the cooldown, and audits `ELECTORAL_CHANGED(VOTER_ID_AUTHORITATIVE)` with subject=citizen (`VerificationReviewService.java:123-127`, `LocationService.java:199-220`). Authority is correctly derived (`idType==VOTER ∧ idVerified`), and a voter-ID-authoritative electoral is locked against manual move/removal (`LocationService.java:120-123,165-169,228-230`). **Tested:** `VerificationFlowIntegrationTest.voterIdApprove_setsAuthoritativeElectoral` asserts exactly one electoral and an audit row (`:176-196`).
- Binding scope is correct: exactly one `isElectoral` per profile (DB partial-unique backstop, `ProfileLocation.java:92-98`); the Moderator queue scopes on the subject's electoral-else-primary ward (`VerificationReviewService.java:209-221`).
- **Cooldown gap (V-1, P2):** the manual cooldown reads `electoral_changed_at` off the **currently-electoral row** (`LocationService.java:171-184`). A citizen can defeat it: `removeLocation` the current electoral (allowed while it is *not* voter-ID-authoritative), then `setElectoralManual` a different pin — the new target has `electoralChangedAt=null`, so the `changedAt != null` guard is skipped and the change goes through immediately. Net: unlimited manual electoral hopping → exactly the cross-location double-influence D13 exists to prevent. The cooldown must be anchored to the **profile**, not to a row that the user can delete. See fix below.

### (3) D16 — operator cannot approve their own verification — **RESOLVED**

`@taarifuAuthz.isNotSelf(@verificationScope.subjectOf(#publicId))` on both approve and reject (`VerificationReviewController.java:88,110`). `isNotSelf` is deny-by-default for a null subject and compares the live `CurrentUser` against the subject (`ScopeGuardImpl.java:100-109`); `subjectOf` returns null when the request is missing → denies (`VerificationScopeResolver.java:46-48`). **Tested:** `StaffMfaVerificationEndpointIntegrationTest.moderatorCannotApproveOwnRequest_d16` mints a staff token with `mfaEnabled=true` (so only D16 is left to stop it), asserts 403, and asserts the self-approval did **not** flip `id_verified` (`:152-181`). Multi-hat actions audit with `roles("MODERATOR")` and actor=reviewer (`VerificationReviewService.java:133-137,157-160`).

### (4) Verification PII — **RESOLVED**

- `idNo` is field-encrypted via `EncryptedStringConverter` (randomised GCM), never logged, never in a DTO (`Profile.java:82-88,196-200`; `SubmitVerificationDto.java:21-31`; `VerificationStatusDto` carries only ref+status). Grep confirms no `log.*`/`System.out`/`printStackTrace` and no `getIdNo()` use anywhere in `identity` outside the entity accessor.
- Dedup is via the deterministic **blind index** `idHash`, never decryption; unique DB index `ux_profile_id_hash` is the race backstop (`VerificationService.java:100-127`; `Profile.java:47-51,90-95`). Audit references only `idHash:` (`VerificationService.java:103-106,129-132`).
- Evidence is an object-store **ref**, never bytes (`VerificationRequest.java:57-61`; `SubmitVerificationDto` `evidenceRef`). Provider adapters never log `idNumber` (`OperatorAssistedVerificationAdapter.java:39-42`, `StubAutoVerificationAdapter.java:35-41`).
- TOTP secrets are encrypted on `User`, two-slot (pending vs active), never logged (`User.java:84-100,171-179`; `V6` columns encrypted/nullable). The raw secret is surfaced once at `setup` and is **not** audited.
- **Tested:** `VerificationFlowIntegrationTest.assertNoRawTextInAudit("19900101-…")` asserts no audit text column contains the raw idNo (`:140-141,234-241`).

> See V-3 (P3) below: the `otpauth://` URI embeds the raw secret in a path that includes `user.getPublicId()`; ensure the MfaController response is not access-logged with bodies. Low risk (it is authenticated and one-time), tracked.

### (5) Scoped Moderator endpoint uses live role+scope, never token claims — **RESOLVED**

`@taarifuAuthz.canActOnArea(...)` reads the caller's **active and currently-effective** `RoleAssignment` scope from the DB at `clock.now()` (`ScopeGuardImpl.java:50-64,120-125` → `RoleAssignmentRepository.findActiveEffectiveByUser`, the N-2 window-aware query, `:66-74`). `@mfa.isStaffMfaSatisfied()` re-derives the staff role live (`MfaLoginGate.java:88-104`). Neither consults the JWT `roles`/`trustTier` claim. The queue is additionally service-side filtered to in-scope wards, deny-by-default (`VerificationReviewService.java:90-96,202-207`). N-2 is honoured here: the same effective-window predicate governs the scope guard, the MFA role check, and the access-token role claim (`TokenService.java:204-211`) — one DRY query.

### (6) Audit completeness for verification/tier/electoral events — **RESOLVED**

Every transition is audited with references only: `AUTH_VERIFICATION_REQUESTED` (success + `DUPLICATE_IDENTITY`/`_RACE` denied), `AUTH_VERIFICATION_APPROVED`/`_REJECTED` (actor=reviewer, roles=MODERATOR), `AUTH_TIER_CHANGED` (`before->after`), `ELECTORAL_CHANGED` (`MANUAL`/`VOTER_ID_AUTHORITATIVE`; denied `COOLDOWN`/`AUTHORITATIVE_LOCKED`), `AUTH_MFA_ENROLLED`, `AUTH_LOGIN_MFA`, `AUTH_MFA_CHALLENGE_FAILED`. `V6` widens `ck_audit_event_type` append-only to admit the six new codes (`V6:96-109`) — correctly flagged as *not* caught by `ddl-auto=validate`, so it must ship here (it does). **Tested** via `auditCount(...)` assertions in `VerificationFlowIntegrationTest`.

> Minor (V-5, P4): on approve, `AUTH_TIER_CHANGED` is attributed actor=subject (the citizen) not the deciding reviewer (`VerificationReviewService.java:174-177`), and the voter-ID `ELECTORAL_CHANGED` is actor=subject. Forensically the *cause* was a Moderator decision; the `AUTH_VERIFICATION_APPROVED` row carries the reviewer, so the chain is reconstructable, but a reviewer-attributed tier/electoral change would be cleaner. Not a blocker.

---

## New findings

> **P2** = fix before merge; **P3** = fix in this increment's close follow-up; **P4** = hardening/track.

**V-1 (P2) — Manual electoral cooldown (D13) is bypassable by remove-then-re-add; anchor the cooldown to the profile, not the row.**
`setElectoralManual` enforces the 6-month cooldown from the *current electoral row*'s `electoral_changed_at` (`LocationService.java:171-184`). Because a non-authoritative electoral pin can be soft-deleted (`removeLocation` only blocks the authoritative electoral and the last pin, `:120-127`), a citizen can: delete the current electoral, then set another pin electoral — the target's `electoralChangedAt` is null, the cooldown branch is skipped, and the move succeeds with no wait. This reopens cross-location double-influence (rate two MPs / sign two constituency petitions over time) — the exact D13 risk. It also evades audit-of-intent (the deny path never fires).
- **Fix:** make the cooldown a **profile-level** invariant. Track the last electoral-change instant on the profile (a `Profile.electoralChangedAt` column, or `MAX(electoral_changed_at)` over the profile's locations including the just-cleared one), and check it before *any* manual electoral set or move. Also reject removing the current electoral if doing so would land inside the cooldown (or carry the timestamp forward). Add a Testcontainers test: set electoral → remove it → attempt to set a different pin electoral inside the window → expect `RATE_LIMITED` and an `ELECTORAL_CHANGED/DENIED/COOLDOWN` audit row. (The existing `manualElectoralChange_isCooldownGuarded` test only covers the move-without-delete path.)

**V-2 (P2) — The staff TOTP step has no used-once/replay binding on the `MFA_CHALLENGE`+code; a captured pair is replayable within the step window.**
`completeTotpLogin` verifies the `MFA_CHALLENGE` JWT (good: short TTL, type-bound) and the TOTP code, but neither the challenge token nor the consumed TOTP code is marked used — the JWT is bearer and reusable until `exp`, and `TotpService.verify` is a pure read with a ±1-step window (`LoginService.java:173-215`, `TotpService.java:117-121`). An attacker who observes one `mfaToken`+`totp` (e.g. a TLS-terminating proxy, a shared-device shoulder-surf, or a leaked client log) can replay it to mint **additional** independent token families until the ~5-min challenge expires and while the ~30–90s TOTP window holds — defeating the single-use intent of a second factor and producing extra refresh families the victim cannot see. For a staff/admin surface this is the highest-value session to protect.
- **Fix:** bind the challenge to a single redemption: persist a one-time `mfa_challenge` record (jti + subject + used flag) and reject reuse in the same write-locked pattern as refresh rotation (S-3), **or** at minimum track the last accepted TOTP step (`lastTotpStep` per user) and refuse a code whose step ≤ the last accepted step (standard TOTP replay defence). Audit the replay attempt as `AUTH_MFA_CHALLENGE_FAILED/DENIED/REPLAY`. Add a test that a second `completeTotpLogin` with the same `(mfaToken, totp)` fails.

**V-3 (P3) — `otpauth://` provisioning URI uses `user.getPublicId()` as the account label and returns the raw secret in a JSON body.**
`TotpService.setup` builds `otpauth://totp/Taarifu:{publicId}?secret={raw}&...` and returns both URI and raw secret in `TotpSetupDto` (`TotpService.java:76-78`, `MfaController.java:47-52`). The secret-in-response is correct (enrolment must show it once) and it is authenticated + one-time, but two things: (a) the raw secret travels in a response body — ensure no access-log/APM captures response bodies on `/auth/mfa/totp/setup`, and add it to the structured-log scrubbing allow-list when that lands (auth-review S-4 residual); (b) using `publicId` (a UUID) as the label is privacy-safe (good — not the phone, unlike VERIFICATION-DESIGN §2.3's example which says `{phone}`), so the **doc is the thing to correct** — §2.3 should be updated to say `{publicId}`, not `{phone}`, to keep the MSISDN out of authenticator apps/screenshots. Low risk; tidy the doc and confirm the body-logging posture.

**V-4 (P3) — Operator-supplied `registeredWardPublicId` on voter-ID approval is unvalidated against the subject and unaudited as a distinct value.**
On a voter-ID approval the Moderator passes `registeredWardPublicId`, which `setElectoralAuthoritative` will pin as the citizen's binding electoral home — overriding the cooldown and any citizen choice (`VerificationReviewService.java:114-127`). This is operator-assisted by design (D-Q2, the operator reads the card), but the value is a free input: it is not checked against the subject's claimed locations, and the `ELECTORAL_CHANGED` audit records only `VOTER_ID_AUTHORITATIVE`, not *which* ward was set or *who* set it (`LocationService.java:216-219`). A careless or malicious operator can plant a citizen's binding civic weight in the wrong constituency with no targeted audit trail — an election-period neutrality concern.
- **Fix:** include the resolved ward/constituency reference (non-PII public ids) in the `ELECTORAL_CHANGED` `detailRef`, and attribute the change to the **reviewer** (or add a companion audit row that does), so a wrong-ward set is attributable. Optionally surface a warning when the chosen ward is not among the subject's pinned locations. Pairs with V-5.

**V-5 (P4) — Tier/electoral changes triggered by a Moderator decision are attributed to the subject, not the actor.**
As noted in (6): `AUTH_TIER_CHANGED` and the authoritative `ELECTORAL_CHANGED` use actor=subject (`VerificationReviewService.java:174-177`, `LocationService.java:215-219`). The reviewer is recoverable via the sibling `AUTH_VERIFICATION_APPROVED` row, so this is forensic tidiness, not a hole. Prefer threading the reviewer's publicId into `setElectoralAuthoritative`/`recacheTier` for direct attribution. Track.

**V-6 (P4) — `reject()` recomputes and re-caches the subject's live tier on every rejection.**
`reject` calls `recacheTier` "to keep the cached hint consistent" (`VerificationReviewService.java:161-163`). A rejection never changes `idVerified`, so the live tier cannot move; the recompute is a no-op write risk (it can flip the cached `trustTier` only if it was already stale for an unrelated reason, silently emitting an `AUTH_TIER_CHANGED` inside a *rejection* — confusing in the trail). Harmless to security but unnecessary work and a slightly misleading audit edge. Consider dropping the recompute on the reject path (KISS). Track.

---

## Must-fix before merge

1. **V-1 (P2)** — Anchor the manual electoral-change cooldown to the **profile** so remove-then-re-add cannot bypass it; test the bypass path denies with a `COOLDOWN` audit row. (D13 double-influence.)
2. **V-2 (P2)** — Make the staff TOTP second factor single-use: bind/consume the `MFA_CHALLENGE` (jti used-once) and/or enforce TOTP step monotonicity (`lastTotpStep`); reject and audit replay; test a repeated `(mfaToken, totp)` fails. (Staff second-factor replay.)

## Strongly recommended in this increment (do not let slip)

3. **V-4 (P3)** — Record the resolved ward + the reviewer in the voter-ID `ELECTORAL_CHANGED` audit; consider warning on an off-profile ward. (Election-period attribution.)
4. **V-3 (P3)** — Confirm `/auth/mfa/totp/setup` response bodies are never access-logged; correct VERIFICATION-DESIGN §2.3 to use `{publicId}` not `{phone}` in the `otpauth` label.

## Track (not merge blockers)

5. **V-5 / V-6 (P4)** — Attribute Moderator-driven tier/electoral audits to the reviewer; drop the no-op tier recompute on reject.
6. **Carried launch-gate items (auth review):** Redis-backed `AuthRateLimiter` (N-6) — now also gates the new TOTP-step lockout, which is in-memory/single-instance today; RS256/ES256 signer swap (MF-1 residual) — **a precondition before any ADMIN/ROOT token is issued in a shared environment**, since the `MFA_CHALLENGE` and staff access tokens are HS256-symmetric and any verifier could forge them; SAST/DAST/SCA + container scan in CI.

---

**Bottom line:** The hard, regression-prone parts are right — N-4 staff TOTP is enforced and proven on both the login and endpoint sides, the live-role/live-scope/live-tier discipline holds, dedup and `idNo` encryption are intact, and D16 self-approval is blocked. The two blockers are a D13 cooldown that anchors to a deletable row (V-1) and a replayable second-factor step (V-2) — both narrow, both with a clear fix. Land V-1 and V-2, do V-3/V-4 in close follow-up, and this increment merges clean. None of the foundation regressions (wildcard CORS, logged secrets, missing method security, refresh-as-access, public actuator, PII in logs) recur on this code.
