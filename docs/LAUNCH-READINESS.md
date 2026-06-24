# Taarifu — M-MVP Launch-Readiness Checklist & Go/No-Go

> **Owner:** Asha Mwakyusa (Delivery & Project Management)
> **Gate:** **M-MVP** — first live pilot region (PRD §27.1 / §27.5)
> **Status:** **NO-GO (conditional)** — engineering spine is materially complete and running; the gate is held by **emission/observability of KPIs, production secrets + hosting sign-off, and the two ops onboarding programs (D3/D5)**, not by missing software.
> **Grounding:** PRD §3.3 (KPIs), §8 (scope/MVP DoD), §15 (NFRs), §18 (security/privacy), §19 (decisions), §26 (risks R1–R34), §27 (rollout, milestones, launch-readiness). CLAUDE.md §9 (Definition of Done).
> **Cross-references:** [`docs/reviews/security-foundation-review.md`](reviews/security-foundation-review.md) (threat-model reference — MF-1..3, S-1..5, L-1..3); [`docs/reviews/wiring-civic-review.md`](reviews/wiring-civic-review.md) (civic-readiness — F1 councillor electoral scope, F2..F5); [`docs/reviews/geography-civic-review.md`](reviews/geography-civic-review.md) (seed correctness).

---

## 1. Bottom line (read first)

The platform is **not** the unbuilt "reference catalogue + auth shell" the four prior repos left (SYNOPSIS §2). The clean rewrite has a **running, secured modular monolith spanning 16 modules**, both client apps scaffolded, the TZ geography/electoral seed loaded, and CI gating build→test→SAST→container-scan. **The software is far closer to MVP than the program is.**

Per PRD §26 and §27, the decisive M-MVP risks are **program and adoption (R1–R3, R6–R8, R24)** — won or lost region by region in partnerships and operations. **"Region" is the unit of launch readiness, not "feature complete."** We will not open citizen reporting in any area before its officials are live and its leaders onboarded.

**The single most important things to unblock, in order:**
1. **Wire analytics emission from the civic flows** (recorder exists, nothing emits) — without it the M-MVP KPI exit criteria (TTFR/TTR/% resolved/verification funnel, §3.3) **cannot be measured**, so the gate is literally unverifiable. **Owner: Eng.**
2. **Production secrets + in-country hosting decision (D-Q9) + PDPA sign-off** — real adapter credentials, KMS for ID encryption, residency confirmed with Legal. **Owner: SRE + Legal.**
3. **Pilot region "live" (D3 leaders + D5 agencies onboarded & scoped)** — the gating ops programs; no amount of code substitutes for a real Area Official answering a real report. **Owner: Program.**
4. **Civic-correctness fix F1** (councillor/Diwani binding actions are not electoral-scoped) — a real integrity bug on a binding-action path. **Owner: Eng + Security.**

---

## 2. Inventory — BUILT vs REMAINING for M-MVP

### 2.1 BUILT (verified in repo, `develop`)

