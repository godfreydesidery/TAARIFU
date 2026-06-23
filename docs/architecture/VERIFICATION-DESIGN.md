# Taarifu Backend — Profile & Identity Verification (Tier-Progression T1→T2→T3) Design

> **Status:** Accepted (profile & verification increment). **Owner:** Solution Architect (David Okello).
> **Scope:** the profile-completion + identity-verification increment of the modular monolith (`/backend`). **DESIGN ONLY** — this is the contract the backend, security and database engineers build to. No application code is delivered with this document.
> **Builds on (already merged on `develop`):** the auth increment — `com.taarifu.common` shared kernel (`ResponseFactory`, `ApiError`/`ErrorCode`, `AuditEventService`/`AuditEvent`/`AuditEventType`, `CryptoPort`, `JwtService`/`JwtProperties`, `TierResolver`/`RequiresTierAspect`, `ScopeGuard`, `AuthRateLimiter`, `CurrentUser`); the `identity` slice (`User`, `Profile`, `ProfileLocation`, `RoleAssignment`, `Role`, `VerificationRequest`, `OtpChallenge`, `RefreshToken`; `SignupService`, `LoginService`, `OtpService`, `TokenService`, `TierService`, `ScopeGuardImpl`, `ProfileService`/`ProfileController`, `AuthController`); the `geography` read slice (`GeographyQueryService.resolveWardPin`, `LocationResolutionService`, effective-dated `WardConstituency`); the `communications` `SmsGateway` port + dev stub; the `IdentityVerificationProvider` port.
> **Grounding:** PRD §6.4 (D11–D16), §7 (RBAC + tiers T0–T3), §9.0 (multi-location, primary vs electoral, ward→constituency), §18 (security/verification), §24 (responder scope), §25.1/§25.4 (`isElectoral` edge cases) / §25.5 (downgrade); D-Q1, D-Q2, D13, D16. AUTH-DESIGN.md §6/§7/§9; ADR-0011; ADR-0012 (this increment's decision of record); CLAUDE.md §3/§8/§12.
> **Closes the auth-review follow-up:** `docs/reviews/auth-increment-review.md` — **N-4 (staff TOTP MUST land before any staff-role login path is exposed)**. This increment exposes the **Moderator verification queue** (the first scoped staff endpoint), so **N-4 is in scope and blocking here.**

---

## 0. One-paragraph summary

This increment delivers the **citizen path from T1 to T3** and the **first staff endpoint** behind it. A T1 citizen completes their **profile** (names + demographics) and verifies a contact channel by **OTP** (reusing the existing `OtpService` with `channel=EMAIL` for email and `channel=SMS` for phone) to reach **T2**. A T2 citizen submits a **government ID** (NIDA or voter) for verification: the `idNo` is **field-encrypted** and a **blind-index dedup** (D15) rejects a second account on the same ID; a `VerificationRequest(type=ID, status=PENDING)` is created behind the **`IdentityVerificationProvider` port**, whose **operator-assisted adapter** (D-Q2, the MVP/degradation path) routes it to a **Moderator queue**. A **Moderator** — the **first scoped staff endpoint**, gated on `ROLE_MODERATOR` + `ScopeGuard` (area scope) + `isNotSelf` (D16) **and only reachable after a TOTP second factor (N-4)** — approves or rejects. On approve, `Profile.idVerified=true` makes the **live tier T3** (the existing `TierService` already computes this — no tier is ever client-asserted, MF-2); if `idType=VOTER`, the resolved **ward→constituency** is set as the **authoritative `isElectoral`** location (D13). Citizens manage their **locations** (add/remove, set the single `isPrimary`, set the single `isElectoral` with a **6-month cooldown** for manual changes, voter-ID bypasses cooldown). **Staff TOTP** is wired into the login path: any account holding `MODERATOR`/`ADMIN`/`ROOT` cannot complete login without the TOTP step. Every transition — verification submitted/approved/rejected, tier change, electoral change, staff login-with-MFA — is written to the **append-only `audit_event`** store (references/hashes, no raw PII). The only new persisted state is **two nullable encrypted columns on `app_user`** (`mfa_totp_secret`, `mfa_pending_secret`); **every other entity already exists**.

---

## 1. Scope, non-goals & the decisions this increment honours

### 1.1 In scope
- **Profile completion → T2:** required fields (`firstName`, `lastName`) + ≥1 `ProfileLocation` + a verified contact channel via **OTP** (`channel=EMAIL` reusing `OtpService`; phone already verified at signup). (PRD §7.3; AUTH-DESIGN §6.)
- **ID/voter verification → T3 (citizen-facing submit):** encrypt `idNo`, compute + check the **blind-index dedup** (D15), create `VerificationRequest(ID, PENDING)` through the `IdentityVerificationProvider` **operator-assisted adapter** (D-Q2).
- **Operator (Moderator) verification queue + approve/reject** — the **first scoped staff endpoint**: `ROLE_MODERATOR` + `ScopeGuard` (area scope of the subject's electoral/primary ward) + `isNotSelf` (D16). On approve → `Profile.idVerified=true` → live T3; voter-ID → authoritative `isElectoral` (D13).
- **`ProfileLocation` management:** add/remove a location (resolve pin → admin chain + constituency via geography), set the single `isPrimary`, set the single `isElectoral` (voter-ID-authoritative; manual change cooldown-guarded, D13).
- **Staff TOTP enforcement (N-4):** require the TOTP second factor in the login path for any account holding `MODERATOR`/`ADMIN`/`ROOT`; enrolment (`setup`/`activate`) wired to `User.mfaEnabled` + the new encrypted TOTP-secret columns.
- **The audit events** for verification submitted/approved/rejected, tier change, electoral change, and staff-login-with-MFA — adding the **missing event types** to the `AuditEventType` catalogue.

### 1.2 Non-goals (explicitly deferred / seam-only here)
- **NIDA / voter-ID *API* integration** — operator-assisted at launch (D-Q2). The `IdentityVerificationProvider` port gets its **operator-assisted adapter**; the **auto/NIDA stub adapter** is selectable by config and returns `PENDING_REVIEW` (falls through to the same queue). The real registry API is a later integration increment (EI-1/EI-2).
- **Object-store upload + malware scan of the ID evidence** — the `ObjectStore`/`MalwareScanner` ports are a separate integration increment (EI-8). This increment accepts and stores an **`evidenceRef`** (object-store key produced by the upload endpoint that lands with EI-8); the queue and decision flow are complete around the ref.
- **`REP_CLAIM` / `ORG` verification** — the same queue/port supports them; this increment wires **`type=ID`** end-to-end only. The Moderator endpoint is typed so rep/org decisions slot in without a new surface.
- **Email delivery adapter** — OTP `channel=EMAIL` goes through the **same `SmsGateway`-style seam**; the real `EmailSender` adapter is the `communications` increment. The dev stub delivers email-channel OTP identically to SMS (redacted log, test-only retrieval). See §2.4.
- **Fraud-driven revocation recompute (§25.5)** — the *default* downgrade path (id_verified revoked → live tier drops to T2, prior actions stand) is inherent in the live `TierService` and needs no code here. Fraud-driven aggregate recompute is owned by `engagement`/`accountability`.
- **Redis-backed cooldown / rate-limit durability** — the electoral-change cooldown is enforced from the **durable `electoral_changed_at` column** (not Redis), so it survives restart by design. The Redis `AuthRateLimiter` swap remains the launch-gate item from the auth review (N-6).

### 1.3 Locked decisions this increment must not violate
- **D11/D15 — one account per person:** ID dedup via the **blind index** at submit; a duplicate `idHash` throws `DUPLICATE_IDENTITY` (409) and **never** creates a second profile/account.
- **D13 — electoral integrity:** exactly one `isElectoral` per profile; **voter-ID-authoritative**; manual change **cooldown-guarded** (default 6 months, config) and audited; voter-ID verification **bypasses** the cooldown. Re-delimitation re-resolution rides the effective-dated `WardConstituency` (§25.4) — historical actions stay attributed to the constituency in effect when taken.
- **D16 — conflict of interest:** a Moderator **cannot approve their own** verification (`isNotSelf`); all multi-hat actions audited.
- **D-Q2 — pluggable verification, operator-assisted at launch:** the `IdentityVerificationProvider` port has **two implementations** (operator-assisted now, auto/NIDA stub) selected by config; **no launch dependency on NIDA**; the Moderator queue is the MVP.
- **MF-2 — live tier, never the claim:** T3 is reached only because `TierService.resolveLiveTier` reads `profile.idVerified` live; no endpoint sets or trusts a tier from the client.
- **N-4 (auth review) — staff TOTP before any staff login path:** this increment exposes a staff endpoint, so staff TOTP is enforced **in the same PR**.
- **PDPA / §18 — privacy by default:** `idNo` field-encrypted, never logged; evidence is an object-store **ref**; audit holds **references/hashes only**; `ProfileLocation` stays private PII (no public DTO).

---

## 2. Components added (each carries **mandatory Javadoc** — responsibility, params/returns, throws, security/PDPA "why")

| Component | Package | Responsibility |
|---|---|---|
| `VerificationService` | `identity.application.service` | Citizen-facing ID submit: validate T2, encrypt `idNo`, compute + check blind-index dedup (D15), call `IdentityVerificationProvider`, create `VerificationRequest(ID,PENDING)`, audit `AUTH_VERIFICATION_REQUESTED`. |
| `VerificationReviewService` | `identity.application.service` | Operator (Moderator) decisions: list the scoped queue; **approve** → `Profile.idVerified=true` + (voter-ID) authoritative `isElectoral`; **reject** → reason-coded, tier untouched. Recomputes + caches the live tier; audits `*_APPROVED`/`*_REJECTED`, `AUTH_TIER_CHANGED`, `ELECTORAL_CHANGED`. |
| `OperatorAssistedVerificationAdapter` | `identity.infrastructure.adapter` | The **D-Q2 MVP** `IdentityVerificationProvider` impl: returns `PENDING_REVIEW` (routes to the queue), never auto-decides. Active by config `taarifu.identity.verification.provider=operator-assisted` (default). |
| `StubAutoVerificationAdapter` | `identity.infrastructure.adapter` | The auto/NIDA **stub** impl (config `=auto-stub`): deterministic match/reject for dev/E2E; **no external call**, never logs `idNo`. The seam for the future real NIDA adapter. |
| `LocationService` | `identity.application.service` | `ProfileLocation` lifecycle: add/remove, set single `isPrimary`, set single `isElectoral` (manual = cooldown-guarded; authoritative = bypass). Resolves pins via `GeographyQueryService`. Extracts the location logic out of `ProfileService` (SRP). |
| `TotpService` | `identity.application.service` | TOTP secret provisioning + verification for staff MFA. Stores the secret **encrypted** (`CryptoPort`); verifies a 6-digit TOTP with a ±1 step window; sets `User.mfaEnabled` on activate. |
| `MfaLoginGate` | `identity.application.service` | The N-4 staff second-factor step: decides whether a login must complete a TOTP step (account holds a staff role **or** `mfaEnabled`), issues/validates the short-lived `MFA_CHALLENGE` token, and only then lets `LoginService` issue the real pair. |
| `VerificationController` | `identity.api.controller` | Citizen ID-submit endpoint + own-verification status read. |
| `VerificationReviewController` | `identity.api.controller` | **First scoped staff surface:** the Moderator queue + approve/reject (TOTP-gated session, `ROLE_MODERATOR`, `ScopeGuard`, `isNotSelf`). |
| `LocationController` | `identity.api.controller` | `ProfileLocation` add/remove + set-primary/set-electoral (replaces the single `POST /profiles/me/locations` on `ProfileController`). |
| `MfaController` | `identity.api.controller` | TOTP `setup`/`activate` (authenticated) + the `login/totp` step (carries the `MFA_CHALLENGE` token). |

> **Module-boundary rationale:** all new behaviour lives in `identity` (it owns `Profile`/`VerificationRequest`/`ProfileLocation`/`User`). Pin resolution goes through `geography`'s **public API** (`GeographyQueryService`), never its repositories. The adapters sit in `identity.infrastructure.adapter` behind the existing `IdentityVerificationProvider` port (`identity.domain.port`). No vendor type leaks into domain code; no ward/constituency semantics leak into a provider format (mapping stays in the adapter). `TokenType` gains a `MFA_CHALLENGE` value in `common.security` (the only `common` change beyond the audit catalogue).

### 2.1 The `IdentityVerificationProvider` port (finalised for this increment)
The port already exists with a provisional `verify(idTypeName, idNumber, fullName) → {MATCHED, PENDING_REVIEW, REJECTED}`. This increment **keeps that signature** and pins its meaning:
- **operator-assisted adapter (default):** always returns `PENDING_REVIEW` → `VerificationService` creates the `PENDING` request and the Moderator decides. (D-Q2; degradation by default, DI2.)
- **auto-stub adapter:** returns `MATCHED`/`REJECTED` deterministically (dev/E2E); on `MATCHED`, `VerificationService` may auto-approve **without** a Moderator (still audited). The real NIDA adapter later returns `MATCHED` on a registry hit and `PENDING_REVIEW` on outage (falls back to the queue — the citizen never loses an earned tier while pending, EI-1/EI-2).
- **degradation:** any provider exception is caught in the adapter and surfaced as `PENDING_REVIEW` (queue), never a hard fail of the citizen submit (DI2, PRD §21).

### 2.2 Blind-index dedup at submit (D15)
`Profile.idHash` is a **unique blind index** over `idType + ":" + idNo` (`CryptoPort.blindIndex`). At submit, `VerificationService`:
1. computes `idHash = crypto.blindIndex(idType.name() + ":" + normalise(idNo))`;
2. checks `profileRepository.existsByIdHashAndUserNot(idHash, me)` (new repo method) — a hit on **another** profile → `DUPLICATE_IDENTITY` (409), audited as `AUTH_VERIFICATION_REQUESTED / DENIED / DUPLICATE_IDENTITY`, **no** request created;
3. on no conflict, sets `profile.idType`, `profile.idNo` (encrypted converter), `profile.idHash`; the DB **unique index `ux_profile_id_hash`** is the hard backstop against a race (concurrent submits → the second hits a `DataIntegrityViolation` → mapped to `DUPLICATE_IDENTITY`). The plaintext `idNo` is never logged; only the hash is referenced in audit.

### 2.3 TOTP (staff MFA) details
- **Algorithm:** RFC 6238 TOTP, SHA-1, 6 digits, 30-second step, ±1 step verification window (clock-skew tolerance). A small, audited library or a ~40-line in-house HMAC implementation — no new heavyweight dependency.
- **Secret at rest:** the Base32 secret is stored **encrypted** via the existing `EncryptedStringConverter` (envelope, `CryptoPort`), `@ToString`-excluded, never logged (S-4). Two columns (§7): `mfa_pending_secret` (set at `setup`, before activation) and `mfa_totp_secret` (promoted on `activate`); separating them means an un-activated secret can never satisfy a login.
- **Provisioning:** `setup` returns the `otpauth://totp/Taarifu:{publicId}?secret=…&issuer=Taarifu` URI **once** (and the raw secret once, for manual entry); `activate {totp}` verifies a code against `mfa_pending_secret`, promotes it to `mfa_totp_secret`, sets `mfa_enabled=true`, audits `AUTH_MFA_ENROLLED`. (Review V-3: the account label is the account's `publicId` (UUID), never the MSISDN — the code uses `user.getPublicId()` — so the phone never lands in an authenticator app or a screenshot.)

### 2.4 OTP `channel=EMAIL` (T2 contact verification)
The existing `OtpService.issueSms` is generalised: a sibling `issueEmail(email, purpose=VERIFY, user)` builds an email-channel `OtpChallenge(channel=EMAIL)` and delivers through the **same delivery seam** (the dev `SmsGateway` stub stands in for the email channel until the `EmailSender` adapter lands — see §1.2). On verify of a `VERIFY`-purpose email challenge, `Profile.markEmailVerified()` is called and the live tier recomputed. This reuses **all** existing OTP machinery — hashing, single-use, attempt cap (N-1 fix), rate limit, anti-enumeration — with **zero** new OTP logic.

---

## 3. Flow 1 — Profile completion → T2

**Goal:** T1 → **T2** (PRD §7.3; US-0.x): names present, ≥1 `ProfileLocation`, and a verified contact channel. T2 unlocks official reports, moderated comments, Q&A, survey responses.

```
1. PATCH /api/v1/profiles/me { firstName, lastName, dateOfBirth?, gender?, nationality? }
      → @RequiresTier("T1"); ProfileService.updateProfile (already built)
      → recompute live tier (TierService); audit AUTH_TIER_CHANGED if it moved

2. POST  /api/v1/profiles/me/locations { wardPublicId, associationType, isPrimary }
      → @RequiresTier("T1"); LocationService.addLocation
      → GeographyQueryService.resolveWardPin(ward) → admin chain + effective constituency
      → if isPrimary: clear existing primary first (single-primary, D12)
      → recompute live tier

3. POST  /api/v1/auth/otp/request/email { email }    (or reuse the channel param)
      → OtpService.issueEmail(email, VERIFY, user)  → 202 {challengeId}
   POST  /api/v1/auth/otp/verify/email { challengeId, code }
      → OtpService.verify(challengeId, code, VERIFY) → Profile.markEmailVerified()
      → recompute live tier  → promotes to T2 when the T2 predicate holds
      → audit AUTH_OTP_VERIFIED, AUTH_TIER_CHANGED(T1→T2)
```

- **T2 predicate** (unchanged, owned by `TierService`): `T1 ∧ firstName ∧ lastName ∧ ≥1 ProfileLocation ∧ (emailVerified ∨ phoneVerified)`. Phone is already verified at signup, so a citizen can reach T2 with name + location even before email; email-verify is the path for accounts that want the email channel. The client **cannot** self-assert T2 (MF-2).
- **Cost/connectivity:** email OTP is the **cheaper** channel (AUTH-DESIGN §15); SMS stays the universal fallback. Payloads are tiny; nothing here costs an SMS unless the citizen chooses the phone channel.

---

## 4. Flow 2 — ID / voter verification submit → `VerificationRequest(PENDING)`

**Goal:** a **T2** citizen submits a government ID to start the path to **T3** (PRD §7.3, D-Q2, D15).

```
POST /api/v1/profiles/me/verification { idType (NATIONAL|VOTER|PASSPORT), idNo, fullName, evidenceRef }
  → @RequiresTier("T2")                              // must be T2 to submit (MF-2 live)
  → VerificationService.submitIdVerification(me, idType, idNo, fullName, evidenceRef):
       1. load me + profile; require live tier ≥ T2
       2. idHash = crypto.blindIndex(idType + ":" + normalise(idNo))         // D15
       3. if profileRepository.existsByIdHashAndUserNot(idHash, me)
              → audit AUTH_VERIFICATION_REQUESTED / DENIED / DUPLICATE_IDENTITY
              → throw DUPLICATE_IDENTITY (409)                                // never a 2nd account
       4. profile.setIdentity(idType, idNo /*encrypted*/, idHash)            // idNo never logged
       5. outcome = identityVerificationProvider.verify(idType, idNo, fullName)  // operator-assisted → PENDING_REVIEW
       6. create VerificationRequest(subject=me, type=ID, status=PENDING, evidenceRef)
       7. audit AUTH_VERIFICATION_REQUESTED (subject=me, detailRef="idHash:"+idHash, ref to evidence; NO idNo)
  → 202 { verificationPublicId, status: PENDING }
```

- **Tier is NOT changed here** — `idVerified` stays false; submitting is not being verified. A pending review never grants or loses tier (PRD §21, §25.5).
- **`fullName`** is the claimed name to match against the ID; it is **not** persisted as PII beyond the existing profile name and is **not** logged.
- **Idempotency:** `Idempotency-Key` on submit so a flaky-2G retry does not create two pending requests; a second submit while one is `PENDING` for the same subject+type returns the existing request (no duplicate queue entries).
- **`evidenceRef`** is an object-store key from the (EI-8) upload endpoint — never document bytes (PRD §18). If EI-8 is not yet wired, `evidenceRef` is optional and the operator works from an out-of-band channel; the flow is unchanged.

---

## 5. Flow 3 — Operator (Moderator) verification queue + approve/reject — **the first scoped staff endpoint**

**Goal:** a **Moderator** decides a `PENDING` ID request; approval lifts the citizen to **T3** (live) and, for voter-ID, sets the authoritative `isElectoral` (D13). This is the first endpoint that combines **staff role + scope + conflict-of-interest + TOTP** — every guard in the auth increment is exercised here for the first time.

```
GET  /api/v1/moderation/verifications?status=PENDING
  → @PreAuthorize("hasRole('MODERATOR') and @mfa.isStaffMfaSatisfied()")     // role + N-4 TOTP-session
  → VerificationReviewService.listQueue(scope):
       returns PENDING requests whose subject's electoral/primary ward is within the
       caller's ScopeGuard area scope (deny-by-default; empty area set = unrestricted-within-role)
  → 200 { items:[{verificationPublicId, subjectPublicId, idType, submittedAt, evidenceRef}], meta }

POST /api/v1/moderation/verifications/{publicId}/approve { note? }
  → @PreAuthorize("hasRole('MODERATOR') and @mfa.isStaffMfaSatisfied()
                   and @taarifuAuthz.canActOnArea(@verificationScope.wardOf(#publicId))
                   and @taarifuAuthz.isNotSelf(@verificationScope.subjectOf(#publicId))")   // D16
  → VerificationReviewService.approve(reviewer, publicId, note):
       1. load request; require status==PENDING and type==ID
       2. request.approve(reviewerPublicId, note, now)                       // status=APPROVED, decided_at
       3. profile.markIdVerified()  (idVerified=true, verifiedAt=now)
       4. if profile.idType == VOTER:
              resolve the registered ward→constituency (from the voter-ID result / evidence),
              ensure a ProfileLocation for that ward (auto-create electoral-only if absent — §25.4),
              setElectoralAuthoritative(thatLocation)   // bypasses cooldown, demotes any prior electoral
              audit ELECTORAL_CHANGED (reason="VOTER_ID_AUTHORITATIVE")
       5. liveTier = TierService.resolveLiveTier(profile)  → T3; cache on user
       6. audit AUTH_VERIFICATION_APPROVED (actor=reviewer, subject=citizen, roles=reviewer roles)
          audit AUTH_TIER_CHANGED (T2→T3)
  → 200 { status: APPROVED, subjectTier: T3 }

POST /api/v1/moderation/verifications/{publicId}/reject { reasonCode, note }
  → same guards
  → VerificationReviewService.reject(reviewer, publicId, reasonCode, note):
       request.reject(reviewerPublicId, reasonCode, note, now)   // status=REJECTED; idVerified stays false
       audit AUTH_VERIFICATION_REJECTED (reason=reasonCode)      // tier unchanged
  → 200 { status: REJECTED }
```

- **Why the live `TierService` and not a manual `setTrustTier(T3)`:** the existing resolver already returns T3 the instant `idVerified` flips true (it's the T3 predicate). The service **recomputes and caches** for the token hint, but gating anywhere re-resolves live — so a forged/elevated claim is still ignored (MF-2 keystone, already tested).
- **`isNotSelf` (D16):** a Moderator who is also a citizen cannot approve **their own** ID request; `@taarifuAuthz.isNotSelf(subjectOf(publicId))` blocks it (`CONFLICT_OF_INTEREST`, audited `AUTHZ_SELF_ACTION_BLOCKED`).
- **Scope (`ScopeGuard`):** the Moderator's area scope (`RoleAssignment.areaIds`, resolved against the subject's electoral/primary ward) limits which queue items they can see/act on — the first real use of `canActOnArea`. `@verificationScope` is a tiny helper bean resolving the subject's ward + subject publicId from a request publicId (kept out of `@PreAuthorize`-unfriendly inline logic). Empty area set = unrestricted-within-role (the documented permissive case).
- **N-2 honoured:** the role-claim and scope come from `findActiveEffectiveByUser` (effective-window-aware), so a lapsed Moderator mandate cannot act.
- **Degradation:** the Moderator path is the **degradation mode itself** (D-Q2) — there is no upstream provider to fail. The `evidenceRef` read goes through the object-store port; if it is down, the operator can still reject/defer (the decision is not blocked on evidence retrieval; it just can't approve blind — operational guidance, not a code path).

---

## 6. Flow 4 — `ProfileLocation` management (add / remove / set-primary / set-electoral)

**Goal:** US-0.8 / UC-A25-27 — a citizen manages multiple locations with exactly one primary and one electoral (PRD §9.0, D13). All `ProfileLocation` rows are **private PII** — no public DTO, never logged.

```
POST   /api/v1/profiles/me/locations { wardPublicId, associationType, isPrimary }   @RequiresTier("T1")
   → resolve pin (admin chain + constituency); if isPrimary, clear prior primary first
DELETE /api/v1/profiles/me/locations/{publicId}                                       @RequiresTier("T1")
   → soft-delete; REJECT if it is the only location of a T2+ user (would break the ≥1-pin T2 predicate)
   → REJECT (CONFLICT) if it is the current isElectoral and was set authoritatively (must re-verify to move)
PATCH  /api/v1/profiles/me/locations/{publicId}/primary                               @RequiresTier("T1")
   → demote existing primary, promote this one (single-primary, D12; DB partial-unique index backstop)
PATCH  /api/v1/profiles/me/locations/{publicId}/electoral                             @RequiresTier("T2") + cooldown
   → MANUAL electoral change: enforce the cooldown (D13) from electoral_changed_at
   → demote existing electoral, promote this one, stamp electoral_changed_at = now
   → audit ELECTORAL_CHANGED (reason="MANUAL")
```

**Set-electoral rules (D13 / §25.4) — every branch specified:**
- **Manual change** is allowed only for a citizen whose electoral was **not** set authoritatively by a voter ID, and only after the **cooldown** (default **6 months**, config `taarifu.identity.electoral.cooldown-days=183`) measured from `electoral_changed_at`. A change inside the window → `RATE_LIMITED`/`CONFLICT` with `Retry-After`/`retryAfter` in `data`, audited as a denied `ELECTORAL_CHANGED`.
- **Voter-ID authoritative** set (from Flow 3) **bypasses** the cooldown and **overrides** any manual electoral; the citizen is notified (out of scope here — a notification event), residence stays `isPrimary` (§25.4 conflict branch: voter-ID wins for electoral).
- **No voter-ID T3 (NIDA path):** the citizen may keep their `isPrimary` ward as `isElectoral` (citizen-confirmed, cooldown-guarded) until a voter ID overrides it (§25.4 first branch). This is just a manual set with the cooldown.
- **Single-electoral invariant** is DB-enforced (`ux_profile_location_one_electoral` partial unique index) — the service demotes the old one in the same transaction; the index is the hard backstop against a race.
- **Re-delimitation** (§25.4): not a user action — handled by the effective-dated `WardConstituency` re-resolution; historical binding actions stay attributed to the constituency in effect when taken. No code in this increment beyond reading the effective mapping at pin/approve time (already in `GeographyQueryService`).

---

## 7. Flow 5 — Staff TOTP enforcement (N-4) — required before this staff endpoint can authenticate/act

**Goal (N-4, blocking):** no account holding `MODERATOR`/`ADMIN`/`ROOT` may authenticate or act without a TOTP second factor. The Moderator verification endpoint (Flow 5) is a staff surface, so this **lands in the same increment**.

### 7.1 Login path change (two-step for staff)
```
POST /api/v1/auth/login/password { accountKey, password }       (or login/otp)
  → LoginService verifies the credential as today
  → MfaLoginGate.requiresSecondFactor(user)?
        true  if user.mfaEnabled  OR  user holds an ACTIVE+effective staff RoleAssignment
                                       (MODERATOR / ADMIN / ROOT)
  → if required:
        issue a short-lived MFA_CHALLENGE token (tokenType=MFA_CHALLENGE, ~5 min, no roles/tier, sub=publicId)
        return 200 { mfaRequired: true, mfaToken }               // NOT an access token
        audit AUTH_LOGIN_SUCCEEDED is NOT yet written (login incomplete)
  → else: issue the real token pair as today

POST /api/v1/auth/login/totp { mfaToken, totp }
  → JwtService.verify(mfaToken, MFA_CHALLENGE)                   // new TokenType, validated like the rest
  → TotpService.verify(user, totp)                               // ±1 step window
  → on success: TokenService.issuePair(user); audit AUTH_LOGIN_MFA  (success, factor=TOTP)
  → on failure: audit AUTH_MFA_CHALLENGE_FAILED; rate-limited via AuthRateLimiter (S-2)
```

### 7.2 Hard policy (deny staff without active MFA)
- A staff account that has **not** enrolled TOTP (`mfaEnabled=false`) **cannot complete login as staff**: `MfaLoginGate` blocks issuance of a token bearing staff authority and returns a typed error directing the account to enrol (`POST /auth/mfa/totp/setup` → `activate`). It may still hold a citizen session for citizen features (additive roles), but **the staff endpoint's `@PreAuthorize("… and @mfa.isStaffMfaSatisfied()")` fails** until MFA is active. So even if a staff role is granted to a live citizen session, the Moderator endpoint is **closed** until the account enrols + completes the TOTP step.
- `@mfa.isStaffMfaSatisfied()` is a method-security bean that returns true only when the current authenticated session was minted **after** a TOTP step for a staff account (carried as a verified token characteristic / re-checked against `mfaEnabled`), deny-by-default otherwise. This is the gate that makes N-4 binding on the new endpoint.
- **Why both the login gate and the endpoint gate:** defence in depth — the login gate prevents a staff token being minted without TOTP; the endpoint gate prevents a stale/citizen-only session from reaching the staff surface. Either alone would be a gap.

### 7.3 Enrolment endpoints
```
POST /api/v1/auth/mfa/totp/setup       @PreAuthorize("isAuthenticated()")   → { otpauthUri, secret(once) }   sets mfa_pending_secret
POST /api/v1/auth/mfa/totp/activate     @PreAuthorize("isAuthenticated()")  { totp } → mfa_enabled=true; promote pending→active; audit AUTH_MFA_ENROLLED
```

---

## 8. Audit events recorded (append-only `audit_event`; references/hashes only — L-1)

> New `AuditEventType` values added by this increment are **bold**. The rest already exist. Every event holds references/hashes only — never `idNo`, raw phone, OTP, or token (PRD §18).

| `event_type` | When | Notes |
|---|---|---|
| `AUTH_TIER_CHANGED` | T1→T2 (profile complete), T2→T3 (approve), T3→T2 (id_verified revoked) | from→to in `reason_code` (existing) |
| `AUTH_VERIFICATION_REQUESTED` | citizen submits ID (or duplicate denied) | `detailRef="idHash:…"`; evidence is a ref; never `idNo` (existing type) |
| **`AUTH_VERIFICATION_APPROVED`** | Moderator approves an ID request | actor=reviewer, subject=citizen, `actor_roles` (multi-hat audit, D16) |
| **`AUTH_VERIFICATION_REJECTED`** | Moderator rejects | `reason_code` = rejection reason |
| **`ELECTORAL_CHANGED`** | `isElectoral` set (manual or voter-ID-authoritative) | `reason_code` ∈ {`MANUAL`, `VOTER_ID_AUTHORITATIVE`, `REDELIMITATION`}; subject=citizen (D13) |
| **`AUTH_LOGIN_MFA`** | staff login completes the TOTP step | success; `reason_code="TOTP"` (N-4 evidence) |
| `AUTH_MFA_ENROLLED` | TOTP activated (`mfa_enabled=true`) | (catalogued in AUTH-DESIGN §11.2; **add the enum value** — it is referenced but not yet in the enum) |
| `AUTH_MFA_CHALLENGE_FAILED` | wrong TOTP at login/activate | anti-automation signal (**add the enum value**) |
| `AUTHZ_SELF_ACTION_BLOCKED` | Moderator tries to approve own request | D16 (existing) |
| `AUTHZ_SCOPE_DENIED` | Moderator acts outside area scope | MF-3 (existing) |
| `AUTHZ_TIER_DENIED` | submit attempted below T2 / etc. | MF-2 (existing) |

> **`AuditEventType` enum additions (this increment):** `AUTH_VERIFICATION_APPROVED`, `AUTH_VERIFICATION_REJECTED`, `ELECTORAL_CHANGED`, `AUTH_LOGIN_MFA`, `AUTH_MFA_ENROLLED`, `AUTH_MFA_CHALLENGE_FAILED`. (The last two are already referenced in AUTH-DESIGN §11.2 but were never added to the enum — this increment makes the catalogue and the code agree.) Codes are append-only; never repurpose a value.

---

## 9. Endpoint list with tier / role / scope

> All under `/api/v1`. One `ApiResponse` envelope via `ResponseFactory`; machine code at `data.code`. **Authn** = any valid access token; `@RequiresTier` enforced **live** (MF-2); `@PreAuthorize` by method security; `@taarifuAuthz` by `ScopeGuard`; `@mfa` by the staff-MFA gate (N-4).

### 9.1 Profile completion & contact verification (`ProfileController` / `MfaController`)
| Method | Path | Authz / tier | Notes |
|---|---|---|---|
| GET | `/profiles/me` | Authn (T1+) | own profile; never others' PII / `idNo` (existing) |
| GET | `/profiles/me/tier` | Authn (T1+) | live tier (UI hint, same resolver) (existing) |
| PATCH | `/profiles/me` | `@RequiresTier("T1")` | names/demographics → may promote T2 (existing) |
| POST | `/auth/otp/request/email` | Authn (T1+) | issue EMAIL VERIFY OTP (reuses `OtpService`) |
| POST | `/auth/otp/verify/email` | Authn (T1+) | verify → `emailVerified=true` → may promote T2 |

### 9.2 `ProfileLocation` management (`LocationController`) — private PII
| Method | Path | Authz / tier | Notes |
|---|---|---|---|
| POST | `/profiles/me/locations` | `@RequiresTier("T1")` | add pin; resolve admin chain + constituency |
| DELETE | `/profiles/me/locations/{publicId}` | `@RequiresTier("T1")` | soft-delete; guard last-location & authoritative-electoral |
| PATCH | `/profiles/me/locations/{publicId}/primary` | `@RequiresTier("T1")` | set single primary (D12) |
| PATCH | `/profiles/me/locations/{publicId}/electoral` | `@RequiresTier("T2")` + cooldown | set single electoral; manual = cooldown-guarded (D13) |

### 9.3 ID verification — citizen submit (`VerificationController`)
| Method | Path | Authz / tier | Notes |
|---|---|---|---|
| POST | `/profiles/me/verification` | `@RequiresTier("T2")` | submit ID/voter; dedup (D15); creates `PENDING`; idempotent |
| GET | `/profiles/me/verification` | Authn (T1+) | own verification status (never others') |

### 9.4 Operator (Moderator) verification queue — **first scoped staff endpoint** (`VerificationReviewController`)
| Method | Path | Authz / role / scope | Notes |
|---|---|---|---|
| GET | `/moderation/verifications` | `hasRole('MODERATOR')` + `@mfa.isStaffMfaSatisfied()` + scope-filtered | queue limited to caller's area scope (N-2 effective) |
| POST | `/moderation/verifications/{publicId}/approve` | `hasRole('MODERATOR')` + `@mfa` + `@taarifuAuthz.canActOnArea(…)` + `@taarifuAuthz.isNotSelf(subject)` | → `idVerified=true` → live T3; voter-ID → authoritative `isElectoral` (D13/D16) |
| POST | `/moderation/verifications/{publicId}/reject` | same | reason-coded; tier untouched |

### 9.5 Staff MFA / TOTP (`MfaController` / `AuthController`)
| Method | Path | Authz | Notes |
|---|---|---|---|
| POST | `/auth/mfa/totp/setup` | Authn (T1+) | provisioning URI + secret (once); sets `mfa_pending_secret` |
| POST | `/auth/mfa/totp/activate` | Authn (T1+) | `{totp}` → `mfa_enabled=true`; audit `AUTH_MFA_ENROLLED` |
| POST | `/auth/login/totp` | Public (carries `MFA_CHALLENGE` token) | staff second factor → real token pair (N-4) |

> `/auth/login/password` and `/auth/login/otp` (existing, public) now return `{mfaRequired:true, mfaToken}` for staff instead of a token pair. `SecurityConfig` adds `/api/v1/auth/login/totp` to the public POST allow-list (it carries the MFA challenge, not a prior access token).

---

## 10. Data additions

> Forward-only Flyway, `ddl-auto=validate` (ADR-0005). SQL comments mandatory (CLAUDE.md §8). **Most entities are unchanged.** New migration **`V6__identity_mfa.sql`**.

### 10.1 New columns — `app_user` (staff TOTP secrets) — the only schema change
| Column | Type | Notes |
|---|---|---|
| `mfa_totp_secret` | `varchar(512)` nullable | **encrypted** (envelope, `CryptoPort`) Base32 TOTP secret, active after `activate`; never plaintext/logged (S-4). |
| `mfa_pending_secret` | `varchar(512)` nullable | **encrypted** provisional secret set at `setup`, before activation; promoted to `mfa_totp_secret` on `activate`. Separation means an un-activated secret can never satisfy login. |

`User` gains: `setMfaPendingSecret`/`getMfaPendingSecret`, `setMfaTotpSecret`/`getMfaTotpSecret` (encrypted converter, `@ToString`-excluded), `enableMfa()` (promote pending→active + `mfaEnabled=true`). The existing `mfa_enabled` column is reused.

### 10.2 No new tables
- `VerificationRequest`, `Profile` (incl. `idVerified`, `idHash`, `idType`, encrypted `idNo`), `ProfileLocation` (incl. `is_primary`, `is_electoral`, `electoral_changed_at`), `RoleAssignment`, `OtpChallenge` (incl. `channel`) **all already exist** with every column this increment needs. No migration touches them.
- **Entity-method additions only** (no columns): `Profile.setIdentity(idType,idNo,idHash)`, `Profile.markIdVerified()`, `Profile.revokeIdVerified()` (for §25.5 downgrade); `ProfileLocation.markElectoral()/clearElectoral()/stampElectoralChange(now)`; `VerificationRequest.approve(...)`/`reject(...)`/factory `submit(subject,type,evidenceRef)`.
- **New repository methods** (no schema): `ProfileRepository.existsByIdHashAndUserNot(idHash, user)`; `VerificationRequestRepository.findByStatusAndType(status,type)` and a scoped queue query; `ProfileLocationRepository` already has `findByProfileAndElectoralTrue`/`findByProfileAndPrimaryTrue`.

### 10.3 `common.security.TokenType` — add `MFA_CHALLENGE`
A new enum value for the short-lived two-step login token (no roles/tier; ~5-min TTL). `JwtService` issues/verifies it through the existing `sign`/`verify` path (iss/aud/exp/type all validated) — no new signing code.

### 10.4 Config keys (externalised, tunable)
| Key | Default | Purpose |
|---|---|---|
| `taarifu.identity.verification.provider` | `operator-assisted` | selects the `IdentityVerificationProvider` adapter (D-Q2) |
| `taarifu.identity.electoral.cooldown-days` | `183` | manual `isElectoral` change cooldown (~6 months, D13/§25.4) |
| `taarifu.security.mfa.totp.step-seconds` | `30` | TOTP step |
| `taarifu.security.mfa.challenge.ttl-seconds` | `300` | `MFA_CHALLENGE` token TTL |

---

## 11. NFR budget, degradation & test bar

- **Latency (PRD §15):** profile/location writes p95 < 1s; verification submit p95 < 1s (one blind-index + one provider call + one insert); queue read p95 < 500ms (indexed by status). The Moderator approve path is one transaction (request + profile + optional location + tier cache).
- **Degradation (DI2, never hard-fail the citizen):** any `IdentityVerificationProvider` fault → `PENDING_REVIEW` (queue), submit still succeeds; object-store/evidence outage never blocks **rejecting/deferring**; email-OTP outage falls back to SMS-OTP (and a pending verify never loses an earned tier, PRD §21). Redis-down still **fails closed** on the auth surface (auth-increment posture); the electoral cooldown reads the **durable column**, so it is unaffected by a Redis flush.
- **Cost (PRD §15):** email-channel OTP preferred over SMS for the T2 contact-verify (cheapest); ID verification adds no per-request external cost at launch (operator-assisted).
- **Security/PDPA:** `idNo` field-encrypted + dedup-by-blind-index (never decrypted for dedup); TOTP secret encrypted; audit references/hashes only; `ProfileLocation` private; no PII in logs (S-4).
- **Test bar (CLAUDE.md §9/§10, ≥80% core):**
  - **Unit:** dedup rejects a duplicate `idHash` (D15); approve flips live tier to T3 (resolver, not a setter); voter-ID approve sets authoritative `isElectoral` and bypasses cooldown; manual electoral change inside cooldown is denied / outside is allowed; `isNotSelf` blocks self-approval (D16); `MfaLoginGate.requiresSecondFactor` true for staff/`mfaEnabled`; TOTP verify ±1 step window.
  - **Integration (Testcontainers Postgres):** full T1→T2 (profile+location+email-OTP) → submit → Moderator approve → live T3, with audit rows written and **no raw `idNo`/phone** in any audit column; **staff cannot reach `/moderation/verifications` without completing TOTP (N-4)**; concurrent duplicate-ID submits → exactly one succeeds (DB unique backstop); concurrent electoral set → single-electoral invariant holds.
  - **Contract:** OpenAPI for every §9 endpoint; envelope-shape with Angular/Flutter clients.
  - **Authz regression:** a forged `trustTier=T3` claim still cannot submit-as-T3 or approve (MF-2); a lapsed Moderator grant is denied (N-2); a Moderator outside the subject's area scope is denied (MF-3).

---

## 12. Review-backlog & decision traceability

| Item | Where addressed |
|---|---|
| **N-4** staff TOTP before any staff-role login path | §7, §9.4/§9.5, §10.1, ADR-0012 |
| **D-Q2** pluggable verification, operator-assisted at launch | §2.1, §4, §5, ADR-0012 |
| **D13 / §25.4** electoral integrity, voter-ID authoritative, cooldown | §5 (step 4), §6, §8, §10.4 |
| **D15** one-account-per-person ID dedup (blind index) | §2.2, §4 (step 2-3) |
| **D16** conflict-of-interest (no self-approval), multi-hat audit | §5, §8 |
| **MF-2** live tier, forged claim ignored | §3, §5, §11 tests |
| **MF-3 / N-2** scope + effective-window on the staff endpoint | §5, §9.4, §11 tests |
| **PDPA / §18** encrypted `idNo`, audit refs/hashes, private locations | §2.2, §2.3, §8, §11 |

## 13. Cross-references
- **Decision of record:** [ADR-0012-identity-verification-and-tier-progression.md](../adr/ADR-0012-identity-verification-and-tier-progression.md).
- **Builds on:** [AUTH-DESIGN.md](AUTH-DESIGN.md) §6/§7/§9; ADR-0011; `docs/reviews/auth-increment-review.md` (N-4).
- **Product truth:** PRD §6.4, §7, §9.0, §18, §24, §25.4, §25.5; D-Q1/D-Q2/D13/D15/D16. **Engineering rules:** CLAUDE.md §3/§8/§12.
