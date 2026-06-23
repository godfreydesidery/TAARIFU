# Wiring Civic-Correctness Review — Electoral Scope, Report Routing, Swahili i18n

> **Reviewer:** Tanzania Civic & Governance Domain Expert (tanzania-domain-expert)
> **Branch:** `develop` (integrated backend, 12 modules)
> **Date:** 2026-06-23
> **Scope:** civic correctness of (1) electoral-scope enforcement on binding actions, (2) report routing to the responsible office/responder, (3) the Swahili i18n strings just added.
> **Grounding:** PRD §9.0 (location & electoral model), §10/§12 (epics), §23.5 (integrity fence), §25.2/§25.4 (routing fallback, `isElectoral` edge cases), D3/D12/D13/D18; ARCHITECTURE §3.2 (boundaries), §6.2 (authz); CLAUDE.md §8/§12.
> **Verdict:** the fence architecture is sound and well-documented. **One CRITICAL civic-correctness gap** (councillor/Diwani electoral scope) and **one HIGH i18n defect** (missing message key) must be fixed. Report routing is *deliberately deferred* and acceptable as-is, with caveats.

---

## Summary table

| # | Severity | Area | Finding | Fix owner |
|---|---|---|---|---|
| F1 | **CRITICAL** | Electoral scope | Councillor (Diwani) binding actions have **no electoral gate** — a citizen anywhere in Tanzania can rate / sign-a-petition-against any councillor. Wiring only checks *constituency*; a councillor holds a *Ward (Kata)*, so the gate is silently skipped. | backend (engagement, accountability) + identity + institutions |
| F2 | **HIGH** | Swahili i18n | Message key `reporting.report.resolutionRequired` is thrown in code but **absent from both** `messages_sw.properties` and `messages_en.properties` → the citizen sees the raw key string, not Swahili. **(Fixed in this pass — see F2.)** | content/i18n |
| F3 | MEDIUM | Swahili copy | Report timeline strings interpolate **English enum tokens** inside Swahili sentences (`"Ripoti imepokelewa (NEW)"`, `"(NEW → ASSIGNED)"`). A low-literacy Swahili citizen sees `NEW`, `ASSIGNED`, `ESCALATED`. | content/i18n + backend |
| F4 | MEDIUM | Report routing | Routing to a responder OWNER is `TODO(wiring)` — every report stays `NEW`, unrouted. Acceptable *only* if the §25.2 "no responder yet" citizen message and the §25.4 staged-onboarding fallback are honoured when routing lands. | backend (responders increment) |
| F5 | LOW | Electoral scope | `OFFICE`-targeted petitions carry **no area gate at all** (any citizen nationwide can sign a petition against any council/ministry). Confirm this is the intended policy for office petitions vs. constituency petitions. | product + tanzania-domain-expert |

---

## F1 — CRITICAL: Councillor (Diwani) binding actions are not electoral-scoped

### What the PRD requires
The PRD is explicit and repeated: the electoral mapping is **two-tier**.

- §9.0 / §9 ERD / D-glossary: **"Constituency → MP (Mbunge); Ward → Councillor (Diwani)."** (PRD lines 303, 309, 336, 751, 886.)
- §17 glossary, line 748: *"the administrative chain (Region→…→Ward) drives services/officials/**councillor** & report routing; the electoral mapping (Ward→Constituency) drives **MP** representation."*
- D13 (line 727): binding actions are *"scoped to the single electoral location"* — and that location is a **Ward**, which resolves to **both** a councillor (directly) and an MP (via Ward→Constituency).

So the correct rule is:
- **Rating / petitioning an MP** → gate on **constituency** (signer's `isElectoral` ward must map to the MP's constituency).
- **Rating / petitioning a Councillor** → gate on **Ward** (signer's `isElectoral` ward must equal the councillor's ward).

### What the code actually does
The binding-action fence resolves scope through **constituency only**:

