# Taarifu — M-MVP Launch-Readiness Checklist & Go/No-Go

> **Owner:** Asha Mwakyusa (Delivery & Project Management)
> **Gate:** **M-MVP** — first live pilot region (PRD §27.1 / §27.5)
> **Status:** **NO-GO (conditional)** — after waves 2–5 the **engineering spine is now substantially WIRED, not just scaffolded** (outbox live; report routing→OWNER with §25.2 fallback; analytics emission from reporting/responders/moderation/engagement; F1 electoral-scope fence enforced; real adapters + prod-boot; USSD auth; DLQ; admin users/reports/appeals; attachment pipeline; FCM tokens; full national geo-seed; Redis limiters; E2E tests; OpenAPI; S-3 + media-IDOR + audit-CHECK fixes). The gate is now held almost entirely by **non-code items**: **production secrets + in-country hosting + PDPA sign-off, pen-test/load-test, and the two ops onboarding programs (D3/D5)** — plus a short residual-code tail (identity verification-funnel emission; routing reverse-leg; real SMS/email recipient resolution).
> **Grounding:** PRD §3.3 (KPIs), §8 (scope/MVP DoD), §15 (NFRs), §18 (security/privacy), §19 (decisions), §26 (risks R1–R34), §27 (rollout, milestones, launch-readiness). CLAUDE.md §9 (Definition of Done).
> **Cross-references:** [`docs/reviews/TODO-WIRING-AUDIT.md`](reviews/TODO-WIRING-AUDIT.md) (the honest wired-vs-stub audit behind this refresh — categories A/B/C); [`docs/reviews/wave4-review.md`](reviews/wave4-review.md) (FCM/geo-seed/media/Redis/E2E wave + the V121 & media-IDOR fixes); [`docs/reviews/security-foundation-review.md`](reviews/security-foundation-review.md) (threat-model reference — MF-1..3, S-1..5, L-1..3); [`docs/reviews/wiring-civic-review.md`](reviews/wiring-civic-review.md) (civic-readiness — F1 councillor electoral scope, F2..F5); [`docs/reviews/geography-civic-review.md`](reviews/geography-civic-review.md) (seed correctness).

---

## 1. Bottom line (read first)

The platform is **not** the unbuilt "reference catalogue + auth shell" the four prior repos left (SYNOPSIS §2). The clean rewrite has a **running, secured modular monolith spanning 16 modules**, both client apps scaffolded, the TZ geography/electoral seed loaded, and CI gating build→test→SAST→container-scan. **The software is far closer to MVP than the program is.**

Per PRD §26 and §27, the decisive M-MVP risks are **program and adoption (R1–R3, R6–R8, R24)** — won or lost region by region in partnerships and operations. **"Region" is the unit of launch readiness, not "feature complete."** We will not open citizen reporting in any area before its officials are live and its leaders onboarded.

