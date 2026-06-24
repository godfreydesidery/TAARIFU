# Taarifu — Honest `// TODO(wiring)` Audit (post waves 2–5)

> **Owner:** Asha Mwakyusa (Delivery & Project Management)
> **Branch reviewed:** `feature/wave5-finish` worktree (full backend @ `develop` `fd2423e`).
> **Scope:** every `// TODO(wiring)` / `TODO` / `FIXME` marker under `backend/src/main` (Java + SQL migrations).
> **Why this audit exists:** ADR-0013 was written when the outbox was **not yet built** and 50+ cross-module
> calls were deferred as `// TODO(wiring)`. Waves 2–5 since landed the outbox/event bus, real adapters +
> prod-boot, USSD auth, DLQ, admin (users/reports/appeals), mobile deepening, observability, the attachment
> pipeline, FCM tokens, the full national geo-seed, Redis limiters, E2E tests, OpenAPI, and the S-3 /
> media-IDOR / audit-CHECK fixes. **Many TODO comments are now stale** — they describe work that is done.
> This audit reads the actual code (not the comments) and separates real gaps from documentation debt.
> **Grounding:** ADR-0013 (cross-module api-ports), ADR-0014 (outbox), ARCHITECTURE §3.2/§8, PRD §15/§18/§24/§27,
> CLAUDE.md §8 ("stale comments are a defect").

---

## 1. Bottom line (read first)

- **88 `TODO/FIXME` occurrences across 61 files** (ripgrep, `backend/src/main`). Of these, **~52 are `// TODO(wiring)` markers** (the rest are SQL/Javadoc references *to* those markers).
- The headline finding: the **MVP-critical cross-module wiring is DONE**. The transactional outbox is live; report **routing → responder OWNER (D21)** with the §25.2 fallback is wired and tested; **analytics emission** flows from reporting, responders, moderation and engagement; the **electoral-scope fence (F1)** is enforced on ratings and petition-signatures; the **USSD identity/reporting adapters are real**; the **prod-safe first-admin** path exists; the **media-IDOR** is closed and **V121** is fixed.
- What remains is a **short tail of low-severity emissions and one denormalisation leg**, plus a cluster of **by-design id-reference deferrals** that are the intended ADR-0013 pattern (not bugs), plus a **meaningful number of stale comments** that should be deleted so the next reader is not misled into thinking the platform is less finished than it is.

**Counts by category:**

| Category | What it means | `// TODO(wiring)` sites (approx.) | Action |
|---|---|---|---|
| **(A) REAL unbuilt gap** | Work genuinely not done; a real (mostly low) severity item | **7** | Build / schedule (see §2) |
| **(B) By-design id-reference deferral** | The sanctioned "reference by public id, validate via the owner's `..api..` port" pattern — acceptable, not a bug | **~23** | Leave; document as pattern, not debt |
| **(C) STALE — work already done** | Comment claims deferral for work that waves 2–5 completed | **~22** | **Delete / rewrite the comment** |

> The category split is what matters for the gate: only **(A)** affects launch-readiness, and within (A) only **one** item (verification-funnel analytics emission) touches a published KPI; the rest are P2/P3 polish or are blocked on a single central decision (communications publishing two public command ports).

---

## 2. Category (A) — REAL unbuilt gaps

These are genuinely not done. Severity uses the PRD §27.5 gate lens; owner is the lead who must act.