- `RepresentativeQueryService.constituencyOf(...)` (institutions) returns `Optional.empty()` for a `COUNCILLOR_WARD` mandate — see the entity rule in `Representative` (constituency null, ward populated for councillors).
- `RatingService.submit(...)` (accountability) and `PetitionService.sign(...)` (engagement) both do:
  ```
  Optional<UUID> repConstituency = representativeQueryApi.constituencyOf(subjectId);
  if (repConstituency.isPresent() && !electoralScopeApi.isElectorOf(rater, repConstituency.get())) {
      throw OUT_OF_SCOPE;
  }
  ```
  When `constituencyOf` is **empty** (every councillor), the `if` is false → **the electoral gate is skipped entirely.**

The Javadoc even states this as if intentional: *"A representative with no constituency (councillor/special-seats/nominated) carries no constituency electoral gate, so this check is skipped for them."* That reasoning is **correct for special-seats / nominated MPs** (Viti Maalum and `NOMINATED` genuinely have no geographic seat — PRD §22.6) but **wrong for councillors**, who very much have a geographic seat: their **Ward**.

### Civic impact
- A citizen registered in Mtwara can rate or sign a petition against a councillor in Kinondoni. This is exactly the **ratings-brigading / coordinated-petition** abuse vector the fence exists to stop (PRD §25.7, line 1107: *"T3 + one-per-person + electoral scope"*).
- It breaks the core promise that *"a citizen rates/petitions only in their registered [electoral area]"* — and councillors are the leaders citizens deal with most (D3, PRD line 276).
- It is **politically sensitive**: ungated councillor ratings invite accusations that Taarifu enables out-of-area brigading of local politicians, precisely the partisan-misuse perception we must avoid.

### Required fix
The fix is a cross-module wiring change. **Do not** quietly fold ward into the constituency port — keep the semantics honest.

1. **institutions** — add a ward-resolution method to the published port (mirror of `constituencyOf`):
   ```java
   // RepresentativeQueryApi
   /** The ward (Kata) for a COUNCILLOR_WARD / ward-exec seat; empty for constituency/special-seats/nominated. */
   Optional<UUID> wardOf(UUID representativePublicId);
   ```
   Implement in `RepresentativeQueryService` from `rep.getWard()` (the FK already exists on `Representative`).

2. **identity** — add a ward-level elector check to the published port (the data is already there: `ProfileLocation.getWard()` on the single `isElectoral` location):
   ```java
   // ElectoralScopeApi
   /** True iff the user's single isElectoral location is in this ward (D13, councillor scope). */
   boolean isElectorOfWard(UUID userPublicId, UUID wardPublicId);
   ```
   Implement in `ElectoralScopeService` exactly like `isElectorOf`, comparing `electoral.getWard().getPublicId()` instead of the constituency. Keep deny-by-default on every missing link, and **keep the fence clean** (no token collaborator).

3. **engagement (`PetitionService.sign`) and accountability (`RatingService.submit`)** — resolve the gate by mandate, not by "is constituency present":
   - If the subject rep has a **constituency** → check `isElectorOf(constituency)`.
   - Else if the subject rep has a **ward** → check `isElectorOfWard(ward)`.
   - Else (genuinely seat-less: special-seats / nominated) → no geographic gate (current behaviour, correct).
   Audit the denial with the existing `AUTHZ_SCOPE_DENIED` type and reason `..._OUT_OF_ELECTORAL_SCOPE` (the petition path already does this).

4. **Tests** — add the missing cases:
   - elector-of-ward **can** rate/sign for their councillor;
   - out-of-ward citizen is **`OUT_OF_SCOPE`** for a councillor (the test that would currently fail to exist);
   - special-seats / nominated MP remains ungated (regression guard).

5. **Boundary check** — both new methods live on the existing `api`-package ports, so ArchUnit `ModuleBoundaryTest` stays GREEN (no new cross-module internal import). Migration not required (no schema change) — but **reserve V50+** if any later increment needs a backing index on `ward_id` for this lookup.

> NOTE: This is a wiring/logic fix only; it does **not** redesign a locked decision — it *implements* D13 correctly. No ADR-superseding note needed. Flag to `security-privacy-engineer` because it touches a binding-action authorization path.

---

## F2 — HIGH (FIXED): missing Swahili/English message key `reporting.report.resolutionRequired`

