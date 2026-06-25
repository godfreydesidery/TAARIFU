# Phase-2 Codebase-Completion Wave — Final Integrated Verify + Review

**Branch:** `feature/phase2-complete` · **Reviewer:** Solution Architect (David Okello) · **Date:** 2026-06-25
**Base:** `develop` @ `bc88ab9` (Phase-2 waves 1–4 merged).
**Grounding:** PRD.md (§16, §17, §18, §23.5/D18, §9.0/D13, D16, D21), CLAUDE.md (§3, §8, §9, §12), ARCHITECTURE.md (§3, §7, §8, §10), ADR-0013 (cross-module integration), ADR-0014 (outbox), ADR-0017 (search), ADR-0019 (USSD area follow).

## Verdict: **PASS**

The codebase-completion wave is integrated and green. Every bare `// TODO(wiring)` marker in Java source is resolved (wired to a live `*Api` port / `domain.port` adapter / outbox event) or — where an external dependency is genuinely still absent — replaced with a precise, explicit CENTRAL-NEED note. The only literal `// TODO(wiring)` strings that survive anywhere in the tree are 6 historical comments inside three **applied, forward-only** SQL migrations that CLAUDE.md §12 forbids editing; the live application code that supersedes each is clean.

---

## 1. Build + test gates

| Gate | Command | Result |
|---|---|---|
| Backend package | `./mvnw -q -DskipTests package` | **GREEN** (exit 0) |
| Module boundary | `./mvnw -q test -Dtest=ModuleBoundaryTest` | **GREEN — 7/7** (`Tests run: 7, Failures: 0, Errors: 0`) |
| Engagement (changed module) | `EngagementSearchBackfillSourceTest, PetitionServiceTest, QuestionServiceTest, SurveyServiceTest` | **GREEN — 40/40** (9 + 19 + 5 + 7) |
| Backend compile after Javadoc reword | `./mvnw -q -o compile` | **GREEN** (exit 0) |
| web-admin | `npm run build` | **GREEN** (exit 0; only cosmetic SCSS `:where`/`form-floating` selector warnings) |

**Exact failures:** none.

`ModuleBoundaryTest` stays **7/7**. The two new cross-module edges this wave introduced both hold:
- **`SearchBackfillSource` impls use carve-out (b).** Engagement's `PetitionBackfillSource` / `SurveyBackfillSource` / `QuestionBackfillSource` (and the wave's reporting/responders/communications/institutions siblings) implement the search module's published `search.domain.port.SearchBackfillSource` and depend only on `search.api` (`SearchIndexApi`) + their own module internals — the sanctioned `domain.port` injection (same shape as `SmsGateway`/`Geocoder`), not a reach into another module's `domain`/`infrastructure`.
- **`accountability → institutions.api` ownership edge is `api → api` with no cycle.** `InstitutionsBackedOwnershipAdapter` (accountability `infrastructure.adapter`) depends only on `institutions.api.RepresentativeQueryApi#ownsRepresentative`. Institutions does not call accountability back — the edge is acyclic.

## 2. Completeness — `TODO(wiring)` census

Ripgrep over the **whole backend** (`backend/src/main` Java + SQL, `backend/src/test`):

| Location | Count | Status |
|---|---|---|
| `backend/src/main/java` | **0** | All resolved/reworded this wave. |
| `backend/src/test/java` | **0** | — |
| Other bare `TODO`/`FIXME` in Java main | **0** | — |
| `backend/src/main/resources/**.sql` | **6** | In **applied, forward-only** migrations V24, V46, V95 — immutable (CLAUDE.md §12). See below. |

**Final bare-wiring `TODO(wiring)` count in editable source: 0.**

### The 6 SQL survivors are immutable historical comments, NOT live wiring debt
Each is a design-note comment inside an already-applied migration whose live code has since superseded it. We do **not** edit applied migrations (forward-only rule, CLAUDE.md §12); rewording them would be a correctness/audit hazard, not a fix.

- **V24 `reporting_report.sql`** (`assigned_responder_id` STUB / routing deferred) → routing→OWNER assignment is now event-driven via the outbox (`REPORT_ROUTED` → responders routing worker), ADR-0013 §2 / ADR-0014. Column comment is stale-but-frozen.
- **V46 `accountability_rating.sql`** (electoral scope "documented `// TODO(wiring)`") → **electoral scope is enforced** in `RatingService.submit` (F1) via `RepresentativeQueryApi.constituencyOf` × `ElectoralScopeApi.isElectorOf`. Comment is stale-but-frozen.
- **V95 `ussd_alert_subscription.sql`** (`forwarded` → communications "// TODO(wiring)") → **fully wired** in `UssdAlertService` (port `UssdSubscriptionPort` → communications `AreaSubscriptionApi`, fail-soft, config-gated `taarifu.ussd.alerts.forward`). The single remaining genuine external/cross-module need — **account→profile grain resolution from identity** so the AREA follow keys at the correct grain — is an explicit documented CENTRAL NEED in the service Javadoc (ADR-0019 §1b), not a bare TODO. Default-OFF is the safe degradation (local intent always captured).

### The 1 former Java survivor — reworded this wave
`search/package-info.java:44` carried a literal `// TODO(wiring)` as a *section heading label* over items now DONE (producer calls, backfill adapters, security allow-list). Reworded to **"Cross-module wiring status (CENTRAL …)"** with the backfill-adapters bullet marked **DONE** (all owners shipped). No behavioural change; removes the false-positive that read as a defect.

## 3. Backfill safety (every `SearchBackfillSource`)

