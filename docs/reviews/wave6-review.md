# Wave 6 — Gap-Closure Review (A1–A6)

> **Reviewer:** Salim Juma (Security & Privacy Engineer)
> **Branch:** `feature/wave6-gaps` (isolated worktree; full backend @ `develop` base `c54bf20`)
> **Scope:** verify the six gap closures from `docs/reviews/TODO-WIRING-AUDIT.md` §2 (category A) shipped on this branch — funnel analytics (A1), report-routing reverse leg (A2), flag/appeal analytics (A4), moderation→identity sanction (A5), EXIF/geo strip-before-serve (A6). A3/A7 (USSD SMS/ward-code) are out of scope (Phase-2, central communications port).
> **Grounding:** ADR-0013 (cross-module via `..api..` + events), ADR-0014 (outbox), PRD §18 (privacy/PDPA), Appendix E (analytics), §12.1/§24.3/D21 (routing), §25.1 (erasure), EI-8 (EXIF strip).

## Verdict: **PASS** — no must-fix. Launch-gate-relevant gaps A1 and A2 are closed and tested; A4/A5/A6 (P2 hardening) are closed and tested.

---

## 1. Build & test results (exact)

| Step | Result |
|---|---|
| `./mvnw -q -DskipTests package` (in `backend/`) | **BUILD SUCCESS** |
| `./mvnw test -Dtest=ModuleBoundaryTest` | **7/7 GREEN** — no new boundary violations |
| New/changed unit tests (11 classes) | **87/87 GREEN** |
| **FULL `./mvnw test`** | **`Tests run: 692, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS** |

Full-suite count **692** (target was 680+); +~40 over the wave-5 baseline from the new A1–A6 tests
(`IdentityFunnelAnalyticsTest`, `ModerationSanctionHandlerTest`, `ResponderAssignedHandlerTest`,
`ReportServiceTest` additions, `FlagServiceTest`/`AppealServiceTest`/`ModerationQueueServiceTest` additions,
`BaselineMetadataStripperTest`, `MediaServiceTest` additions, `AccountProvisioningServiceTest` additions).

No migration was added (none required — see §3). `AuditEventType.java` is **unchanged**, so the `ck_audit_event_type`
CHECK constraint is untouched and no V123 recreate was needed.

---

## 2. The A1–A6 closures actually work (functional confirmation)

**A1 — verification-funnel analytics emitted (P1, KPI gate).** A single emission point
`identity.application.service.IdentityFunnelAnalytics` appends one `analytics.api.event.CivicActivityRecorded`
to the outbox via `OutboxWriter` in the caller's transaction. Wired at all five funnel steps:
`SignupService` → `ACCOUNT_SIGNED_UP` (APP); `AccountProvisioningService` → `ACCOUNT_SIGNED_UP` (USSD);
`ProfileService` → `PROFILE_COMPLETED` on T1→T2; `VerificationService` → `IDENTITY_VERIFICATION_STARTED`
(and `_FAILED` on dedup block); `VerificationReviewService` → `IDENTITY_VERIFIED` / `IDENTITY_VERIFICATION_FAILED`.
The §3.3 verification-funnel KPI now has a data source. Verified by `IdentityFunnelAnalyticsTest` + the per-service tests.

**A2 — report transitions to ASSIGNED on RESPONDER_ASSIGNED (P1, DoD round-trip).**
`reporting.application.service.ResponderAssignedHandler` (a `DomainEventHandler` auto-registered by
`EventDispatcher` via `List<DomainEventHandler>` injection) consumes `responders.api.event.ResponderEventTypes.RESPONDER_ASSIGNED`,
acts on **OWNER only** (COLLABORATOR is a no-op), and delegates to `ReportService.applySystemAssignment`, which
sets `Report.assignedResponderId` and drives the guarded `NEW → ASSIGNED` transition (state machine stays single-owned
in `ReportService`). Idempotent: no-op unless the report is still `NEW`; missing/soft-deleted report is a benign no-op
(no DLQ). The routing round-trip is now closed on the report side. Verified by `ResponderAssignedHandlerTest` (OWNER
transition, COLLABORATOR skip, redelivery idempotency) + `ReportServiceTest.applySystemAssignment_*`.

**A4 — flag / appeal analytics emitted (P2, KPI completeness).** `FlagService.flag` emits `CONTENT_FLAGGED`
(abuse-rate numerator); `AppealService.decideAppeal` emits `MODERATION_APPEAL_RESOLVED` (closes the
flag→action→appeal→resolution funnel). Both append on the outbox in the action transaction; both already wrote the
immutable audit row. Verified by `FlagServiceTest` / `AppealServiceTest` additions.

**A5 — moderation sanction consumed by identity (P2).** `ModerationQueueService.takeAction` appends
`moderation.api.event.ModerationSanctionApplied` (`{subjectAccountId, sanctionType}`) on a SUSPEND/VERIFY_REQUEST
action (skipped when author is null). Identity's `ModerationSanctionHandler` consumes
`MODERATION_SANCTION_APPLIED` and applies `User.suspend()` (account → SUSPENDED, login path already refuses
non-ACTIVE) + mirrors `AuditEventType.USER_SUSPENDED`. Moderation never reaches into identity (ARCHITECTURE §3.2) —
the effect rides the outbox. Naturally idempotent (already-suspended = no-op, no double-audit). `VERIFY_REQUEST` is
recognised but logged-only (no identity gate flag modelled yet — a future schema change, correctly out of this
analytics+consume scope; never mis-applied as a suspension). Unknown account = forward-tolerant no-op (handles §25.1
erasure between action and delivery). Verified by `ModerationQueueServiceTest` (emission) + `ModerationSanctionHandlerTest`
(suspend, idempotency, unknown-account, verify-request).

**A6 — EXIF/geo stripped before serve (P2, EI-8/§18 privacy).** On a CLEAN scan verdict
`MediaService.applyScanVerdict` → `stripMetadata` reads the stored bytes, runs the dependency-free
`BaselineMetadataStripper` (JPEG: drops APP1–APP15 + COM segments, keeps APP0/DQT/DHT/SOFn/scan verbatim;
PNG: drops `eXIf`/`tEXt`/`iTXt`/`zTXt`/`tIME` chunks), re-stores the scrubbed bytes in place, and **only then**
calls `markExifStripped()`. The flag is set *after* the byte strip — an object that cannot be stripped (bytes
missing) stays `exifStripped=false` and a `CONFLICT` is raised, so the serve path keeps refusing it (fail-safe; a
photo with embedded GPS never reaches another citizen). Stripper is fail-safe on malformed input (returns input
unchanged, never corrupts/throws into the worker). Verified by `BaselineMetadataStripperTest` (7 cases: real
EXIF-bearing JPEG/PNG scrubbed, structural segments preserved, malformed/non-image unchanged) + `MediaServiceTest`
strip-before-serve + bytes-missing cases + `MediaFlowIntegrationTest`.

---

## 3. Privacy & boundary checks (my gate concerns)

**No PII in any new event or log (PRD §18, PDPA, ADR-0014 §1) — PASS.**
- Swept every new `log.*` and `outboxWriter.append`/`new CivicActivityRecorded`/`new ModerationSanctionApplied`
  line in the diff: **zero** occurrences of msisdn/phone/name/email/national-or-voter `idNo`/free-text body.
- Funnel facts carry **only** coarse dimensions (trust-tier name + channel name), `actorRef=null`, and a **fresh
  random `aggregateId`** (not the subject account) — the replayable outbox cannot be used to re-identify which
  citizen moved up the funnel. This is the right call: the funnel KPI counts pseudonymous volume per tier/channel.
- Moderation analytics carry controlled-vocabulary **enum names** (`reason().name()` = e.g. ABUSE, `type().name()`
  = SUSPEND, `outcome().name()` = UPHELD) — not free text. The free-text `detail()`/`note()`/`decisionNote()`
  fields are correctly **excluded** from events.
- The sanction event carries only the author's opaque **account public id** + `SanctionType` enum — no name/phone/ID.
- Logs reference `eventId`/report id/sanction type only; the unknown-account path logs no account id.

**Cross-module boundary (ADR-0013) — PASS.** Every new cross-module import is to a sibling's `..api..` package
(`identity → moderation.api.event`; `reporting → responders.api.event`; `moderation → analytics.api.event`); no
`domain`/`infrastructure` reach-through. Consumers that decode a relay `JsonNode` (`ResponderAssignedHandler`) read
string-coded enums/ids positionally precisely to avoid importing a sibling's `domain` enum — the correct established
pattern. `ModuleBoundaryTest` (all 7 rules) is GREEN, independently confirming no new violation.

**Outbox / idempotency (ADR-0014) — PASS.** All emissions use `OutboxWriter.append` inside the producer's
transaction (atomic; off the citizen path). All three new consumers are idempotent on existing state (report status
gate; account-status gate), so at-least-once redelivery never double-applies or double-audits. No per-event dedup
table needed.

**Single envelope, `@PreAuthorize`, Javadoc — PASS.** No new controller surface was added (all closures are
service/handler-level off existing endpoints), so the envelope and method-security posture are unchanged. Every new
class/method carries full Javadoc stating responsibility, the "why" (privacy/boundary/idempotency), and edge cases —
consistent with CLAUDE.md §8.

**Migration / audit CHECK — PASS (none needed).** The new analytics catalogue values live in the
`AnalyticsEventType` enum, stored as a `VARCHAR` (no DB CHECK), so they are additive with no migration (Appendix E.0).
`AuditEventType` is unchanged; the referenced `MODERATION_ACTION_TAKEN` / `MODERATION_APPEAL_RESOLVED` / `USER_SUSPENDED`
values already exist, so `ck_audit_event_type` / V123 was correctly **not** touched.

---

## 4. Residual / out of scope (not must-fix)

- **A3 / A7 (USSD SMS send + ward-code lookup):** still stubs — Phase-2, blocked on communications publishing an
  SMS command port (central decision per the wiring audit §6.1). Not on this branch's scope; degrades safely.
- **A5 `VERIFY_REQUEST`:** consumed but logged-only (no identity verify-request gate flag yet). Acceptable —
  modelling that flag is a schema change for a later increment; the handler never mis-applies it as a suspension.
- **A6 worker invocation is synchronous in `applyScanVerdict`** (in the scan-callback transaction), not a separate
  async quarantine→promote job. Functionally enforces the "stripped before served" invariant on real bytes for the
  MVP object sizes; an async/parallel strip worker is a scale refinement (SRE), not a correctness gap.

These are documented, fenced, and do not affect the launch gate. No P1/P2 open against this branch.