| # | Site(s) | Gap | Severity | Owner |
|---|---|---|---|---|
| A1 | `identity/application/service/*` (no emission), enum `analytics.AnalyticsEventType` (`ACCOUNT_SIGNED_UP`, `PROFILE_COMPLETED`, `IDENTITY_VERIFICATION_STARTED/SUCCEEDED/FAILED`) | **Verification-funnel analytics not emitted.** Reporting, responders, moderation and engagement now emit `CivicActivityRecorded` on the outbox; **identity does not**. The T0→T3 funnel events are *defined* but **no identity flow emits them**, so the §3.3 verification-funnel KPI has no data source even though the recorder + dashboards exist. | **P1** (the one remaining KPI-emission gap; gate-relevant — §27.5 funnel) | Eng (identity) |
| A2 | `reporting/domain/model/Report.java:143`; `Report` Javadoc:45–49 (honestly flagged) | **Routing reverse-leg not consumed.** Filing emits `REPORT_ROUTED`; `RoutingHandler` creates the OWNER `ResponderAssignment` and emits `RESPONDER_ASSIGNED` — but **no reporting `DomainEventHandler` consumes `RESPONDER_ASSIGNED`**, so `Report.assignedResponderId` stays `null` and the report stays `NEW`. The accountable OWNER **exists** in responders (routing/accountability data is captured); only the denormalised pointer + auto-`ASSIGNED` transition on the report row is missing. | **P1** (MVP DoD "official triages" round-trip; data is captured, the report-side status is not) | Eng (reporting) |
| A3 | `ussd/infrastructure/adapter/LoggingUssdSmsSenderStub.java:25`; `ussd/application/service/UssdAlertService.java:44` + `V95` `forwarded` column | **USSD SMS send + area-alert forwarding are stubs.** In-app notification dispatch already resolves the real MSISDN/email via `identity.api.RecipientContactApi` (NOT a gap — see note below); the residual is the **USSD module's own** SMS path, which needs communications to republish a public **SMS command port** in `com.taarifu.communications.api`, and a **Subscription/NotificationPreference command port** for the alert forwarding. USSD must not write communications' tables (ADR-0013), so it correctly waits. | **P2** (USSD reporting is Phase-2 per D-Q7; SMS confirmation degrades to a logged no-op safely) | Eng (communications) — **central** |
| A4 | `moderation/application/service/FlagService.java:116`; `AppealService.java:188` | **`content_flagged` / `moderation_appeal_resolved` analytics not emitted.** `ModerationQueueService.takeAction` **does** emit `MODERATION_ACTION_TAKEN`; flag-raised and appeal-resolved facts are not yet on the outbox, so the abuse-report-rate KPI is incomplete. (Both already write the immutable **audit** event — only the analytics fact is missing.) | **P2** (KPI completeness, not gate-blocking) | Eng (moderation) |
| A5 | `moderation/application/service/ModerationQueueService.java:186`; `moderation.ModerationActionType` | **SUSPEND/VERIFY_REQUEST → identity sanction event not emitted.** A takedown action does not yet emit the identity-module sanction event a consumer would apply. Correctly *not* done by reaching into identity (ARCHITECTURE §3.2); needs an outbox event + an identity consumer. | **P2** (basic moderation is MVP; sanction automation is the deferred half) | Eng (moderation + identity) |
| A6 | `media/application/service/MediaService.java:336` | **EXIF/geo-strip worker not invoked.** On a CLEAN verdict the object is marked `markExifStripped()` but the **byte-level strip + quarantine→served promotion worker** is not wired. The serve-path invariant ("stripped before served") is asserted as a seam, not enforced on bytes. | **P2** (privacy hardening — EI-8/§18; sensitive-report geo-leak) | Eng (media) + SRE |
| A7 | `ussd/application/service/UssdMenuMachine.java:280` | **USSD ward lookup by human code not wired.** `resolveWardCode` accepts a typed UUID and rejects anything else; it does not resolve a human ward **code** via geography's published lookup, so a feature-phone user cannot type a friendly ward code. | **P3** (USSD reporting Phase-2; degrades to UUID entry) | Eng (geography api + ussd) |

> **Corrected during this audit:** in-app **notification recipient MSISDN/email resolution is DONE**, not a gap. `NotificationDispatchService` resolves the real contact via `identity.api.RecipientContactApi.contactFor(...)` and hands `contact.msisdn()`/`contact.email()` to the masking gateway/sender; the old `// TODO(wiring)` markers on that path have been removed. Only the **USSD module's own** SMS path (A3) is still a stub, and that is one central decision (communications' SMS command port). This is captured as stale item **C15** below.