| Area | Evidence | Maps to |
|---|---|---|
| **Modular monolith, 16 modules** | `geography, identity, institutions, reporting, communications, tokens, responders, engagement, moderation, accountability, media, ussd, analytics, admin, common` (+ package-by-feature, ArchUnit boundary tests) | §8 modules; ADR-0002/0003 |
| **Tiered identity + auth** | `AuthController, ProfileController, ProfileLocationController, VerificationController, VerificationReviewController, MfaController`; OTP signup→T1, profile→T2, operator-assisted ID→T3; single-account/additive-roles | M0; D-Q2, D11–D16 |
| **Security foundation** | `@EnableMethodSecurity` ON, deny-by-default, allow-listed CORS, no secrets in source, BCrypt, rotating refresh + reuse-detection schema, **field-level AES-GCM encryption of `Profile.idNo` + HMAC blind-index dedup**, staff TOTP MFA | §18; security review "what is already correct" |
| **Geography + electoral seed** | `V71..V83` migrations: regions, districts, Dar/Kilimanjaro councils, wards, **effective-dated `WardConstituency`**, parties, parliament, issue categories + subcategories; closure table | M1; D-Q6, D14; EI-14 |
| **Reporting & case management** | `ReportController, PublicReportController, IssueCategoryController`; full state machine; PII-free `PublicReportDto`; sensitive-category anonymity; SLA fields | M3; §12.1 |
| **Reps + find-my-rep** | `RepresentativeController, PartyController, ParliamentController, InstitutionsAdminController`; generic `Representative` (type/mandate/legislature) | M2; D-Q3 |
| **Announcements, feed, notifications** | `AnnouncementController, FeedController, NotificationController, NotificationPreferenceController, SubscriptionController`; **transactional outbox + event bus** (`V97`, ADR-0014) | M4, M5 |
| **Moderation (basic)** | `FlagController, ModerationQueueController, AppealController`; flag→queue→action→appeal + audit | M12-basic |
| **Admin console (backend + Angular)** | `AdminDashboardController, AdminUserController` (user mgmt + additive role grant), `AdminReportsController, AppConfigController, AdminSystemConfigController` (`V90`); **`web-admin/` Angular 18 present & building** | M14 |
| **Tokens (free), responders, engagement, accountability, USSD, analytics** | Controllers + migrations present for all (tokens free-tier `V31/32`, payment **seam** `V33`; responders `V34/35`; petitions/surveys/Q&A `V36–38`; accountability `V43–46`; USSD `V94/95`; analytics events `V91`) | M17/M18/M8–M10/M6/M13/M15 scaffolds |
| **Mobile app** | `mobile/` Flutter present (pubspec) | A3, M-MVP client |
| **CI/CD** | `.github/workflows/ci.yml`: backend build+Testcontainers ITs → CodeQL SAST → container build → Trivy scan; web-admin + mobile guarded jobs | §15; CLAUDE.md §5 |
| **Running-locally-verified** | `DevAdminSeeder` (dev-only bootstrap), `application*.yml`, Flyway `validate` | — |

### 2.2 REMAINING for M-MVP launch

**A. Functional / engineering gaps (P0–P1 for the gate)**

| Gap | Detail | Owner | Severity |
|---|---|---|---|
| **Analytics emission not wired** | `AnalyticsApi.record` / `AnalyticsRecordingService` exist and are idempotent, but **no sibling civic module emits events** (`TODO(wiring)` per ADR-0013 §2). KPI dashboards therefore have **no data source**. Blocks measuring the M-MVP exit criteria + §27.5 SLA/funnel dashboards. | Eng | **P0 (gate-blocking — unverifiable gate)** |
| **Report routing to a responder OWNER deferred** | `ReportService.fileReport` ends `// TODO(wiring): route to a responder OWNER (D21)` — every report is created `NEW`, unrouted (wiring-civic-review F4). The MVP DoD ("auto-route by category × area → official triages → resolves") cannot complete until this lands **with §25.2 fallback** (default office + Admin alert). | Eng (responders increment) | **P0 (MVP DoD)** |
| **Councillor binding-action electoral scope (F1)** | Diwani ratings/petitions skip the electoral gate (constituency-only check) → nationwide brigading vector. Real integrity bug. | Eng + Security | **P1** |
| **Prod-safe admin bootstrap** | First-admin path is `DevAdminSeeder` (`@Profile("dev")` only). Production has **no documented, audited first-ROOT provisioning procedure** (correctly no hardcoded prod admin — but the runbook/migration-gated seed-from-secret path is not yet defined). | SRE + Security | **P1** |
| **Swahili enum-token leak in timeline (F3)** | Report timeline interpolates English enum names into Swahili sentences (`"... (NEW → ASSIGNED)"`). Low-literacy citizen sees `NEW`/`ESCALATED`. | Content/i18n + Eng | **P2** |
| **Server-side live-tier resolver + scope-checker** | Security review MF-2/MF-3: confirm the live trust-tier resolver and area/category/constituency scope-checker are enforced on **every** tier-gated and scoped endpoint (not trusting token claims). Spot-check coverage before gate. | Eng + Security | **P1 (verify)** |
| **Deeper citizen flows** | Offline draft→sync verification, low-data mode, force-update/min-version config, find-my-rep + report end-to-end smoke on **mobile + web** in SW/EN. | Eng (mobile/web) | **P1** |

**B. Phase-2 (correctly OUT of MVP — do not pull forward)**