`ReportService.resolve(...)` throws:
```java
throw new ApiException(ErrorCode.BAD_REQUEST, "reporting.report.resolutionRequired");
```
but the key exists in **neither** `messages_sw.properties` nor `messages_en.properties`. With Swahili as the default locale (ADR-0010), a responder who resolves a case with a blank note would receive the **literal string `reporting.report.resolutionRequired`** as the user-facing `message` — broken Swahili, exactly the machine-translated-stiffness failure we guard against.

**Fixed in this pass.** Added to both files:
- `messages_sw.properties`: `reporting.report.resolutionRequired=Maelezo ya utatuzi yanahitajika`
- `messages_en.properties`: `reporting.report.resolutionRequired=A resolution note is required`

(Placed in the reporting block, adjacent to `reporting.report.notResolved`, so the file stays organised.)

> Recommend a small unit/contract test asserting **every** key referenced via `new ApiException(..., "<key>")` resolves in both bundles — this class of defect (key drift) is cheap to catch mechanically. All *other* reporting keys referenced in code (`category.inactive`, `report.anonymousNotAllowed`, `report.notResolved`, `report.illegalTransition`, `report.badVisibility`, `category.duplicateCode`, `category.badRoutingLevel`, `category.badVisibility`) were verified present in both bundles.

---

## F3 — MEDIUM: English enum tokens leak into Swahili citizen-facing timeline text

`ReportService` builds timeline messages by interpolating the raw `ReportStatus` enum names into otherwise-Swahili sentences:

| Code | What the citizen sees |
|---|---|
| `"Ripoti imepokelewa (NEW)"` | `Ripoti imepokelewa (NEW)` |
| `assign(...)` → `"Imepangiwa mtekelezaji (%s → %s)"` | `Imepangiwa mtekelezaji (NEW → ASSIGNED)` |
| `start/resolve/escalate` | `... (IN_PROGRESS → RESOLVED)`, `Imepandishwa ngazi (ASSIGNED → ESCALATED)` |
| `confirmResolution(...)` | `RESOLVED → CLOSED` (bare enums, no Swahili wrapper) |

These strings are persisted as `CaseEvent` messages and surfaced on the citizen's tracking timeline (US-3.2) and the **public** report timeline (US-3.7). A low-literacy Swahili user (PRD §14, §15) cannot read `NEW`, `AWAITING_INFO`, `ESCALATED`.

**Recommended fix:** map each `ReportStatus` to a Swahili label and build the timeline copy from message keys, not enum `.name()`. Suggested labels (for the content team to confirm):

| Status | Swahili label |
|---|---|
| NEW | Imepokelewa |
| ASSIGNED | Imepangiwa mtekelezaji |
| IN_PROGRESS | Inashughulikiwa |
| AWAITING_INFO | Inasubiri maelezo zaidi |
| RESOLVED | Imetatuliwa |
| CLOSED | Imefungwa |
| REOPENED | Imefunguliwa upya |
| ESCALATED | Imepandishwa ngazi |

Then e.g. `confirmResolution` reads `"Imetatuliwa → Imefungwa"` rather than `RESOLVED → CLOSED`. Keep the machine `status` field in the DTO (English enum) for clients; localise only the **human message**. This is presentation, not schema — no migration. (Lower priority than F1/F2 because it degrades clarity, not correctness or integrity.)

---

## F4 — MEDIUM: report routing to a responsible office is deferred (acceptable, with conditions)

The routing layer is **civically well-designed** and I have no correctness objection to its *shape*:
- `RoutingLevel` is an **abstraction over agencies** (WARD / MTAA_VILLAGE / COUNCIL / DISTRICT / REGION / SECTOR_UTILITY / OVERSIGHT), not hardcoded agency names — correct per Appendix D.1, and correctly anticipates region-by-region onboarding (D-Q5).
- The Swahili-facing examples (TANESCO, DAWASA/RUWASA, TARURA/TANROADS, NEMC, PCCB/TAKUKURU, TPF, CHRAGG) are accurate Tanzanian institutions.
- `IssueCategory.defaultRoutingLevel` carries the seed default; the per-area token→responder-scope matrix is explicitly a later (responders) increment.

