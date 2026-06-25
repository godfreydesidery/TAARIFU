# Phase-2 Wave-3 review — producer wiring (search indexing + moderation auto-assist content ports)

> **Verdict: PASS.** Final integrated verify + architectural/privacy review of the wave-3 producer wiring on
> `feature/phase2-wave3` (branch head `1cbdc62`, off `develop @ 14856be`; waves 1–2 already merged).
> **Reviewer:** Solution Architect (David Okello). **Date:** 2026-06-25.
> **Grounds:** PRD §18/§25.3 (privacy/visibility), ADR-0013 (cross-module integration), ADR-0017 (search),
> ADR-0018 (moderation auto-assist), ARCHITECTURE §3.2 (boundaries).

## Scope reviewed

30 files, +1710/−14 vs `develop`. Two cross-module seams wired on producers' existing write paths:

- **`search.api.SearchIndexApi`** (owner → search `upsert`/`remove`) — index public-safe discovery projections.
- **`moderation.api.SubjectContentQueryApi`** (owner provides impl; moderation owns the interface — dependency
  inversion) — surface a flagged subject's transient scorable text to the auto-assist scorer.

## 1. Build + boundary + module tests — GREEN

| Gate | Result |
|---|---|
| `./mvnw -q -DskipTests package` | **BUILD SUCCESS** (clean compile) |
| `./mvnw test -Dtest=ModuleBoundaryTest` | **Tests run: 7, Failures: 0, Errors: 0** — 7/7 |
| Changed-module tests (11 classes) | **Tests run: 95, Failures: 0, Errors: 0** — BUILD SUCCESS |

Changed-module classes run green: `ReportServiceTest` (23), `ReportSubjectContentQueryTest` (5),
`ReportingErasureHandlerTest` (2), `AnnouncementServiceTest` (13), `AnnouncementSubjectContentQueryTest` (4),
`PetitionSubjectContentQueryTest` (3), `QuestionSubjectContentQueryTest` (4), `InstitutionsAdminServiceTest` (10),
`ResponderAdminServiceTest` (14), `RatingSubjectContentQueryTest` (5), `AnalyticsIntegrationTest` (12).

**Boundary confirmation (the key invariant):** the new `source → search.api` and `source → moderation.api`
edges are `api → api`. No producer imports `search.domain.repository`/`search.infrastructure` or
`moderation.domain`/`moderation.infrastructure` (only `search.api`, `search.api.dto`,
`search.domain.model.enums` for the published enums, and `moderation.api`). `SubjectContentQueryApi` is owned by
moderation (a foundation module) and *implemented* by feature owners — dependency inversion, **no cycle**.
ModuleBoundaryTest's four legacy rules + the ADR-0013 internals fence all hold (7/7).

## 2. Wiring correctness

### 2a. Search indexing — populates on create/update, deletes on hide/delete

- **Reporting (`ReportService`)** — the privacy-critical path, **correct**. `reindexForDiscovery(report, category)`
  is the single decision point, called on file, every guarded `transition`, `resolve`, and `confirmResolution`.
  The fence: `publicSafe = visibility == PUBLIC && !isAnonymous()`; non-public-safe ⇒ `remove(PUBLIC_REPORT, id)`
  (idempotent, defence-in-depth). PRIVATE (sensitive/forced), citizen-private, and **anonymous** reports are
  **never** upserted — anonymity is screened explicitly (belt-and-braces beyond the category force-private rule).
  Snippet truncated to 480 chars; only title/description/ward/category/code pushed; `authoredByAccountId` =
  reporter profile id (used only for ADR-0017 §3 suspended-author maintenance, never returned). No geo-point,
  no PII. **Asserted** by `ReportServiceTest`: private/anonymous ⇒ `never upsert` + `remove`; public ⇒ upsert,
  no remove; resolve re-upserts.
