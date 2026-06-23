# Security review — cross-module wiring increment (`feature/backend-wiring`)

> **Reviewer:** Salim Juma (Security & Privacy Engineer)
> **Date:** 2026-06-23
> **Branch:** `feature/backend-wiring` (working tree, uncommitted) off `develop`
> **Scope grounded in:** PRD §18/§23.5/§24/§9.0, ADR-0013, ARCHITECTURE §3.2/§4.3/§6.2, CLAUDE.md §12.
> **Verdict:** **RESOLVED (ship-eligible)** with a small must-fix list — none of which block the *fence/PII/boundary* gate, but one (R-1) must be tracked before any responder go-live.

This review covers the ADR-0013 consolidation: electoral-scope (D13) on petition-sign + rating, the
reporting↔responders routing split, moderation subject-author resolution (D16), and the CI test-harness
fix. The four review questions, answered with evidence:

---

## 1. Can the binding-action electoral scope (D13) be bypassed? — **NO (RESOLVED)**

The fence is **tier + electoral scope + one-per-person**, all server-side, none token-derived.

- **Tier half is live-resolved, not claim-trusted.** `RequiresTierAspect` (`common/security/RequiresTierAspect.java`)
  reads the caller's `publicId` from the security context and asks `TierResolver.resolveLiveTierRank()` —
  the JWT `trustTier` claim is never consulted. `TierGateForgedClaimIntegrationTest` proves a valid-signature
  token forged to `T3` for a live-T1 user is rejected `403 TIER_TOO_LOW`. So a forged claim cannot reach the
  binding path. ✔
- **Electoral half is deny-by-default at every link.** `ElectoralScopeService.isElectorOf`
  (`identity/application/service`) returns `false` on null user, null target, no profile, no `isElectoral`
  location, or no snapshotted constituency. The constituency is the FK snapshotted on the single
  `isElectoral` `ProfileLocation` (voter-ID-authoritative, cooldown-guarded, DB partial-unique
  `ux_profile_location_one_electoral`), so it is stable and not freely user-mutable. ✔
- **Actor identity is the authenticated principal, never the body.** Both `PetitionController.sign` and
  `RatingController.submit` pass `CurrentUser.requirePublicId()`; `RatingService` re-reads it internally. A
  caller can only act *as themselves* — the precondition for one-per-person and no-self-action holding. ✔
- **One-per-person is DB-guaranteed.** Pre-check + unique constraint with a `DataIntegrityViolationException`
  → clean `409` on the race, in both `PetitionService.sign` and `RatingService.submit`. ✔
- **Constituency-less reps correctly skipped, missing reps rejected.** `RepresentativeQueryApi.constituencyOf`
  returns empty for councillor/special-seats/nominated (no gate), throws `NOT_FOUND` for a non-existent rep
  (cannot rate/petition a phantom). ✔
- **Tested as the keystone.** `RatingServiceTest` (7/7) and `PetitionServiceTest` (11/11) assert out-of-scope
  → `OUT_OF_SCOPE`, in-scope → persists, self → `CONFLICT_OF_INTEREST`, duplicate → `CONFLICT`, and that only
  the scope/electoral ports are consulted. All GREEN.

**Residual risk:** the integration-level bypass test (forged token → real `POST /ratings` with a non-elector,
expect 403) is unit-mocked, not yet an HTTP IT. Low risk (each link is independently proven) but add one
end-to-end IT before launch — see R-3.

## 2. Is there any token balance in a binding path? — **NO (RESOLVED) — fence intact (D18 / §23.5)**

- `RatingService` and `PetitionService` inject **no** token/wallet/ledger collaborator. Constructor
  signatures carry only `RatingRepository`/`PetitionRepository`, `ScopeGuard`, `RepresentativeQueryApi`,
  `ElectoralScopeApi`, mapper, audit. Verified by import scan across `engagement/` and `accountability/`:
  every `tokens`/`Wallet`/`balance` hit is a **comment asserting the fence**, never a call.
- `ElectoralScopeService` and `RepresentativeQueryService` likewise inject no token type — the electoral
  ports are scope/identity ports, not wallet ports, exactly as ADR-0013 §4b requires.
- Flagging (`FlagService`) and moderation actions read no balance either — civic-safety actions are never
  priced (correct, PRD §23.5).

The fence holds **by construction** (no collaborator to consult), which is stronger than a runtime guard.

## 3. Cross-module PII leakage? — **NONE FOUND (RESOLVED)**

- **Query ports expose only `UUID`/`boolean`/`void`** — no entity, no `Optional<Entity>`, no PII.
  `ReportQueryApi` (void/boolean), `IssueCategoryQueryApi`, `RepresentativeQueryApi` (`Optional<UUID>`),
  `ElectoralScopeApi` (`boolean`), `SubjectAuthorQueryApi` (`Optional<UUID>` of an **account public id**).
- **Anonymous sensitive reports stay anonymous across the boundary.** `ReportSubjectAuthorQuery.authorOf`
  returns `reporterProfileId`, which is `null` for an anonymous filing (D-Q1) — moderation receives `null`,
  the D16 guard is vacuously satisfied, and no reporter linkage is ever surfaced. `ReportService` keeps the
  ownership-mismatch-as-404 and PUBLIC-only public reads unchanged.