**PASS.** Engagement's three adapters (the wave's representative pattern, identical to the reporting/responders/communications/institutions siblings):
- **Reuse the live producer's fence — no parallel copy.** Each routes every row through the owner's own `reindexForDiscovery(...)` (now `public boolean`), the *exact* method the write path calls. The index-vs-no-index decision and the public projection cannot drift between live and backfill (DRY, ADR-0017 §1).
- **Visibility fence held — no private/anonymous/sensitive indexed.** Source query is `findByStatusIn(PUBLIC_STATUSES, …)` (DRAFT excluded); the shared fence re-screens per row (belt-and-braces) and **removes** (idempotent) any non-public row rather than indexing it; soft-deleted rows excluded by `@SQLRestriction`. Tests pin it: `petitionBackfill_neverIndexesADraft_andExcludesItFromCount`, `surveyBackfill_neverIndexesADraft`, and `questionBackfill_upsertsPublicQuestions_neverTheAskerId` (asserts `authoredByAccountId` is null + target-rep is the only facet). The questions-JSON / response payload never reaches a snippet/keyword field (`surveyBackfill_…_neverQuestionsJson`).
- **Idempotent.** Every push is `index.upsert` keyed on `(entityType, publicId)` inside the shared producer — a re-run lands the same rows in place, never a duplicate.
- **PII-free, batched, lean.** Paged at `BATCH_SIZE=200` ordered by primary key for a stable walk; logs counts only (verified in the test run output), never a title/snippet/id (PRD §18, §15).
- POLL correctly covers BOTH `SURVEY` and `POLL` `SurveyType`s under one adapter (one `Survey` aggregate → one `SearchEntityType.POLL`) — no double-registration, no gap.

## 4. Security fences

**PASS** on all four checked invariants.

- **Rep-reply ownership fence holds.** `RatingReplyService.replyAsRepresentative` gates on `ownershipPort.isLinkedAccountOf(author, representativeId)` — a rep replies only to a rating about **themselves**, never a rival (D16). The real `InstitutionsBackedOwnershipAdapter` (now the default `@Component`) delegates to `RepresentativeQueryApi.ownsRepresentative`, which **fails closed** (returns `false`, never throws, on null/unknown/unlinked/mismatch). The `DenyByDefaultOwnershipAdapter` backs off via `@ConditionalOnMissingBean` and only re-engages if the real adapter is ever absent — worst case is "self-reply temporarily unavailable", never "anyone speaks as a rep". Curated (on-behalf) reply is `@PreAuthorize("hasRole('ADMIN')")`. One-per-rating cap enforced by DB unique → clean `CONFLICT`. No token balance read (fence).
- **Identity `ProfileLookup` returns ZERO PII.** `ProfileLookupApi` exposes only public ids, public display name, and public trust-tier *name*; **no** national/voter id or blind index, **no** phone/email (contact PII has its own consent-fenced `RecipientContactApi`), no demographic. Deny-by-default `Optional.empty()` on unknown/null. The new `PetitionService.create` account→profile resolution uses it api→api (no identity `domain` import) and fails closed to `BAD_REQUEST` rather than storing an unattributable row.
- **Payments refund/void keep the D18 fence + are state-guarded.** `RefundService.refund` touches only the convenience wallet via `WalletReversalPort` (no binding-action module dependency — fence by construction); idempotent (REFUNDED → no-op), state-guarded (only SUCCEEDED refundable → else `CONFLICT`), reversal keyed deterministically per top-up for exactly-once. `voidTopUp` rejects any terminal/settled row (`CONFLICT`). Both require a non-blank machine reason; logs carry provider/amount only (no PII). Admin endpoints both `@PreAuthorize("hasRole('ADMIN')")`.

## 5. Migration collisions above V182

**PASS — none.** Highest version is **V182**; no duplicate version numbers anywhere; **no new migration** was added by the engagement contribution (its backfill is read-only over existing tables). The reserved block above V182 is untouched and free.

## 6. web-admin

**PASS.** `npm run build` → exit 0, bundle generated. Only two cosmetic Tailwind/Bootstrap SCSS selector warnings (`form-floating>~label`), no errors. The wave's payment detail view + refund/void operator actions (`3ef7adc`) compile and ship.

---

## Prioritized must-fix

**None blocking.** All gates green; no security or boundary regression.

### Track (P3 — not regressions, expected by ADR-0013 §2 / ADR-0019)
1. **USSD area-alert forward grain (CENTRAL NEED).** `taarifu.ussd.alerts.forward` stays default-OFF until identity exposes account→profile resolution so the communications AREA follow keys at the profile grain. Port + adapter + fail-soft path are live and tested; flipping the flag (passing a true profile id) is the one-line completion. Owner: identity + ussd eng. The local intent path is unaffected.
2. **Outbox-driven event wirings** (routing→OWNER assignment D21, fan-out, moderation takedown, async token rewards, identity funnel analytics emission) remain event-driven per ADR-0013 §2 — the substrate (ADR-0014) is built; per-handler emission lands as independent increments. Correct-but-empty until then; not defects.
3. **Frozen SQL design-note comments** (V24/V46/V95) read as `// TODO(wiring)` but are immutable historical text whose live code is wired. Optional cosmetic follow-up: a forward `chore` could add a superseding `COMMENT ON` in a *new* migration if the audit noise is judged worth the migration. Low priority.

## Final `TODO(wiring)` count

- **Editable source (Java main + test): 0.**
- **Immutable applied SQL migrations: 6** (V24×2, V46×1, V95×3) — frozen historical comments, live code superseded, not actionable under the forward-only rule.