- **PDPA erasure (`ReportingErasureHandler`)** — on sever, the report becomes anonymous, so the handler
  `remove(PUBLIC_REPORT, report.publicId)` per report. Idempotent, no PII logged. Closes the erasure → discovery
  loop correctly.
- **Communications (`AnnouncementService`)** — `indexForDiscovery` on the went-live funnel (`publish`,
  `approveAndPublish`): `PUBLISHED` ⇒ upsert PUBLIC (title + SW/EN body snippet ≤280 + first-area facet +
  category); any other state (draft/held/scheduled/expired) ⇒ remove. The `moderationHeld` flag, full body,
  attachments, and schedule are never indexed.
- **Institutions (`InstitutionsAdminService`)** — `indexRepresentative` on create/update; `remove` on delete.
  Visibility flips by status: `PENDING_VERIFICATION` ⇒ STAFF (anti-claim-spoofing), `SITTING`/`FORMER` ⇒ PUBLIC.
  Title = Swahili seat label; keywords = SW+EN role synonyms + mandate/legislature; area = constituency-or-ward
  id. Bio and linked profileId deliberately **not** pushed; `authoredByAccountId` null (directory entity).
- **Responders (`ResponderAdminService`)** — `indexOrganisation` on create/update/verify; visibility mirrors
  `isPubliclyListable()` (ACTIVE && verified ⇒ PUBLIC, else STAFF). Type as keyword facet; contacts not pushed;
  no author. Un-verify flips to STAFF (idempotent visibility flip, row retained for re-verification).

### 2b. `SubjectContentQueryApi` impls — text only, never logged/leaked; resolver discovers them

Five impls registered, one per `FlagSubjectType` the module owns:

| `FlagSubjectType` | Impl (module) | Scorable text returned |
|---|---|---|
| `REPORT` | `ReportSubjectContentQuery` (reporting) | title + description |
| `ANNOUNCEMENT` | `AnnouncementSubjectContentQuery` (communications) | bilingual body (SW + EN), **not** title |
| `PETITION` | `PetitionSubjectContentQuery` (engagement) | title + body |
| `QUESTION` | `QuestionSubjectContentQuery` (engagement) | question body + rep's answer (whole thread) |
| `RATING` | `RatingSubjectContentQuery` (accountability) | the rating's free-text comment only |

All are `@Transactional(readOnly = true)`, return `Optional<String>` of **content under review only** (no
reporter/asker/rater identity, no geo, no other-party PII), return `empty` for absent/soft-deleted/blank
subjects (⇒ screen skipped, item still goes to a human — EI-18 floor). None log the text.
`moderation.SubjectContentResolver` auto-discovers all five by `subjectType()` into an `EnumMap`, throws on a
duplicate claim, and returns `empty` for unregistered types (graceful degradation). The flag path
(`FlagService` → `AutoAssistService` → resolver → scorer) is intact.

### 2c. Analytics `AUTO_MODERATION_TRIAGED` records (V171)

`AutoAssistService` emits a `CivicActivityRecorded` fact with `analyticsEventType = "AUTO_MODERATION_TRIAGED"`.
Before V171 the handler dropped it as a forward-compatible no-op. V171 + the `AnalyticsEventType` enum value
admit it, so the auto-vs-manual moderation-split KPI (US-12.3) now persists. `AnalyticsIntegrationTest` green (12).

## 3. End-to-end sanity

- **A newly-filed PUBLIC report is now findable via `/search`** — YES. `fileReport` upserts a PUBLIC
  `SearchDocument` (PUBLIC_REPORT type, ward/category facets), and `GET /search` (`websearch_to_tsquery('simple')`)
  returns PUBLIC rows to any reader. (A PRIVATE/sensitive/anonymous report is correctly **not** findable.)
- **Flagging that report triggers an auto-assist score** — YES. `FlagSubjectType.REPORT` resolves through
  `ReportSubjectContentQuery` → scorer → records signal/confidence on the `ModerationItem` → emits the
  AUTO_MODERATION_TRIAGED fact (now recorded). Same for ANNOUNCEMENT/PETITION/QUESTION/RATING.