| Item | Status | Decision |
|---|---|---|
| **Payments / mobile-money purchase** | DB **seam only** (`V33` token_package + payment, Stub provider that never settles; money in minor-unit BIGINT). No money movement in MVP. | D19 — Phase 2 |
| **USSD reporting** | Module scaffolded (`V94/95`, `UssdGatewayController`); **SMS OTP/alerts are MVP**, USSD reporting is Phase 2. | D-Q7 |
| **Accountability authoring (contributions/attendance/promises/ratings curation)** | Scaffolded; curated content is Phase 2. | D-Q4 |
| **Auto-moderation (Swahili-aware)** | Basic/manual moderation is MVP; auto-assist Phase 2. | D-Q8 |
| **Zanzibar geography + ZANZIBAR_HOR** | Modelled (`legislature`/`mandate`), seed deferred. Mainland-first. | D17 |
| **NIDA API** | Pluggable adapter; operator-assisted at launch. **No launch dependency on NIDA** (R30). | D-Q2 |

**C. Open security / civic items (from cross-referenced reviews)**

| ID | Item | Severity for gate | Source |
|---|---|---|---|
| MF-1 | JWT secret strength/presence guard; RS256/ES256 swap before shared-env staff tokens; validate `iss` | P1 | security review |
| MF-2 | Live server-side trust-tier resolver enforced (never trust token `trustTier`) | P1 | security review |
| MF-3 | Scope-aware authz (area/category/constituency from live `RoleAssignment`) on scoped endpoints | P1 | security review |
| S-2 | Login lockout/backoff + OTP anti-automation + staff TOTP enforcement | P1 | security review |
| S-1 | Blind-index HKDF/rotation story for prod KMS adapter (dev SHA-256 is residual) | P2 (document at gate) | security review |
| L-1 | Append-only immutable `audit_event` store (refs/hashes, tombstone-on-erasure) before first auditable security action | P1 | security review |
| L-3 | KMS envelope encryption + key/secret rotation runbook; ID-key residency to Legal | P1 (ties D-Q9) | security review |
| F1 | Councillor electoral scope (see §2.2A) | P1 | civic review |
| F5 | Policy: do OFFICE-targeted petitions need area-scoping vs national? | P2 (product decision) | civic review |

**D. Real-adapter secrets + in-country hosting (D-Q9)**

| Item | Owner | Severity |
|---|---|---|
| Real SMS aggregator credentials + **shortcode procured & live**, deliverability tested | Program + Eng | **P0** |
| FCM configured; email sender authenticated (SPF/DKIM/DMARC) | Eng/SRE | P1 |
| KMS for ID field-encryption keys (replace dev key); envelope encryption | SRE + Security | **P0** |
| **In-country-where-feasible hosting confirmed with Legal (D-Q9)**; ID-data residency | Legal + SRE | **P0** |
| **PDPA 2022/2023 sign-off**: consent center, right-to-erasure, data-controller registration, retention config | Legal | **P0** |
| Backups + tested restore; one-command rollback | SRE | **P0** |

**E. Pilot-region rollout — the gating ops programs (region = unit of "done")**

| Program | What "done for pilot" means | Owner | Severity |
|---|---|---|---|
| **D5 — agency onboarding (§27.2)** | Pilot = 1–2 regions (one urban e.g. a Dar council, one rural e.g. Singida), categories Water/Roads/Health/Sanitation; **≥1 Area Official active per pilot council/category**; MoU per department; named champion; routing resolves to a real office; default-office + Admin-alert fallback configured (UC-D04). | Program | **P0** |
| **D3 — local-leader onboarding (§27.3)** | Reps onboarded for the pilot region (**MPs first → Councillors → ward/village execs backfilled**), verified against official electoral/LGA rolls before REPRESENTATIVE granted; reps go live in the **same region wave** as agencies. | Program | **P0** |
| **Seed integrity for pilot** | Every pilot Ward resolves a full admin chain + constituency; "find-my-rep" and "route-a-report" round-trip per pilot region (§27.4). | Program + Eng | **P0** |
| **Moderation + verification ops staffed** | Moderator queue manned (native-Swahili); operator-assisted ID/rep-claim review manned; on-call for abuse/safety (R22, R32). | T&S | **P0** |
| **Inclusion-edge facilitation** | On-the-ground community facilitators in pilot regions; SMS-alert subscription path live; low-data/offline verified (R6/R7). | Program/Product | P1 |

---

## 3. Go/No-Go criteria — owners, status, evidence