**What truly remains for MVP (from category A only):**
1. **A1 — emit the verification-funnel analytics** from identity (the single remaining published-KPI emission gap; everything else KPI-wise is wired). *Highest leverage; small change.*
2. **A2 — consume `RESPONDER_ASSIGNED` in reporting** to set `assignedResponderId` and auto-transition to `ASSIGNED` (closes the routing round-trip on the report side).
3. **A3 — communications to publish the SMS-send + Subscription/preference `..api..` command ports** so the **USSD** SMS + area-alert forwarding stop being stubs. (In-app SMS/email delivery is already wired via `RecipientContactApi`.)

A4–A7 are **P2/P3** and are correctly *not* on the MVP critical path (moderation analytics completeness; sanction automation; EXIF byte-strip; USSD ward-code) — schedule post-pilot or as hardening.

---

## 3. Category (B) — by-design id-reference deferrals (acceptable; the ADR-0013 pattern)

These are **not bugs and not unbuilt work** in the problematic sense — they are the sanctioned "a module references a sibling's record by its public `UUID` and validates/resolves it through the owner's published `..api..` port" pattern (ADR-0013 §1). The comment marks the seam; the entity stores the id; the validation is a published-port concern. They should **stay**, with the comment kept as a seam-marker (optionally reworded from "TODO" to "cross-module reference" to reduce alarm).

| Cluster | Sites | Why it is by-design |
|---|---|---|
| **accountability ↔ institutions/projects** | `RepresentativeContribution` (`representativeId`), `Promise` (`representativeId`, `promise_project`), `Attendance`, `Rating` subject existence, `CurationService` (`representativeId`/`linkedProjectIds`) | Append-only rows reference reps/projects by public id; existence/scope validated via institutions' published port at the curation seam. Curated authoring is **Phase-2 (D-Q4)**, so the deferral is intentional. |
| **engagement creator/target resolution** | `PetitionService:159`, `QuestionService:119`, `SurveyService:120,155` (`creatorPublicId`→profile, target validation, audience eligibility) | Creator is the authenticated account public id; the *binding* signature path (electoral fence) **is** wired (F1). The remaining markers are creator→profile resolution and audience/target validation — owner-port concerns, engagement authoring is **seam-only at MVP**. |
| **responders ↔ reporting/identity/geography category & area validation** | `Responder`, `RoutingRule`, `ResponderAssignment`, `Organisation`, `CreateResponderRequest`, `CreateRoutingRuleRequest` | Category/area/admin ids referenced by public id; validated via reporting's `IssueCategoryQueryApi` / geography / identity at the admin-create seam. The **routing read-path** (`responders → reporting`) is the sanctioned synchronous edge; the markers are admin-config validation, deferred without blocking routing. |
| **moderation subject resolution** | `Flag.java:57`, `moderation/package-info` | Moderation holds `(subjectType, subjectId)` and resolves author via the owner's `SubjectAuthorQueryApi` (the registry pattern). The `ReportSubjectAuthorQuery` impl already exists; remaining owners publish theirs as their content becomes moderatable. |
| **media host-catalogue validation** | `MediaObject.java:61`, `UploadRequest.java:21`, `MediaService.java:126` (attach authz) | Media references its host `(ownerType, ownerId)` by id; the **serve-path** host-visibility check is now wired (`ReportMediaAccessService`, MF-2 fixed). The remaining attach-time host-catalogue validation is the same by-design owner-port seam. |
| **communications subject/category/reputation refs** | `Announcement.java:79` (category), `AnnouncementController:45` (reputation source), `FeedQueryService:37` (REPRESENTATIVE follows), `AnnouncementService:37,197` (moderation-decision consumer) | Announcements reference categories/subjects by id; feed/announcement enrich via the owner's port. The moderation-gate consumer + reputation source are real future seams but are correctly id-referenced, not reached-into. |

> **Why we accept these:** they keep the dependency graph acyclic and the boundary enforced (the `ModuleBoundaryTest` ADR-0013 rule passes). Turning them into FKs or cross-module imports would be the *legacy* failure mode. The risk is purely **cosmetic** — too many "TODO" labels make the codebase look unfinished. Recommend a follow-up `chore` to reword the **(B)** seam-markers from `// TODO(wiring)` to `// cross-module ref (ADR-0013)` so they stop reading as defects.

