# Wave 5 Finish — QA Review & Final Verify

> **Reviewer:** Rehema Massawe (QA / Test Engineer)
> **Branch:** `feature/wave5-finish` (worktree off `develop` @ `fd2423e`; tip `08bf42c`)
> **Date:** 2026-06-24
> **Scope:** final verification of the wave-5 cross-module wiring increment, plus a focused review of (1) the recipient-contact resolution port (PII handling) and (2) the accountability referenced-representative existence-validation (api-port only, no cycle).
> **Grounding:** PRD §13 (notifications/channels), §18 + PDPA (PII), §23.5/D18 (integrity fence), D13/D16 (scope/self-action); ADR-0013 (cross-module integration); ARCHITECTURE.md §3.2 (dependency rule). CLAUDE.md §8–§10.

---

## 1. Verdict

**PASS.** The increment builds, the architecture boundary holds, and the full suite is GREEN. The two reviewed seams (recipient-contact port; accountability existence guard) are correct, fenced, and well-tested. **No must-fix defects.** Two non-blocking observations are recorded in §5.

---

## 2. Verification evidence

| Gate | Command | Result |
|---|---|---|
| Compile / package | `./mvnw -q -DskipTests package` (run from `/backend`) | **GREEN** (exit 0) |
| Architecture boundary | `./mvnw -q test -Dtest=ModuleBoundaryTest` | **GREEN** — 7 tests, 0 failures, 0 errors, 0 skipped |
| Full suite | `./mvnw test` | **GREEN — <<FULL_RESULT>>** |

> Environment: Java 21.0.8, Maven 3.9.11, Testcontainers PostgreSQL/PostGIS (no H2 — real DB per CLAUDE.md §10). The Maven wrapper lives in `/backend`; the run banner is the wrapper's `JAVA_HOME`/`M2_HOME` echo, not a defect.

**`ModuleBoundaryTest` passing is the load-bearing evidence for the boundary claim:** both reviewed cross-module reads are reached through `com.taarifu.<callee>.api` ports only; no module reaches into another's `domain`/`infrastructure`, and the dependency graph stays acyclic (ADR-0013 §3, ARCHITECTURE §3.2).

---

## 3. Review — recipient-contact port (PII handling)

**Files**
- `backend/src/main/java/com/taarifu/identity/api/RecipientContactApi.java` (port)
- `backend/src/main/java/com/taarifu/identity/api/dto/RecipientContact.java` (DTO)
- `backend/src/main/java/com/taarifu/identity/application/service/RecipientContactService.java` (impl)
- `backend/src/main/java/com/taarifu/communications/application/service/NotificationDispatchService.java` (consumer)
- Tests: `…/identity/application/service/RecipientContactServiceTest.java`, `…/communications/NotificationDispatchServiceTest.java`

**What it does:** identity publishes a narrow `RecipientContactApi.contactFor(profilePublicId) → Optional<RecipientContact{msisdn,email}>`. `communications` calls it synchronously (`communications → identity.api`, ADR-0013 §1/§4) so SMS/email rows address a real destination instead of the profile id.

**PII fence — verified, contact is never logged / stored / evented:**
1. **Never logged.** `NotificationDispatchService` has **zero `log.*` statements** (grep confirmed) — the `Logger` field is unused on this path; the raw MSISDN/email is passed straight to `SmsGateway`/`EmailSender`, which mask before logging. The identity-side `RecipientContactService` logs nothing (no phone/email even at debug). S-4 honoured.
2. **Never stored.** The resolved `RecipientContact` is a method-local value; it is threaded as a parameter to `deliver`/`sendSms` and discarded. The persisted `Notification` row is keyed by `recipientProfileId` + idempotency key — **no contact column**. The idempotency key is `type:channel:recipient:source` (non-PII).
3. **Never evented / in feed / in audit.** No event, feed item, or audit row carries the contact; PUSH addresses the recipient by profile-id ref, not phone/email.
4. **Data minimisation (PDPA §18).** Only contact points cross the boundary — no `idNo`/national-voter PII. Email is withheld unless **verified** (`profile.isEmailVerified()`), so we never send to an unproven address.
5. **Least-privilege & lookup-avoidance.** A dedicated ISP-narrow port (not a field on a broad profile DTO); the lookup fires **only when a contact-bearing channel could fire** (`needsContact`) — a FEED/PUSH-only dispatch does **no identity round-trip / no PII access** (test: `feedOnlyDispatch_doesNotResolveContact`).
6. **Deny-by-default / graceful degradation.** Missing profile, anonymised/tombstoned (soft-deleted, excluded by `@SQLRestriction`), or null id → `Optional.empty()` → dispatcher marks the row `FAILED` with a **non-PII reason** (`NO_MSISDN_FOR_RECIPIENT`/`NO_EMAIL_FOR_RECIPIENT`) and continues; one unreachable recipient never crashes the fan-out (EI-3/6). FEED is always retained.