Status key: **PASS** (met) · **PARTIAL** (built, not fully wired/verified) · **OPEN** (not started/blocked) · **N/A-P2** (correctly out of MVP).
Every row maps to a §27.5 launch-readiness checkbox.

### 3.1 Security & privacy

| # | Criterion | Owner | Status | Evidence / gap |
|---|---|---|---|---|
| S1 | RBAC method-level, deny-by-default, no "authenticated-only" admin surface | Eng + Security | **PARTIAL** | `@EnableMethodSecurity` ON, deny-by-default verified (security review); **MF-3** scope-checker coverage to confirm on scoped endpoints |
| S2 | Live trust-tier resolved server-side (never token claim) on tier gates | Eng + Security | **PARTIAL** | MF-2 — verify the resolver is enforced on every `@RequiresTier`/T3 gate |
| S3 | PII (national/voter ID) field-encrypted at rest; redacted in logs; retention config set | Security | **PARTIAL** | AES-GCM + blind-index built (dev key); **needs KMS (L-3) + retention config**; S-4 log-redaction structural guard |
| S4 | No secrets in source; secrets from env/secret manager; CORS allow-listed | Eng/SRE | **PASS** | security review (no baked secrets; CORS correct) |
| S5 | Security review (OWASP ASVS-aligned), pen-test on auth/reporting/admin; no open P1/P2 | Security | **OPEN** | Foundation review done (no P1/P2 in scaffold); **full pen-test + F1/MF-1..3 closure outstanding** |
| S6 | Legal/PDPA sign-off; hosting/residency confirmed (D-Q9); consent + right-to-erasure working | Legal | **OPEN** | D-Q9 not finalised; PDPA registration + erasure path to verify |
| S7 | Immutable audit-event store for security/verification/multi-hat/erasure | Eng + Security | **OPEN** | L-1 — per-row audit columns exist; append-only `audit_event` store to design |
| S8 | JWT secret guard + asymmetric signing before shared-env staff tokens; `iss` validated | Eng | **OPEN** | MF-1 |
| S9 | Login lockout/backoff + OTP anti-automation + staff TOTP enforced | Eng | **PARTIAL** | staff TOTP present (`DevAdminSeeder`, MfaLoginGate); S-2 lockout/OTP-rate to confirm |

### 3.2 Performance & reliability

| # | Criterion | Owner | Status | Evidence / gap |
|---|---|---|---|---|
| P1 | Load test at pilot volume incl. announcement-burst fan-out + report spikes; p95 reads <500ms, writes <1s | SRE/Eng | **OPEN** | outbox fan-out built; load test not yet run |
| P2 | Resilience: SMS/verification retries + circuit-breakers; graceful degradation (feed/search read-only) | Eng/SRE | **PARTIAL** | adapter ports + degradation design (§21) present; verify under fault injection |
| P3 | Observability live: structured logs (trace ids), metrics, tracing, health, alerts (5xx/latency/SLA/queue depth) | SRE | **PARTIAL** | health/metrics wired (§15); **SLA-breach + queue-depth alerts depend on analytics emission (P0)** |
| P4 | Backups + tested restore; one-command rollback; migrations gated | SRE | **OPEN** | Flyway `validate` gated; backup/restore + rollback runbook outstanding |

### 3.3 Operations & content

| # | Criterion | Owner | Status | Evidence / gap |
|---|---|---|---|---|
| O1 | Moderation staffed; flag→action→audit→appeal working; on-call for abuse/safety | T&S | **PARTIAL** | software path built (Flag/Queue/Appeal controllers); **staffing + on-call OPEN** |
| O2 | Verification ops staffed — operator-assisted ID & rep-claim queues manned (D-Q2) | T&S | **PARTIAL** | review controllers built; **staffing OPEN** |
| O3 | SMS shortcode procured & live for OTP + alerts; deliverability tested | Program/Eng | **OPEN** | **start procurement early (R27)** — long lead time |
| O4 | Push (FCM) configured; email sender authenticated (SPF/DKIM) | Eng/SRE | **OPEN** | ports built; prod credentials/config outstanding |
| O5 | Prod-safe first-admin provisioning (audited, no hardcoded prod admin) | SRE + Security | **OPEN** | dev seeder only; define migration/secret-gated first-ROOT runbook |

### 3.4 Data & program readiness

