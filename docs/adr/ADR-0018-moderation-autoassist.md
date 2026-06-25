# ADR-0018: Swahili-aware moderation auto-assist scorer (hold-for-review only) + PII-free transparency report — the automated half of the hybrid model (D-Q8) behind a pluggable `ContentSafety` port, and the §25 transparency read surface

**Status:** Accepted · 2026-06-25 · Trust & Safety / Content Moderation Lead (Zainab Ramadhani)
**Extends:** the existing moderation increment (V40–V42, V100: `Flag` / `ModerationItem` / `ModerationAction` / `Appeal` + the D16 self-action and appeal-independence fences), ADR-0004 (ports & adapters), ADR-0013 (cross-module integration — analytics is event-driven via the outbox), ADR-0014 (transactional outbox + in-process bus — the substrate the auto-triage analytics fact rides). ADR-0008 (single envelope). It realises **US-12.3** (auto-assist, P2), **UC-H05** (auto-moderation triage), and the **§25 / M-Phase 3 transparency report** in the moderation module only.
**Grounding:** PRD §12 (US-12.1/12.2/**12.3**), §18 (content safety; immutable audit/transparency), **§25.8** (severity SLAs; queues prioritised by severity), **§25.3** (sensitive/anonymous reports — stricter pre-routing triage hold), D-Q8 (hybrid moderation: community flagging + in-house moderators + auto-assist), **EI-18** (`ContentSafety` port; degradation → all to human moderators), **R20/R21/R22** (harmful content at scale; Swahili moderation gap; moderation can't keep pace), Appendix E events `auto_moderation_triaged` and `moderation_action_taken.was_auto_assisted`. ARCHITECTURE.md §3.2/§3.3 (boundaries, internal layering), §7 (ports/adapters + stub + degradation), §4.1 (Flyway range-partitioned per module — moderation owns `V8xx`/late blocks). CLAUDE.md §3 (SOLID/KISS/fail-safe), §8 (docs + no PII in logs), §12 (guardrails).

## Context

The moderation spine exists: citizens flag → severity-prioritised `ModerationItem` queue → append-only `ModerationAction` → independent `Appeal`. Two pieces of the **hybrid model (D-Q8)** are still unbuilt:

1. **The automated half (US-12.3, P2).** D-Q8 is explicitly *hybrid*: community flagging **plus** in-house moderators **plus** auto-assist. Today nothing screens content before a human or a flagger looks at it. R20 (harmful content at scale), R22 (moderation can't keep pace), and the §25.3 requirement that **all sensitive reports enter a triage hold before routing** all need an automated screen that **raises and prioritises** risky content — never one that silences it. EI-18 fixes the contract: a `ContentSafety` port emitting **risk scores/labels**, holding for review, and **degrading to "everything routes to human moderators"** when the provider is down.

2. **Transparency reporting (§25, M-Phase 3).** §18/§25 commit the platform to a published transparency report on moderation volumes, action mix, and appeal outcomes, keeping the abuse-report rate under the <2% KPI. The moderation tables hold the source rows; there is no read surface that aggregates them **PII-free**.

Five forces shape the decision — and one is non-negotiable in my lane:

- **Safety beats throughput, and a classifier must never auto-remove borderline content (R21, D-Q8).** Swahili (and Sheng / Coastal / Zanzibari registers / code-switching) defeats naive classifiers. Auto-assist is **assist only, human-in-the-loop**: it may *hold for review* and *raise severity*, it may **never** approve, hide, remove, warn, or suspend. The human pipeline is **always the floor**.
- **EI-18 degradation is the default, not the exception.** With no real classifier configured (MVP/most environments), the system must boot and run with **zero external calls** and route everything to humans — exactly the stub/degradation discipline ARCHITECTURE §7 already uses for `SmsGateway`/`PushSender`.
- **No PII in scores, events, logs, or the transparency report (PRD §18, PDPA, §25.3).** The scorer reads content text only inside the request that already holds it; it persists **labels and confidences, never the text**. The `auto_moderation_triaged` analytics fact and the transparency report are **counts/codes only**.
- **The boundary holds (ADR-0013).** Auto-assist lives entirely inside `com.taarifu.moderation`. It touches no sibling's `domain`/`infrastructure`; the analytics fact rides the outbox via the existing `CivicActivityRecorded` contract.
- **One source of truth for severity (DRY).** Auto-assist must reuse the existing `SeverityPolicy` / `ModerationSeverity` SLA chain (§25.8), not invent a parallel one.

## Decision

Add, **in the moderation module only**, (1) a pluggable `ContentSafety` port + a conservative **Swahili+English heuristic scorer** adapter that **holds-and-prioritises** risky content without ever removing it, wired through an `AutoAssistService`; and (2) a **read-only, PII-free transparency report** service + endpoint aggregating the moderation tables. Migration **V154** adds the auto-assist columns to `moderation_item`; no other schema is needed (V155/V156 reserved, unused).

### 1. `ContentSafety` port (EI-18) — `moderation.domain.port.ContentSafety`

A plain interface (no vendor imports — ArchUnit `domainPortsHaveNoVendorImports` stays GREEN):

```java
ContentSafetyResult score(ContentSafetyRequest request);
```

- **`ContentSafetyRequest`** = `(FlagSubjectType subjectType, UUID subjectId, String text, String languageHint)`. The `text` is the content body, passed **transiently** (request-scoped) — it is never persisted by the scorer and never logged.
- **`ContentSafetyResult`** = `(List<SafetySignal> signals, boolean recommendHold)`, where `SafetySignal = (ContentSignal signal, double confidence)`. The port returns **labels + confidences only** (EI-18 "In: risk scores/labels"); the decision to *hold* is the caller's policy, but the port offers `recommendHold` as the provider's own conservative recommendation.
- **`ContentSignal`** enum mirrors the Appendix E `auto_moderation_triaged.signal` vocabulary exactly: `PROFANITY`, `PII`, `SPAM`, `IMAGE`. (GBV-sensitivity surfaces as `PROFANITY`/`PII` signals at high confidence for the §25.3 path; a dedicated `GBV` signal is a documented additive follow-up, not this increment — keep the published analytics vocabulary stable.)
- **Degradation (EI-18):** when no real provider is configured, the **stub adapter returns an empty result** (`signals=[]`, `recommendHold=false`) — i.e. *no auto-assist*, so **everything falls through to the human pipeline + community flagging**. That is the floor, by construction.

### 2. `HeuristicContentSafetyScorer` adapter (Swahili-aware, conservative — R21)

`moderation.infrastructure.adapter.HeuristicContentSafetyScorer implements ContentSafety`, `@ConditionalOnProperty(name="taarifu.moderation.content-safety.provider", havingValue="heuristic", matchIfMissing=true)` — the **match-if-missing default** so every environment has exactly one `ContentSafety` bean and boots with **zero external calls** (the `LoggingSmsGatewayStub` pattern). A real ML/hosted adapter (`provider=ml`) swaps in later behind the same port with no caller change.

- **Curated SW+EN lexicons** for PROFANITY/SPAM, plus **regex rules** for PII (Tanzanian phone MSISDN `+255…`/`07…`, NIDA-shaped 20-digit IDs, email) — the `PII`/doxxing screen R20 calls for. Lexicons are **normalised** (lower-cased, diacritic-folded, elongation-collapsed e.g. `mjingaaaa`→`mjinga`) so common Swahili evasion spellings and code-switching do not slip the screen.
- **Conservative thresholds (R21):** signals carry a confidence in `[0,1]`; the **hold threshold is configurable** (`taarifu.moderation.content-safety.hold-threshold`, default `0.80`). Below threshold → **no hold** (a human/flagger still can). Above → **hold for review only**. The scorer **never** maps to a removal — it has no path to a `ModerationAction`.
- It is a **heuristic, explicitly labelled as assist** — its output is advisory input to a moderator, recorded as such, and always overridable. No PII or content text is logged; only signal counts at debug.

### 3. `AutoAssistService` — hold-and-prioritise, never remove (US-12.3 / UC-H05)

`moderation.application.service.AutoAssistService` exposes `triage(subjectType, subjectId, subjectAuthorAccountId, text, languageHint)` (called by content-owning modules' wiring, or by the §25.3 sensitive-report pre-routing hold — both **`// TODO(wiring)`** today, since the owners do not yet call in). Inside one `@Transactional`:

1. `ContentSafetyResult r = contentSafety.score(...)`.
2. If `r.signals()` is empty / below the configured hold threshold → **return without holding** (the human pipeline floor; nothing is auto-actioned).
3. Otherwise: **open or escalate** the subject's `ModerationItem` (reusing the existing one-live-item-per-subject collapse and `SeverityPolicy` SLA chain — §25.8), mark it **auto-assisted** with the **top signal + confidence**, and set status so it **surfaces for a human** (PENDING). It records **nothing that removes content**. The new entity fields (`autoAssisted`, `autoSignal`, `autoConfidence`) feed `moderation_action_taken.was_auto_assisted` later (a moderator actioning an auto-held item carries the flag) and the auto-vs-manual transparency split.
4. **Analytics (Appendix E, M15; ADR-0013 §2):** append an `auto_moderation_triaged` `CivicActivityRecorded` fact to the **outbox in this transaction** — `analyticsEventType=AUTO_MODERATION_TRIAGED`, `outcome=<top signal>`, plus the held boolean encoded via the activity (held vs not). **Ids/codes only — no text, no author identity** (PRD §18). This drives the auto-vs-manual KPI split.

> **The fence is structural, not just documented:** `AutoAssistService` depends on the `ContentSafety` port, the item/flag repositories, `SeverityPolicy`, the clock, and the `OutboxWriter` — and on **nothing that can remove content**. It cannot write a `ModerationAction`; only a human via `ModerationQueueService.takeAction` (with the D16 guard) can. This is the R21 / D-Q8 "never auto-remove borderline content" guarantee enforced by construction.

### 4. PII-free transparency report (§25, M-Phase 3)

`moderation.application.service.TransparencyReportService` (`@Transactional(readOnly = true)`) aggregates the moderation tables over a window (default last 30 days, the analytics-service convention) into a `TransparencyReportDto`:

- **Action mix** — counts of `ModerationAction` by `type` (APPROVE/HIDE/REMOVE/WARN/SUSPEND/VERIFY_REQUEST).
- **Appeal outcomes** — counts of `Appeal` by `status` (OPEN/UPHELD/OVERTURNED).
- **Flag volume by reason** — counts of `Flag` by `reason` (the abuse-report-rate numerator).
- **Auto-vs-manual split** — counts of `ModerationItem` by `autoAssisted` (the US-12.3 KPI).

Every figure is an **aggregate count keyed on a code/enum** — no subject id, no author, no flagger, no content, no moderator identity (data minimisation, §18; the report is publishable per M-Phase 3). The endpoint `GET /moderation/transparency` is `@PreAuthorize`-gated to `hasAnyRole('ADMIN','ROOT','MODERATOR')` (staff read; the *public* P3 publication is a downstream export of this same PII-free aggregate, not a new query).

> **WHY read the operational tables, not the analytics read model:** the transparency report is a **moderation-owned** accountability artefact over moderation's own append-only tables (`moderation_action` is immutable — V41 — so the counts are tamper-evident). Reading analytics would couple moderation to a sibling for its own published numbers; the operational tables are the authoritative source here. The analytics dashboards (M15) remain the cross-cutting KPI lens; this is the §25 transparency artefact.

### 5. Migration V154 (moderation block) + analytics catalogue key

- **`V154__moderation_item_auto_assist.sql`** adds nullable columns to `moderation_item`: `auto_assisted BOOLEAN NOT NULL DEFAULT FALSE`, `auto_signal VARCHAR(16)` (CHECK in the `ContentSignal` set), `auto_confidence DOUBLE PRECISION`. Forward-only, SQL-commented, `ddl-auto=validate` (the entity gains matching fields). V155/V156 reserved, unused this increment.
- **`AnalyticsEventTypes.AUTO_MODERATION_TRIAGED`** string constant added (additive, Appendix E.0 forward-compatible — an unknown catalogue value is dropped as a no-op by the analytics handler, so this lands safely even before the analytics enum gains the value).

## Consequences

- (+) **The hybrid model (D-Q8) is complete and safe:** auto-assist *raises and prioritises*, the human pipeline is the floor, and **borderline Swahili content is never auto-removed** (R21). With no provider configured the system degrades to all-human by construction (EI-18) and boots with zero external calls.
- (+) **§25 transparency is real and PII-free:** one read surface over moderation's own immutable/append-only tables, publishable as the M-Phase 3 report, exposing action mix, appeal outcomes, flag-by-reason, and the auto-vs-manual split — no person, location, or content body anywhere.
- (+) **Boundary + fences preserved:** everything lives in `com.taarifu.moderation`; the analytics fact rides the existing outbox contract; auto-assist structurally **cannot** take a removal action (only the D16-guarded human path can); ArchUnit `ModuleBoundaryTest` stays GREEN.
- (+) **Pluggable:** a real Swahili classifier swaps in behind `ContentSafety` (`provider=ml`) with no caller change; the heuristic stays as the safe default/fallback.
- (−) **A heuristic scorer has limited recall** and will miss novel Sheng/dialect abuse. Accepted and deliberate: it is *assist*, conservative-by-threshold, with the human pipeline as the floor — better a missed auto-hold (a human/flagger still catches it) than an unjust auto-removal (R21). Recall improves when the ML adapter lands.
- (−) **Owner modules must call `AutoAssistService.triage(...)`** to screen content on create — left as `// TODO(wiring)` until those modules publish/consume the hook (the same deferral discipline as the existing routing/takedown wirings, ADR-0013 §2). The transparency report and the port/adapter/policy are fully live now.

### Wiring update (2026-06-25) — triage is live on the flag path

`AutoAssistService.triage(...)` is no longer call-site-less: it is now invoked **inside `FlagService.flag`** so that when a citizen flag raises/escalates a `ModerationItem`, the flagged content is also screened and the item is prioritised by what it actually contains (not only the flag reason). To stay boundary-safe (moderation never imports a content owner — it holds only `(subjectType, subjectId)`), a new per-owner published port **`moderation.api.SubjectContentQueryApi`** (mirroring `SubjectAuthorQueryApi`) surfaces the subject's **transient scorable text**; a registry **`SubjectContentResolver`** dispatches by `FlagSubjectType`. When no owner publishes a content port for the type (the launch reality) or the scorer is the degraded stub, the screen is a **no-op** and the flagged item still goes to a human (EI-18 floor) — the flag is **never** blocked or failed by auto-assist, and the transient text is never persisted or logged by moderation (PRD §18, PDPA). The screen remains **assist only** (it joins the flag transaction and can only `markAutoAssisted`/escalate — never action), so the auto-vs-manual transparency split now reflects **real** triaged items. Screening on content *create* (before any flag) and the §25.3 sensitive-report pre-routing hold remain `// TODO(wiring)` for those owners to call in. No new migration: V154's `auto_assisted`/`auto_signal`/`auto_confidence` columns are exactly what this populates.
- **Revisit triggers:** (a) the **ML/hosted `ContentSafety` adapter** lands (EI-18 P2 full) → select `provider=ml`, heuristic becomes fallback; (b) a dedicated **`GBV` / `IMAGE` (vision)** signal is needed → add the `ContentSignal` value + analytics catalogue value (additive); (c) the **public P3 transparency publication** ships → export this PII-free aggregate on a schedule; (d) auto-assist volume needs **its own queue lane / virality ordering** → extend the queue ordering, item shape unchanged.

## Decision summary

- **`ContentSafety` port** (`moderation.domain.port`) returns **labels + confidences only** (EI-18); **`HeuristicContentSafetyScorer`** adapter is the Swahili+English, diacritic/elongation-normalised, conservative-threshold, **match-if-missing default** (zero external calls); a real ML adapter swaps in behind it.
- **`AutoAssistService.triage(...)`** **holds-and-prioritises** risky content (open/escalate the existing `ModerationItem` via `SeverityPolicy`, mark auto-assisted + top signal/confidence) and emits an `auto_moderation_triaged` fact on the outbox — **never auto-removes** (R21/D-Q8; the human D16-guarded path is the only one that can action). Degradation = all-to-human (EI-18).
- **`TransparencyReportService` + `GET /moderation/transparency`** (`ADMIN`/`ROOT`/`MODERATOR`) aggregate moderation's own tables **PII-free** into action mix / appeal outcomes / flag-by-reason / auto-vs-manual split (§25, M-Phase 3).
- **Migration V154** adds `auto_assisted`/`auto_signal`/`auto_confidence` to `moderation_item` (`ddl-auto=validate`); `AnalyticsEventTypes.AUTO_MODERATION_TRIAGED` added (additive). V155/V156 reserved.