## 4. Migration V171 — ordering + no collision

V171 is unique, sits above V170 (`V170__communications_digest_notification_type.sql`, the prior highest), no
collision. **Note on the brief's wording:** the task referenced "V171 CHECK widened" — the actual (correct)
implementation does **not** add/widen a CHECK on `analytics_event.event_type`. Per V91, that column is a bare
`VARCHAR` with **no** domain CHECK by design (the handler drops unknown values as a no-op). V171 therefore
*defensively DROPs* any out-of-band CHECK and refreshes the column comment — the established additive-analytics
pattern. This is the right call; adding a CHECK would have regressed the forward-compatible contract.

## 5. Unwired subject types / known gaps (none block PASS — all degrade safely)

1. **Engagement search indexing is NOT done — BLOCKED on a CENTRAL NEED (must-fix for full discovery).**
   `PetitionService.create`, `QuestionService.ask`, `SurveyService.create` carry only `// TODO(wiring/search)`
   markers: `SearchEntityType` has no `PETITION`/`POLL`/`QUESTION` values, and engagement correctly refused to
   edit search's `domain` enum (isolation rule). So petitions, polls, and Q&A are **not discoverable via
   `/search`**. The wave-3 commit subjects ("index petitions/polls/questions for search") **overstate** what
   shipped for engagement — what actually shipped is the moderation content ports. Degrades safely (not
   indexed = not discoverable, never a leak). **Fix:** the search-module owner adds the three additive
   `SearchEntityType` values (ADR-0017 §2 revisit), then engagement wires one upsert/remove per lifecycle.
2. **`FlagSubjectType.PROFILE`** (identity) and **`COMMENT`/`OTHER`** have no content port. PROFILE is a real
   future gap (an identity content port); COMMENT has no standalone entity in engagement (a signature comment is
   a sub-field), documented in the engagement `package-info`. Unregistered ⇒ screen skipped ⇒ human floor (safe).
3. **Announcement unpublish/expire** has no service path that calls `remove`; an announcement that *expires* by
   clock stays in the index until something re-saves it. Not a leak of private content (it was public), but a
   stale public row. **Follow-up:** an expiry sweep, or wire `remove` on an explicit unpublish/expire path.
4. **Responder capability** (`Responder`) is not separately indexed — by design, the directory entity is
   `ORGANISATION`; acceptable per ADR-0017's vocabulary.

## Prioritized must-fix / follow-ups

| # | Item | Severity | Owner |
|---|---|---|---|
| 1 | Add `PETITION`/`POLL`/`QUESTION` to `SearchEntityType`, then wire engagement upsert/remove (engagement discovery is currently dark) | **High** (functional gap, not a leak) | search owner → engagement |
| 2 | Correct/avoid overstated commit subjects ("index … for search") for engagement in future waves | Low (hygiene) | wave authors |
| 3 | Announcement expiry/unpublish → `remove` sweep (stale public rows) | Medium | communications |
| 4 | `PROFILE` content port (identity) for full auto-assist coverage | Low | identity |

## Conclusion

**PASS.** Build, ModuleBoundaryTest (7/7), and all 95 changed-module tests are green. The two cross-module seams
are correctly `api → api` with no internals reach-in and no new sync cycle. The privacy fences hold: the
reporting IDOR/PDPA fence (PRIVATE/sensitive/anonymous never indexed; removed on becoming non-public-safe; removed
on erasure) is enforced in one place and unit-asserted; `SubjectContentQueryApi` impls return transient text only,
never logged/leaked; the resolver discovers them and degrades to the human floor. Analytics records the auto-triage
fact (V171). The only material gap is engagement discovery, which is **blocked on an additive search-enum CENTRAL
NEED** and degrades safely to "not discoverable" — a functional follow-up, not a correctness or privacy defect.