| # | Criterion | Owner | Status | Evidence / gap |
|---|---|---|---|---|
| D1 | Seed loaded & integrity-verified — geography (incl. Council/LGA), constituencies, parties, parliaments, categories+SLAs+routing | Program + Eng | **PARTIAL** | seed migrations present (`V71–83`); **pilot-region referential-integrity + find-my-rep/route round-trip to verify (§27.4)**; nationwide council/ward enrichment ongoing |
| D2 | **Analytics emission wired** → KPI/SLA/funnel dashboards report TTFR/TTR/% resolved/verification funnel | Eng | **OPEN** | **recorder built, emission `TODO` — P0; gate is unverifiable without it** |
| D3 | Report auto-routing live with §25.2 fallback (default office + Admin alert) | Eng | **OPEN** | F4 `TODO(wiring)` |
| D4 | **Pilot region "live"**: Area Officials onboarded & scoped (D5); reps onboarded for region (D3); routing resolves to a real office | Program | **OPEN** | the gating ops programs — see §2.2E |

### 3.5 Product & accessibility

| # | Criterion | Owner | Status | Evidence / gap |
|---|---|---|---|---|
| A1 | SW/EN strings complete for all launch flows; locale-aware formatting | Content/i18n | **PARTIAL** | bundles present; **F2 fixed**, **F3 enum-leak OPEN** |
| A2 | Accessibility pass (WCAG 2.1 AA web/admin; large-touch/high-contrast/screen-reader mobile; low-literacy flows) | UX/Eng | **OPEN** | not yet audited |
| A3 | Mobile force-update/min-version config set; offline draft→sync verified | Eng (mobile) | **OPEN** | AppConfig path exists (`AppConfigController`); verify on device |
| A4 | End-to-end smoke of MVP DoD in a real pilot region (find-my-rep → file → route → resolve → confirm → notify) | Program + Eng | **OPEN** | blocked on D3 (routing) + E (pilot live) |

### 3.6 Phase-2 fences (must NOT be in MVP — confirm held)

| # | Criterion | Owner | Status |
|---|---|---|---|
| X1 | No money movement in MVP (payment seam only, Stub provider) | Eng | **PASS (N/A-P2)** — D19 |
| X2 | USSD reporting not gating launch (SMS OTP/alerts are MVP) | Program | **PASS (N/A-P2)** — D-Q7 |
| X3 | No hard dependency on NIDA API (operator-assisted launch) | Eng | **PASS (N/A-P2)** — D-Q2 / R30 |
| X4 | Zanzibar deferred (Mainland-first) | Program | **PASS (N/A-P2)** — D17 |

---

## 4. Top risks at the gate (PRD §26)

| Risk | Why it bites M-MVP | Mitigation in flight | Owner |
|---|---|---|---|
| **R1** — agency onboarding stalls | Reports route to officials who never respond → TTFR/TTR/% resolved collapse | Stage region-by-region; never open reporting before officials live; default-office + Admin-alert fallback; responsiveness dashboards (need D2) | Program |
| **R2** — local-leader onboarding unachievable at scale | "No rep found" for a ward → lost trust | MPs first → Councillors → ward/village execs; bulk import; operator-assisted REP_CLAIM; show "rep being onboarded" state | Program |
| **R3** — program-coordination overload | Two ambitious tracks + seed run as ops alongside eng | Funded ops program; per-region launch checklist; **region is the unit of done**; go/no-go gate per wave | Program/Product (**Asha owns**) |
| **R4** — geography/seed incomplete or wrong | Mis-routing, wrong rep mapping | Official seed + effective-dated WardConstituency; per-region data-quality review before go-live (D1) | Program/Eng |
| **R12** — PDPA non-compliance | Hard launch gate, not afterthought | Consent center, erasure, residency to Legal (S6); data-controller registration early | Legal/Eng |
| **R27** — shortcode/licensing slip | OTP + alerts blocked; long lead time | **Start procurement now**; SMS-early/USSD-Phase-2 decouples launch from USSD approval | Legal/Program |
| **R5 (integrity, F1)** | Out-of-area brigading of councillors discredits accountability data | Implement D13 ward-scope for councillors; security sign-off | Eng/Security |

---

## 5. Realistic remaining-work sequencing to launch (relative milestones)

> Estimated in **relative milestones**, not calendar dates (PRD convention). Eng and Ops run in parallel; the gate is the **join** of both plus Legal/SRE.