---

## 4. Category (C) — STALE comments (work done; delete or rewrite)

These comments describe deferrals for work that **waves 2–5 completed**. Per CLAUDE.md §8 ("stale comments are a defect"), they should be deleted or rewritten in the next docs/cleanup pass. Listing them so the cleanup is mechanical.

| # | File:line | Stale claim | Reality |
|---|---|---|---|
| C1 | `reporting/package-info.java:19–23` | "DEFERRED: routing to responders (`assignedResponderId` stub, reports stay NEW); outbox emission of report events; analytics; attachment virus-scan hook" | **All landed.** Filing emits `REPORT_ROUTED`; outbox is live; analytics emits `REPORT_FILED` + lifecycle; the attachment pipeline (V47/V121, `MediaAttachmentValidator`) is wired. *Only the reverse-leg (A2) remains — rewrite to say exactly that.* |
| C2 | `reporting/domain/model/Report.java:143` | "set on routing once the responders module assigns an OWNER (D21)" — as if nothing is wired | Routing **is** wired; the OWNER is created. Stale phrasing — should point at the **single** remaining reverse-leg consumer (A2), which the class Javadoc (45–49) already states correctly. |
| C3 | `reporting/api/ReportLifecycleApi.java:18–23` | "reverse routing-on-creation … stays `// TODO(wiring)` until the bus lands" | **The bus landed.** Routing-on-creation is wired (async via outbox). Rewrite to "wired via the outbox `REPORT_ROUTED` → `RoutingHandler`". |
| C4 | `analytics/api/AnalyticsApi.java:14–18` | "live emission `// TODO(wiring)` … outbox increment is **not yet built** … each emission site marked TODO until then" | **Outbox built; emission live** from reporting/responders/moderation/engagement via `AnalyticsEventHandler`. Rewrite to list the live producers; note identity (A1) is the one not-yet-emitting site. |
| C5 | `analytics/application/service/AnalyticsRecordingService.java:22–23` | "the seam sibling modules' outbox workers wire to (`// TODO(wiring)` until the outbox increment)" | Same as C4 — workers exist; producers emit. Stale. |
| C6 | `analytics/domain/model/enums/AnalyticsEventType.java:21–22` | "New live emission from sibling modules is marked `// TODO(wiring)` until the outbox increment" | Stale (outbox shipped). Most event types are now emitted; identity funnel types are the exception (A1). |
| C7 | `ussd/infrastructure/adapter/UssdReportingAdapter.java:18` & `UssdIdentityAdapter.java:17` | (Self-describing) "the `// TODO(wiring)` is now closed" — but the words "TODO(wiring)" still appear and trip the grep | These are **correct/done**; the residual string is only a back-reference. Reword to remove the literal marker so audits don't re-flag them. |
| C8 | `ussd/package-info.java:30` | "the call is made against a local port with a dev stub and marked `// TODO(wiring)` to swap to the [real adapter]" | Identity + reporting adapters are **real now**; only the **SMS** sender (A4) is still a stub. Narrow the claim to SMS. |
| C9 | `responders/api/event/ResponderAssignedEvent.java:19` | "the actual outbox publication is added when the outbox/bus lands; today this record is unused" | **Now published** by `RoutingHandler` on OWNER creation. Stale — the event is live. |
| C10 | `responders/application/service/ResponderAdminService.java:367` | "publish `ResponderAssignedEvent` via the transactional outbox once the bus lands" | For the **system-routing** path it is published (`RoutingHandler`). If this manual-assign path still doesn't emit, narrow to "manual-assign path TODO"; otherwise delete. |
| C11 | `accountability/application/service/RatingService.java:64` | (electoral-scope) — the surrounding controller/model comments below | RatingService electoral scope (F1) is **fully wired** (`RepresentativeQueryApi` × `ElectoralScopeApi`, both MP-constituency and councillor-ward tiers). Line 64 itself (profile-id resolution) is a legitimate (B) deferral; but the *dependent* comments are stale. |
| C12 | `accountability/domain/model/Rating.java:32,44,70`; `RatingSubjectType.java:14`; `accountability/api/controller/RatingController.java:31`; `V46__accountability_rating.sql:19` | "electoral scope is the documented `// TODO(wiring)` (D13)" | **F1 is implemented.** Electoral scope is enforced in `RatingService.submit` (and `PetitionService.sign`). These five comments + the migration comment are **stale** and should be rewritten to "electoral scope enforced (F1, RatingService)". |
| C13 | `accountability/package-info.java:17,22` | "Electoral-scope enforcement is a documented `// TODO(wiring)` pending the [institutions/geography mapping]" | Stale — the mapping is wired and enforced for ratings (F1). Curated authoring deferral (D-Q4) is the legitimate part; separate the two. |
| C14 | `moderation/domain/model/enums/ModerationActionType.java:14` and `package-info.java:14` | framed as if outbox/subject-resolution is universally pending | Partly stale: `MODERATION_ACTION_TAKEN` analytics + the `ReportSubjectAuthorQuery` resolver exist. The remaining sanction-event (A5) and per-owner author ports (B) are the real residue — narrow the comment to those. |
| C15 | `communications/application/service/NotificationDispatchService.java` (historic `:238,:241` markers) | Earlier review snapshots listed "resolve recipient MSISDN/email via identity's public API" as a `// TODO(wiring)` | **DONE** — the markers are removed; dispatch resolves the real contact via `identity.api.RecipientContactApi.contactFor(...)` and reads `contact.msisdn()`/`contact.email()`. Any external doc still listing this as a gap (incl. this audit's first draft) is corrected: only the **USSD** SMS path (A3) remains a stub. |

> **Net:** ~21 stale sites. Deleting/rewriting them is **pure documentation debt** (no behaviour change) and is the single cheapest thing to make the repo read as honestly as it now behaves. Recommend a `docs`/`chore` cleanup commit separate from this audit (this task is docs-only and must not edit production sources).

---

## 5. How this maps to the launch gate

- **Category (C) is zero-risk to the gate** — it is comment hygiene; the work is done.
- **Category (B) is zero-risk to the gate** — it is the intended pattern; the boundary test enforces it.
- **Category (A) is the only gate-relevant set, and it is short:** A1 (verification-funnel emission) is the one item touching a published KPI; A2/A3 close the report-routing round-trip and real SMS/email delivery; A4 is one central decision (communications' two api command ports) that unblocks the USSD/SMS tail. A5–A8 are P2/P3 hardening that correctly sit behind Phase-2 fences.

The corresponding launch-readiness rows are refreshed in [`docs/LAUNCH-READINESS.md`](../LAUNCH-READINESS.md) (waves 2–5 update).

---

## 6. CENTRAL INTEGRATION NEEDS (for the team's central backlog)

These are the cross-cutting, single-owner needs this audit surfaces — each unblocks multiple `// TODO(wiring)` sites at once:

1. **communications: publish two public command ports in `com.taarifu.communications.api`** — (a) an **SMS send** port and (b) a **Subscription / NotificationPreference** register port. Unblocks **A3** (USSD SMS sender + area-alert forwarding stop being stubs) and removes the C7/C8 stale markers. *Highest-leverage central item.* (In-app notification recipient resolution is already wired via `identity.api.RecipientContactApi` — not part of this need.)
2. **identity: emit verification-funnel `CivicActivityRecorded` facts** (signup/profile/verification) on the outbox — closes **A1**, the last published-KPI emission gap.
3. **reporting: add a `RESPONDER_ASSIGNED` outbox consumer** that sets `assignedResponderId` + auto-`ASSIGNED` — closes **A2**, the routing round-trip's report-side leg.
4. **media/SRE: stand up the EXIF/geo-strip + quarantine→served promotion worker** — closes **A6** (serve-path privacy invariant on real bytes).

*Docs-only audit. No production source was modified. Counts and categorisations reflect repo state on the `feature/wave5-finish` worktree at authoring; the (C) cleanup and the (A) builds are tracked as separate increments.*