**The gap:** `ReportService.fileReport(...)` ends with `// TODO(wiring): route to a responder OWNER (D21)...` — so today **every report is created `NEW` and never assigned**. That is a documented, intentional scaffold, not a bug. But when routing is wired, it **must** honour:
1. **§25.2 routing fallback** — if no responder is onboarded for the (area × token), escalate **up the chain to the Council equivalent** and **flag Admin**; never silently drop. The report must surface the §25.2 *"no responder yet"* citizen message, not a dead end (PRD §25.6 "File report → no responder → §25.2 message").
2. **Administrative-vs-electoral split** — routing keys off the **administrative chain (Ward→Council)**, NOT the electoral constituency (PRD line 748). Confirm the eventual `RoutingRule` resolves on the report's **ward/admin ancestors**, and does **not** accidentally reuse the constituency snapshot stored on the report.
3. The report row already snapshots both `wardPublicId` and `constituencyPublicId` (good — keeps re-delimitation history honest); routing should read the **ward**, accountability/representation reads the **constituency**.

No fix required *now*; this is a forward-looking acceptance note for the responders increment so the deferral does not harden into a wrong design.

---

## F5 — LOW: confirm the policy for `OFFICE`-targeted petitions

A petition with `targetType = OFFICE` (council, ministry, agency) carries **no geographic gate** in `PetitionService.sign(...)` — only `REPRESENTATIVE` targets are scoped. So any T3 citizen nationwide can sign a petition against any office.

This may be **intended** (a national ministry petition is legitimately national; a citizen of any area has standing to petition NEMC or a national agency). But a petition against a **specific Council (Halmashauri)** or a **ward office** is arguably local — and an out-of-area signer there is the same brigading risk as F1. The PRD's binding-action language (D13) names *"sign constituency petition"* specifically, which suggests office petitions were not fully specified for area scope.

**Action:** product + tanzania-domain-expert to decide whether `OFFICE` petitions need an **area-scoped variant** (office bound to a Council/Ward → signer must be in that admin area) vs. **national** (ministry/agency → open). If area-scoping is wanted, it reuses the same `isElectorOfWard` / an analogous admin-area check from F1. No code change until the policy is decided.

---

## What is correct and should not change

- The **integrity fence** is faithfully implemented: `RatingService` and `PetitionService` inject **no token collaborator**, the rater/signer identity is taken from `CurrentUser` (never the body), one-per-person is enforced by a DB unique with the service pre-check, and tier is gated by `@RequiresTier("T3")` on the controller (live-resolved). This matches PRD §23.5 / §9.5 line 980 exactly. **Keep it.**
- **Special-seats (Viti Maalum) and nominated MPs correctly carry no geographic gate** — that is right (PRD §22.6); F1 must not regress this.
- **Self-action (D13/D16)** blocks (rep rating/petitioning their own record) are present and audited.
- **Report privacy**: ownership mismatch returns 404 (never reveals another citizen's report exists), sensitive categories force PRIVATE + permit anonymity with no reporter linkage, public reads use the PII-free `PublicReportDto`. Civically and PDPA-correct (PRD §25.3, §18).
- The **administrative vs electoral snapshot** on `Report` (both `wardPublicId` and `constituencyPublicId` stored) is the right modelling for re-delimitation history (PRD §25.4 line 1082).
- `RoutingLevel` taxonomy and its Tanzanian agency examples are accurate.

---

## Recommended order of work
1. **F2** — done (i18n key added).
2. **F1** — the only correctness/integrity gap; needs security-privacy-engineer sign-off (binding-action path). Cross-module wiring + tests.
3. **F5** — product decision; may be a no-op or fold into F1's ward/admin-area check.
4. **F3** — content team Swahili labels + backend message-key refactor.
5. **F4** — forward note for the responders/routing increment (no action now).

> ArchUnit `ModuleBoundaryTest` stays GREEN for all proposed fixes (new methods on existing `api` ports; no internal cross-module imports). No Flyway migration is required for F1–F3; reserve **V50+** if F1's ward lookup later wants a dedicated index. Nothing here redesigns a locked decision — F1 *implements* D13 as written.