**Wave 0 — Unblock the gate's measurability & critical path (eng, P0)**
1. **Wire analytics emission** from reporting/identity/communications civic flows → KPI/SLA/funnel dashboards (D2). *Without this the gate cannot be evaluated.*
2. **Wire report auto-routing** to responder OWNER **with §25.2 fallback** (default office + Admin alert) (D3 criterion / F4).
3. **Fix F1** councillor electoral scope + add tests; security sign-off.
4. Confirm **MF-2/MF-3** (live-tier resolver + scope-checker) enforced on every gated/scoped endpoint.

**Wave 1 — Security/privacy hardening (eng + security + legal, P0/P1)**
5. **MF-1** JWT guard + asymmetric signing + `iss`; **S-2** lockout/OTP anti-automation; **L-1** audit-event store.
6. **KMS** envelope encryption for ID keys + rotation runbook (**L-3**); retention config; log-redaction guard (S-4).
7. **D-Q9 hosting/residency decision + PDPA sign-off** (consent, erasure, data-controller registration). *Legal critical path — start immediately.*
8. Full **pen-test** on auth/reporting/admin; close any P1/P2 (S5).

**Wave 2 — Ops/infra readiness (SRE + eng, P0/P1)**
9. **Real adapter secrets**: SMS aggregator + **shortcode procured & live** (start in Wave 0 — long lead, R27), FCM, email SPF/DKIM.
10. **Prod-safe first-admin** provisioning runbook (audited; no hardcoded prod admin) (O5).
11. **Load test** at pilot volume (announcement burst + report spikes); p95 targets; resilience/fault-injection (P1/P2).
12. **Backups + tested restore + one-command rollback** (P4); observability alerts (P3, depends on D2).

**Wave 3 — Product/accessibility polish (eng + content + UX, P1/P2)**
13. **F3** Swahili enum-token fix; SW/EN completeness audit for launch flows (A1).
14. **Accessibility pass** WCAG 2.1 AA + low-literacy/mobile (A2); offline draft→sync + force-update verified on device (A3).

**Parallel track — Ops onboarding programs (Program, P0; runs alongside Waves 0–3)**
- **D5 pilot**: select pilot regions (one urban + one rural); MoUs per department; named champions; onboard & scope **≥1 Area Official per pilot council/category**; configure routing + fallback.
- **D3 leaders**: onboard pilot-region reps **MPs → Councillors → ward/village execs**, verified against official rolls; align to the **same region wave** as agencies.
- **Seed integrity per pilot region** (D1); moderation + verification ops staffed (O1/O2); community facilitators in place.

**Gate — Go/No-Go (Asha chairs)**
- **Join condition:** all §3.1–§3.5 rows PASS for the pilot region; §3.6 Phase-2 fences held.
- **Final smoke (A4):** find-my-rep → file report → auto-route → official triage → resolve → citizen confirm → notify, **end-to-end in the live pilot region, SW/EN, mobile + web** — passed.
- A region is marked **"live" in config only after** geography seeded & verified, officials onboarded & scoped, leaders onboarded for the region, and routing resolves to a real office (default-office + Admin-alert fallback configured).

---

## 6. Go/No-Go decision

**Current call: NO-GO (conditional).** The engineering spine is materially complete and running, which is a genuine departure from every prior Taarifu attempt. But three classes of gate items are **OPEN**, and they are exactly the ones that decide whether the loop actually closes:

1. **The gate is currently unverifiable** — analytics emission is not wired, so the M-MVP KPI exit criteria (TTFR/TTR/% resolved/verification funnel) have no data source. *This is the first thing to fix; it is cheap relative to its leverage.*
2. **Production trust & compliance are not signed off** — real secrets/KMS, in-country hosting (D-Q9), and PDPA are hard gates, not afterthoughts (R12).
3. **No region is "live"** — the D3/D5 ops programs that turn launch-ready software into a working civic loop have not completed for any pilot region.

We do not relax any of these. We **GO per region** only when its row-by-row checklist passes. The recommendation to the sponsor: **the code is close; the region is not yet live — fund and run the onboarding programs as the critical path, and wire KPI emission so we can prove the loop where officials are real.**

---

*This checklist is a living artefact. It is re-evaluated at each region wave's go/no-go. Status reflects repo state on `develop` as inventoried at authoring; Eng/SRE/Legal/T&S leads own the row updates in their lanes.*