**The single most important things to unblock, in order (post waves 2–5):**
1. **Production secrets + in-country hosting decision (D-Q9) + PDPA sign-off** — real adapter credentials, KMS for ID encryption, residency confirmed with Legal. **Now the top item** (it was #2; analytics emission and F1, formerly #1 and #4, are done). **Owner: SRE + Legal.**
2. **Pilot region "live" (D3 leaders + D5 agencies onboarded & scoped)** — the gating ops programs; no amount of code substitutes for a real Area Official answering a real report. **Owner: Program.**
3. **Pen-test + load-test, backups/restore + rollback** — the remaining proof-of-readiness gates on auth/reporting/admin and at pilot volume. **Owner: Security + SRE.**
4. **Close the residual-code tail (short):** identity **verification-funnel analytics emission** (the one remaining published-KPI emission gap), the **routing reverse-leg** (`RESPONDER_ASSIGNED` → set `assignedResponderId`/auto-ASSIGNED), and the **USSD SMS path** (needs communications' two `..api..` command ports). See [`TODO-WIRING-AUDIT.md`](reviews/TODO-WIRING-AUDIT.md) §2 (A1–A3). *In-app SMS/email recipient resolution is already wired (`RecipientContactApi`).* **Owner: Eng.**

> **What changed since the last revision:** analytics emission **is wired** (reporting/responders/moderation/engagement), report **routing→OWNER (D21)** with the §25.2 fallback **is wired and tested**, and the **F1 councillor/Diwani electoral-scope fence is enforced** on ratings + petition-signatures — so items that were the top blockers are now done. The gate has moved decisively from "missing software" to "production trust + ops onboarding".

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
| **Mobile app** | `mobile/` Flutter present (pubspec); deepened in wave 5 | A3, M-MVP client |
| **CI/CD** | `.github/workflows/ci.yml`: backend build+Testcontainers ITs → CodeQL SAST → container build → Trivy scan; web-admin + mobile guarded jobs | §15; CLAUDE.md §5 |
| **Running-locally-verified** | `DevAdminSeeder` (dev-only bootstrap), `application*.yml`, Flyway `validate` | — |
| **Transactional outbox + relay (WAVE 2–3)** | `common.outbox`: `OutboxEvent` (`V97`), `OutboxRelay` (`@Scheduled`, `FOR UPDATE SKIP LOCKED`, backoff+jitter, **DLQ** on attempt cap), `OutboxReplayService`, `OutboxMaintenance` (retention/purge), `DomainEventHandler` registry, `OutboxAdminService`; **DLQ audit event types** (`V104`) | §8; ADR-0014 |
| **Analytics EMISSION wired (WAVE 3)** | `AnalyticsEventHandler` (outbox sink) + producers emitting `CivicActivityRecorded`: **reporting** (`REPORT_FILED` + lifecycle), **responders** (`REPORT_ROUTED→OWNER`), **moderation** (`MODERATION_ACTION_TAKEN`), **engagement** (`PETITION_SIGNED`). PII-free; idempotent on `eventId` | M15; Appendix E; was D2 |
| **Report routing → responder OWNER (D21) wired (WAVE 3)** | `ReportService.fileReport` emits `REPORT_ROUTED`; `responders.RoutingHandler` evaluates `RoutingRule` precedence + **§25.2 fallback** (no rule = stays unrouted for manual assign, no-op success), creates single-OWNER `ResponderAssignment`, emits `RESPONDER_ASSIGNED`; tested (`RoutingHandlerTest`) | M3/M-MVP DoD; D21; was F4 |
| **F1 electoral-scope fence enforced (WAVE 3)** | `RatingService` + `PetitionService.sign` enforce two-tier scope (MP→constituency, councillor/ward-exec→**ward**, special-seats→none) via `institutions.api.RepresentativeQueryApi` × `identity.api.ElectoralScopeApi`; OUT_OF_SCOPE audited; **no token read** (D18 fence) | D13/F1; integrity |
| **Real adapters + prod-safe boot (WAVE 2/4)** | USSD identity/reporting adapters real (delegate to `AccountProvisioningApi`/`UssdReportApi`); **`ProdAdminBootstrap`** (explicitly-gated first-ROOT, no default credential); **USSD gateway secret auth**; FCM `PushSender` + **device-token registry** (`V122`) | O5; EI-5; D-Q7 |
| **Media attachment pipeline + IDOR fix (WAVE 4)** | presigned upload→confirm→bind→scan (`V47`/`V121`), `MediaAttachmentValidator` (uploader-scoped, rejects account-media on anonymous filing); **media download host-scoped** via `ReportMediaAccessService` (MF-2 fixed, fail-closed); **V121 migration syntax fixed** | EI-8; §25.3; MF-2 |
| **Full national geography seed (WAVE 4)** | `V105–V109`: national councils, constituencies, wards, ward↔constituency, closure — beyond the pilot-only `V71–83` | D1; D14 |
| **Redis-backed limiters + observability (WAVE 4)** | `RedisAuthRateLimiter`, `RedisUssdGatewayRateLimiter` (multi-instance safe; in-memory fallbacks); metrics/health; outbox DLQ metric | S-2; P3 |
| **E2E + contract tests, OpenAPI (WAVE 4/5)** | `CivicFlowE2ETest`, `PublicReadsContractE2ETest`, `UssdGatewaySecretAuthE2ETest`; springdoc OpenAPI surface | §10; A4 partial |

### 2.2 REMAINING for M-MVP launch

**A. Functional / engineering gaps — status after waves 2–5** (see [`TODO-WIRING-AUDIT.md`](reviews/TODO-WIRING-AUDIT.md) for the wired-vs-stub evidence)

| Gap | Detail | Owner | Severity / status |
|---|---|---|---|
| ~~Analytics emission not wired~~ | **DONE (wave 3).** Emission flows on the outbox from reporting/responders/moderation/engagement via `AnalyticsEventHandler`. **Residual (A1):** identity does **not** yet emit the T0→T3 **verification-funnel** events — the one remaining published-KPI emission gap. | Eng (identity) | **P1 — residual A1** |
| ~~Report routing to a responder OWNER deferred~~ | **DONE (wave 3).** Filing emits `REPORT_ROUTED`; `RoutingHandler` creates the single OWNER with §25.2 fallback; tested. **Residual (A2):** no reporting handler consumes `RESPONDER_ASSIGNED`, so `Report.assignedResponderId` stays null / report stays `NEW` (OWNER exists in responders; only the report-side denormalised pointer + auto-`ASSIGNED` is missing). | Eng (reporting) | **P1 — residual A2** |
| ~~Councillor binding-action electoral scope (F1)~~ | **DONE (wave 3).** Two-tier electoral fence enforced on ratings + petition-signatures (MP→constituency, councillor→ward, special-seats→none) via published query ports; no token read; OUT_OF_SCOPE audited. | Eng + Security | **CLOSED** |
| ~~Prod-safe admin bootstrap~~ | **DONE (wave 2/4).** `ProdAdminBootstrap` — explicitly-gated first-ROOT provisioning, audited, no default credential (vs `DevAdminSeeder` dev-only). | SRE + Security | **CLOSED** |
| ~~Real SMS/email recipient resolution~~ | **DONE.** In-app `NotificationDispatchService` resolves the real MSISDN/email via `identity.api.RecipientContactApi` and hands it to the masking gateway/sender (the old `// TODO(wiring)` markers are removed). Push (FCM) + feed also work. | Eng (communications) | **CLOSED** |
| **USSD SMS send + area-alert forwarding stubs (A3)** | Blocked on **communications publishing two `..api..` command ports** (SMS send + Subscription/preference) for the **USSD module's own** SMS path. USSD reporting is Phase-2 (D-Q7); SMS confirmation degrades to a safe logged no-op. | Eng (communications) — **central** | **P2** |
| **Swahili enum-token leak in timeline (F3)** | Report timeline interpolates English enum names into Swahili sentences (`"... (NEW → ASSIGNED)"`). Low-literacy citizen sees `NEW`/`ESCALATED`. | Content/i18n + Eng | **P2** |
| **Server-side live-tier resolver + scope-checker** | MF-2/MF-3: confirm the live trust-tier resolver + scope-checker are enforced on **every** tier-gated/scoped endpoint. F1 work exercised `RepresentativeQueryApi`×`ElectoralScopeApi` live; broader spot-check still owed before gate. | Eng + Security | **P1 (verify)** |
| **Moderation analytics + sanction tail (A5/A6/A7)** | `content_flagged`/`moderation_appeal_resolved` analytics not yet emitted (audit IS written); SUSPEND/VERIFY→identity sanction event not emitted; media EXIF/geo-strip byte worker not invoked. | Eng (moderation/media) | **P2 (hardening)** |
| **Deeper citizen flows** | Offline draft→sync verification, low-data mode, force-update/min-version config, find-my-rep + report end-to-end smoke on **mobile + web** in SW/EN. Mobile deepened in wave 5; on-device smoke still owed. | Eng (mobile/web) | **P1** |

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
| F1 | Councillor electoral scope | **CLOSED (wave 3)** — two-tier fence enforced (RatingService/PetitionService) | civic review |
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
| S9 | Login lockout/backoff + OTP anti-automation + staff TOTP enforced | Eng | **PARTIAL→PASS-pending** | staff TOTP present; **wave 4 added Redis-backed `RedisAuthRateLimiter`/`RedisUssdGatewayRateLimiter`** (multi-instance, in-memory fallback) — confirm thresholds + OTP-rate coverage to fully close S-2 |

### 3.2 Performance & reliability

| # | Criterion | Owner | Status | Evidence / gap |
|---|---|---|---|---|
| P1 | Load test at pilot volume incl. announcement-burst fan-out + report spikes; p95 reads <500ms, writes <1s | SRE/Eng | **OPEN** | outbox fan-out built; load test not yet run |
| P2 | Resilience: SMS/verification retries + circuit-breakers; graceful degradation (feed/search read-only) | Eng/SRE | **PARTIAL** | adapter ports + degradation design (§21) present; verify under fault injection |
| P3 | Observability live: structured logs (trace ids), metrics, tracing, health, alerts (5xx/latency/SLA/queue depth) | SRE | **PARTIAL** | health/metrics + **outbox DLQ metric** wired (wave 4); analytics emission now live so SLA/queue dashboards **have a data source**; **alert rules** (5xx/latency/SLA-breach/DLQ-non-empty) still to provision |
| P4 | Backups + tested restore; one-command rollback; migrations gated | SRE | **OPEN** | Flyway `validate` gated; backup/restore + rollback runbook outstanding |

### 3.3 Operations & content

| # | Criterion | Owner | Status | Evidence / gap |
|---|---|---|---|---|
| O1 | Moderation staffed; flag→action→audit→appeal working; on-call for abuse/safety | T&S | **PARTIAL** | software path built (Flag/Queue/Appeal controllers); **staffing + on-call OPEN** |
| O2 | Verification ops staffed — operator-assisted ID & rep-claim queues manned (D-Q2) | T&S | **PARTIAL** | review controllers built; **staffing OPEN** |
| O3 | SMS shortcode procured & live for OTP + alerts; deliverability tested | Program/Eng | **OPEN** | **start procurement early (R27)** — long lead time |
| O4 | Push (FCM) configured; email sender authenticated (SPF/DKIM) | Eng/SRE | **PARTIAL** | **FCM `PushSender` + device-token registry built (wave 4, `V122`); in-app recipient MSISDN/email resolution wired (`RecipientContactApi`)**; prod FCM credentials + email SPF/DKIM/DMARC config + the USSD SMS path (A3) outstanding |
| O5 | Prod-safe first-admin provisioning (audited, no hardcoded prod admin) | SRE + Security | **PASS-pending** | **`ProdAdminBootstrap` built (wave 2/4)** — explicitly-gated first-ROOT, audited, no default credential; confirm the provisioning runbook + secret source for prod |

### 3.4 Data & program readiness

| # | Criterion | Owner | Status | Evidence / gap |
|---|---|---|---|---|
| D1 | Seed loaded & integrity-verified — geography (incl. Council/LGA), constituencies, parties, parliaments, categories+SLAs+routing | Program + Eng | **PARTIAL→improved** | pilot seed (`V71–83`) **plus full national seed (`V105–V109`)** loaded (wave 4); **pilot-region referential-integrity + find-my-rep/route round-trip still to verify per region (§27.4)** |
| D2 | **Analytics emission wired** → KPI/SLA/funnel dashboards report TTFR/TTR/% resolved/verification funnel | Eng | **PARTIAL** | **DONE for reporting/responders/moderation/engagement (wave 3)**; **residual: identity verification-funnel events not yet emitted (A1)** — only that funnel KPI lacks a source |
| D3 | Report auto-routing live with §25.2 fallback (default office + Admin alert) | Eng | **PARTIAL** | **DONE (wave 3):** `REPORT_ROUTED`→`RoutingHandler`→single OWNER + fallback, tested. **Residual: reverse-leg (A2)** — reporting not yet setting `assignedResponderId`/auto-`ASSIGNED` from `RESPONDER_ASSIGNED` |
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
| **R1** — agency onboarding stalls | Reports route to officials who never respond → TTFR/TTR/% resolved collapse | Stage region-by-region; never open reporting before officials live; default-office + Admin-alert fallback **(now wired — §25.2 in `RoutingHandler`)**; responsiveness dashboards **(analytics emission now live for routing/reporting — data source exists)** | Program |
| **R2** — local-leader onboarding unachievable at scale | "No rep found" for a ward → lost trust | MPs first → Councillors → ward/village execs; bulk import; operator-assisted REP_CLAIM; show "rep being onboarded" state | Program |
| **R3** — program-coordination overload | Two ambitious tracks + seed run as ops alongside eng | Funded ops program; per-region launch checklist; **region is the unit of done**; go/no-go gate per wave | Program/Product (**Asha owns**) |
| **R4** — geography/seed incomplete or wrong | Mis-routing, wrong rep mapping | Official seed + effective-dated WardConstituency; per-region data-quality review before go-live (D1) | Program/Eng |
| **R12** — PDPA non-compliance | Hard launch gate, not afterthought | Consent center, erasure, residency to Legal (S6); data-controller registration early | Legal/Eng |
| **R27** — shortcode/licensing slip | OTP + alerts blocked; long lead time | **Start procurement now**; SMS-early/USSD-Phase-2 decouples launch from USSD approval | Legal/Program |
| **R5 (integrity, F1)** | Out-of-area brigading of councillors discredits accountability data | **MITIGATED (wave 3):** D13 two-tier ward-scope for councillors enforced on ratings + petition-signatures; OUT_OF_SCOPE audited; final security sign-off owed | Eng/Security |

---

## 5. Realistic remaining-work sequencing to launch (relative milestones)

> Estimated in **relative milestones**, not calendar dates (PRD convention). Eng and Ops run in parallel; the gate is the **join** of both plus Legal/SRE.

**Wave 0 — Unblock the gate's measurability & critical path (eng, P0)** — *largely DONE in waves 2–5*
1. ~~Wire analytics emission~~ **DONE** for reporting/responders/moderation/engagement → **residual A1:** emit the identity **verification-funnel** events (the one published KPI still without a source).
2. ~~Wire report auto-routing to OWNER with §25.2 fallback~~ **DONE & tested** → **residual A2:** consume `RESPONDER_ASSIGNED` in reporting to set `assignedResponderId`/auto-`ASSIGNED`.
3. ~~Fix F1 councillor electoral scope~~ **DONE** (ratings + petition-signatures) → final security sign-off.
4. Confirm **MF-2/MF-3** (live-tier resolver + scope-checker) enforced on every gated/scoped endpoint (F1 exercised the ports live; broaden the spot-check).
5. **A3 (central):** communications to publish the SMS-send + Subscription/preference `..api..` command ports so the **USSD** SMS path stops being a stub. *(In-app recipient MSISDN/email resolution is already wired via `RecipientContactApi`.)*

**Wave 1 — Security/privacy hardening (eng + security + legal, P0/P1)**
5. **MF-1** JWT guard + asymmetric signing + `iss`; **S-2** lockout/OTP anti-automation; **L-1** audit-event store.
6. **KMS** envelope encryption for ID keys + rotation runbook (**L-3**); retention config; log-redaction guard (S-4).
7. **D-Q9 hosting/residency decision + PDPA sign-off** (consent, erasure, data-controller registration). *Legal critical path — start immediately.*
8. Full **pen-test** on auth/reporting/admin; close any P1/P2 (S5).

**Wave 2 — Ops/infra readiness (SRE + eng, P0/P1)**
9. **Real adapter secrets**: SMS aggregator + **shortcode procured & live** (start now — long lead, R27), FCM **prod credentials** (registry built), email SPF/DKIM.
10. ~~Prod-safe first-admin provisioning~~ **`ProdAdminBootstrap` built** — confirm the runbook + secret source only (O5).
11. **Load test** at pilot volume (announcement burst + report spikes); p95 targets; resilience/fault-injection (P1/P2).
12. **Backups + tested restore + one-command rollback** (P4); observability **alert rules** (P3 — analytics now provides the data source; rules still to provision).

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

**Current call: NO-GO (conditional)** — but the *reason* has shifted decisively, and the engineering readiness is materially higher than at the last revision.

**Approximate engineering readiness (build-side, for the gate's code rows): ~90%.** After waves 2–5 the spine is not just scaffolded but **wired and partly tested**: outbox + DLQ, routing→OWNER with §25.2 fallback, analytics emission (4 of 5 producers), the F1 electoral fence, real adapters + prod-boot, USSD auth, media pipeline + IDOR fix, full national seed, Redis limiters, E2E tests, OpenAPI. The **residual code tail is short and mostly P2** — see [`TODO-WIRING-AUDIT.md`](reviews/TODO-WIRING-AUDIT.md): A1 (identity verification-funnel emission, P1), A2 (routing reverse-leg, P1), A3 (real SMS/email recipient resolution, P1), and a P2/P3 hardening list. The formerly gate-blocking items (analytics emission, routing, F1, prod-admin) are **done**.

**The gate is now held overwhelmingly by NON-CODE items**, which is the correct place for a program-led platform to be blocked:

1. **Residual code tail (small):** identity verification-funnel emission (A1), routing reverse-leg (A2), and the USSD SMS path via communications' two `..api..` command ports (A3). *Days, not waves.* (In-app SMS/email recipient resolution is already wired.)
2. **Production trust & compliance — not signed off (the real blocker):** real secrets/**KMS** for ID encryption, **in-country hosting (D-Q9)**, **PDPA** sign-off (consent, erasure, data-controller registration), **pen-test** on auth/reporting/admin, **load-test** at pilot volume, **backups + tested restore + rollback**, **SMS shortcode procured & live (R27 — long lead, start now)**. These are hard gates (R12), owned by SRE + Legal + Security, and no amount of code substitutes for them.
3. **No region is "live":** the **D3/D5 ops onboarding programs** that turn launch-ready software into a working civic loop have not completed for any pilot region (Program-owned; the unit of "done").

We do not relax any of these. We **GO per region** only when its row-by-row checklist passes. **Updated recommendation to the sponsor:** *the software is now genuinely close to MVP — the engineering blockers that dominated the last review are cleared. Shift the critical path to (a) the production-trust/compliance gates (secrets/KMS, hosting/PDPA, pen-test, load-test, shortcode procurement) and (b) the D3/D5 region onboarding programs. Close the short residual code tail (A1–A3) in parallel; it does not gate the program.*

---

*This checklist is a living artefact. It is re-evaluated at each region wave's go/no-go. Status reflects repo state on `develop` as inventoried at authoring; Eng/SRE/Legal/T&S leads own the row updates in their lanes.*