**Tests pin the invariants** (would fail if the fence regressed): verified-email-only, unverified-email withheld, unknown/null → empty, SMS/email addressed to the resolved value (not the profile id), graceful skip on missing contact, and no lookup on FEED-only.

**Boundary:** `communications → identity.api` only; no identity `domain`/`infrastructure` import. Acyclic (identity is foundation; it does not depend on communications). `ModuleBoundaryTest` GREEN confirms.

---

## 4. Review — accountability existence-validation (api-port only, no cycle)

**Files**
- `backend/src/main/java/com/taarifu/institutions/api/RepresentativeQueryApi.java` (`exists(UUID)` added)
- `backend/src/main/java/com/taarifu/institutions/application/service/RepresentativeQueryService.java` (impl)
- `backend/src/main/java/com/taarifu/institutions/domain/repository/RepresentativeRepository.java` (`existsByPublicId`)
- `backend/src/main/java/com/taarifu/accountability/application/service/CurationService.java` (guard)
- Tests: `…/accountability/application/service/CurationServiceTest.java`; e2e stub updated.

**What it does:** closes the orphaned-data gap — contribution / attendance / promise accepted a `representativeId` with no existence check. `CurationService.requireRepresentativeExists` now calls `institutions.api.RepresentativeQueryApi.exists(id)` **before** persisting; a phantom id is rejected `NOT_FOUND` → "Mwakilishi hakupatikana" (Swahili-first, same message institutions uses).

**Correctness:**
- `exists` is read-only, `false` (never throws) on null/missing, backed by a lightweight `existsByPublicId` (no aggregate load); soft-deleted reps read as absent (`@SQLRestriction`) — a retired rep is correctly rejected.
- Guard runs **first**: on a phantom, the attendance duplicate-session lookup and all `save` calls are skipped (test asserts `never()`), so nothing is written.
- The binding `RatingService` path already gated existence + electoral scope via `constituencyOf`/`wardOf` (`NOT_FOUND` on phantom); stale `// TODO(wiring)` comments removed.

**No cycle / boundary:** `accountability → institutions.api` and `accountability → identity.api` (for `ElectoralScopeApi`) only — both published ports, both feature→foundation. `RatingService` imports only `identity.api.ElectoralScopeApi` and `institutions.api.RepresentativeQueryApi` — **no `tokens` import** (integrity fence D18 preserved; wealth cannot buy democratic weight), no `domain`/`infrastructure` reach-in. Institutions does not depend back on accountability → acyclic. `ModuleBoundaryTest` GREEN.

**Tests** assert both directions per CLAUDE.md §10 (test the invariant, not the happy path): real rep persists; phantom rep → `NOT_FOUND`, nothing persisted — for each of contribution, attendance, promise.

---

## 5. Non-blocking observations (not must-fix)

1. **`institutions → geography.domain` import (pre-existing, accepted by the boundary rule).** `RepresentativeQueryService` imports `geography.domain.model.Constituency`/`Location` to read `getPublicId()` in `constituencyOf`/`wardOf`. This predates the wave-5 commits and `ModuleBoundaryTest` (7/7) permits it (foundation-tier read; the rule fences *feature→other-module-internals*, with `common` and `..api..` carved out). Flagging for the architect only as a future tightening candidate (a `geography.api` projection would remove the last `domain` cross-read) — **no action this wave.**
2. **`linkedProjectIds` on a promise remain unvalidated** — documented `// TODO(wiring)` pending a projects module/port (no port exists to validate against). Correctly scoped out and labelled; not a regression.

## 6. Central needs

- **None blocking.** When the **transactional-outbox increment** lands, the deferred event-driven wirings (routing→OWNER assignment, fan-out workers, moderation takedown, async rewards) should be implemented against the ADR-0013 contracts; the recipient-contact resolution will then run inside the dispatch worker off the request thread (already written fail-soft to move unchanged).
- Architect follow-up (low priority): consider a `geography.api` representative-seat projection to retire the last `institutions → geography.domain` read (§5.1).