- **Audit carries references, never PII.** Every new `AuditEvent` (self-action, scope-denied, moderation
  action/appeal) records actor/subject **public ids + a reason code** only; `AuditEventType` Javadoc
  re-states "no raw PII". `MODERATION_ACTION_TAKEN`/`MODERATION_APPEAL_RESOLVED` attach no content body.
- **Grain is consistent (account public id) end-to-end**, so the D16 self-action / appeal-independence guards
  actually match: `FlagController`/`ModerationQueueController`/`AppealController` all pass
  `CurrentUser.requirePublicId()`; owners store the author from `CurrentUser` at create time;
  `ScopeGuardImpl.isNotSelf` compares against `CurrentUser.publicId()`. (Note the *parameter names*
  `flaggerProfileId`/`appellantProfileId` are misleading — they carry the **account** public id — see R-2.)

## 4. Boundaries respected + CI fix sound? — **RESOLVED**

- **ArchUnit `ModuleBoundaryTest` is GREEN (6/6)**, including the `boundaryRuleActuallyBites` canary that
  proves the new `noModuleDependsOnAnotherModulesDomainOrInfrastructure` rule is not a false-GREEN. All
  cross-module edges are `api → api` (sanctioned) or the blessed `geography.domain.model` FK reference
  (`Constituency`) used by `identity`/`institutions` per ARCHITECTURE §4.3. No module imports another's
  `domain`/`infrastructure` outside the allow-list.
- **No synchronous reporting↔responders cycle.** Reads are sync `responders → reporting`
  (`IssueCategoryQueryApi`/`ReportQueryApi`/`ReportLifecycleApi`); the reverse routing-on-create stays async
  `// TODO(wiring)` on the outbox. Matches ADR-0013 §1/§4a exactly.
- **CI fix (`AbstractHttpIntegrationTest`) is sound and security-neutral.** It installs the production
  `/api/v1` context-path once via a `MockMvcBuilderCustomizer` default request, hooking the **same**
  `@AutoConfigureMockMvc` builder — so the full security filter chain, JWT filter, and 401/403 envelopes are
  preserved. It does **not** introduce `@WithMockUser`-style auth bypass or relax any filter. Compilation and
  the targeted suite pass offline.

---

## Findings & must-fix list

| ID | Sev | Finding | Required action | Owner |
|----|-----|---------|-----------------|-------|
| **R-1** | **P2** | **Responder case-lifecycle endpoints are role-gated only, no scope check.** `ResponderAdminController` `assign/start/resolve/escalate` grant `RESPONDER_AGENT`/`RESPONDER_ADMIN`, but the `responders` module makes **zero** `@taarifuAuthz.canActOnArea/Category` calls. Any agent can drive **any** report's lifecycle regardless of their `RoleAssignment` area/category scope — a horizontal-authz gap and an election-period neutrality risk (an out-of-area agent resolving/escalating cases). | Add scope enforcement before the `ReportLifecycleApi` call: resolve the report's ward/category (via `ReportQueryApi` extended to expose them, or the OWNER assignment) and gate on `@taarifuAuthz.canActOnArea/canActOnCategory`. Until then, restrict these endpoints to `ADMIN`/`MODERATOR` only. **Must be closed before any responder-agent go-live** (the security launch gate, PRD §24.4). | responders + identity |
| **R-2** | P3 | **Misleading parameter names mask the grain contract.** `FlagService.flag(UUID flaggerProfileId…)` and `AppealService.fileAppeal(UUID appellantProfileId…)` are documented as "profile public id" but actually carry the **account** public id. The behaviour is correct and consistent; the names invite a future caller to pass a true profile id and silently break the D16 self-action match (a security defect). | Rename to `…AccountPublicId` (or `actorPublicId`) and tighten the Javadoc to state the account-id grain. Pure rename, no behaviour change. | moderation |
| **R-3** | P3 | **No end-to-end HTTP bypass test for the binding fence.** Electoral-scope + no-token is proven at the unit level (mocked ports) but there is no `MockMvc` IT firing a real T3 token at `POST /ratings` / `POST /petitions/{id}/signatures` as a non-elector and asserting `403 OUT_OF_SCOPE`. | Add one HTTP IT per binding endpoint (now trivial on the new `AbstractHttpIntegrationTest`) covering out-of-scope-denied and in-scope-allowed. | qa + security |
| R-4 | P4 (note) | **Binding-success audit gap.** `PetitionService.sign` does not yet emit a `PETITION_SIGNED` success audit (denials are audited; the type is awaited centrally). `RatingService.submit` emits none on success. Not a leak, but the immutable trail is incomplete for the most sensitive civic acts. | Add `PETITION_SIGNED`/`RATING_SUBMITTED` to `AuditEventType` and emit on success (refs only, no PII). | common + owners |

## Launch-gate status for this increment

- Fence not bypassable (tier live-resolved, electoral deny-by-default, actor from token) — **PASS**
- No token balance in any binding/flag/moderation path (by construction) — **PASS**
- No cross-module PII leak; anonymity preserved; audit is reference-only — **PASS**
- Module boundaries enforced; ArchUnit GREEN (6/6); CI harness fix security-neutral — **PASS**
- Horizontal authz on responder lifecycle (scope) — **OPEN (R-1, P2)** — gate condition for responder go-live
- E2E bypass IT + success-audit completeness — **OPEN (R-3, R-4)** — non-blocking, track to closure

**Go / No-go:** **GO** for merge to `develop` (the consolidation is correct and the must-stay-GREEN guard
holds). **Conditional No-go** for any *responder-agent production enablement* until **R-1** is closed.
