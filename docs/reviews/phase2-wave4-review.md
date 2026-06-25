# Phase-2 Wave-4 — Final Integrated Verify + Architecture/Security Review

> **Reviewer:** Solution Architect (David Okello) · **Date:** 2026-06-25 · **Branch:** `feature/phase2-wave4`
> **Base:** full backend @ `develop` 22ce05b (Phase-2 waves 1–3 merged).
> **Grounding:** PRD §16/§17/§18/§21/§23.5/§24; ARCHITECTURE.md §3/§5/§6/§7/§8; ADR-0013 (cross-module integration); ADR-0015 (payments gateway); CLAUDE.md §3/§8/§12.

## Verdict: **PASS** (with one CENTRAL NEED and a small backlog — none blocking the merge of this branch).

Wave-4 lands six slices on top of wave-3: accountability depth (promise-status history + **representative right-of-reply** with a deny-by-default ownership fence), engagement lifecycle endpoints (petition activation / survey opening / question answer), real Tanzanian mobile-money sandbox adapters (M-Pesa / Tigo Pesa / Airtel Money / HaloPesa) behind the gateway port, real FCM + HTTP-SMS-aggregator adapters with an **SMS DLR webhook**, a search **admin reindex/backfill** job, identity **profile-lookup + profile-content** ports, an **announcement-expiry scheduler**, and a brand-new **Swahili-first citizen PWA** (`web-citizen`). All of it respects the locked decisions, the module boundary, the D18 integrity fence, and PII discipline.

---

## 1. Build & test gates

| Gate | Result |
|---|---|
| `./mvnw -q -DskipTests package` | **GREEN** (exit 0) |
| `./mvnw test -Dtest=ModuleBoundaryTest` | **GREEN — 7/7** (the ADR-0013 `api → api` permit + internals fence rule holds; existing four rules unchanged) |
| Changed-module tests (accountability, communications, engagement, identity, payments, search) + ModuleBoundaryTest | **GREEN — 124/124** (0 failures, 0 errors) |
| `web-citizen` `npm run build` | **GREEN** (bundle generated; one cosmetic SCSS selector warning, non-blocking) |

### Must-fix found and fixed during this review
`EngagementLifecycleSecurityIntegrationTest` was **RED — 3/10 failing** as committed (`activatePetition_asAdmin…`, `openSurvey_authenticated…`, `answerQuestion_authenticated…`). All three asserted `404` (gate passed, entity absent) but got **`500`**:

```
java.lang.IllegalStateException: No authenticated principal in context
    at com.taarifu.common.security.CurrentUser.requirePublicId(CurrentUser.java:58)
```

**Root cause (test defect, NOT a production hole).** The three lifecycle handlers attribute the actor via `CurrentUser.requirePublicId()` *before* the not-found lookup. `CurrentUser` reads the principal from the authentication **details** (the production shape the `JwtAuthenticationFilter` always sets). The tests used bare `@WithMockUser`, which sets authorities but leaves `details == null`, so `requirePublicId()` threw and `GlobalExceptionHandler` mapped the unhandled `IllegalStateException` to 500. In production the URL filter guarantees an authenticated, publicId-bearing principal on these handlers, so this path never 500s in prod.

**Fix (test-only, isolated to the engagement module test).** Replaced `@WithMockUser` on the three handler-reaching tests with a `RequestPostProcessor` (`authedWithPublicId(roles…)`) that authenticates with a `UsernamePasswordAuthenticationToken` carrying the `ROLE_*` authorities **and** a real `CurrentUser` in its details — the exact production shape, matching the established `ReportMediaAccessServiceTest`/`MediaServiceTest` pattern. The unauthenticated-401 and citizen-403 tests keep `@WithMockUser` (they assert before reaching the handler). After the fix: **10/10 GREEN.**

### Also corrected
- `PaymentsGatewayProperties` was missing `@ConstructorBinding`. The record has **two** constructors (canonical eight-arg + a backward-compatible seven-arg overload); Spring Boot constructor binding needs exactly one candidate, so without the annotation the binder finds no unambiguous constructor and the **whole application context** fails to start (a record has no default constructor). Annotated the canonical constructor; full-context ITs now boot. (This was an in-flight working-tree change; verified correct and committed.)
- Removed stray untracked scratch artifacts (`backend/cp.txt`, `scratch_*.log`) that were not gitignored.

---

## 2. Real-adapter safety (payments / FCM / SMS) — **PASS**

- **Stub stays the wired default; reals are opt-in.** Every real adapter is `@ConditionalOnProperty(havingValue = …)` with **no `matchIfMissing`**; the stubs (`LoggingMobileMoneyGatewayStub`, `LoggingPushSenderStub`, `LoggingSmsGatewayStub`, `LoggingEmailSenderStub`) carry `matchIfMissing = true`. A no-config dev/test/prod context boots on the stubs with zero external calls — dev/test boot is unchanged.
  - mobile-money: `provider = mpesa|tigopesa|airtelmoney|halopesa` selects a real rail; default `logging`.
  - push: `taarifu.communications.push.provider = fcm`; SMS: `…sms.provider = http`; email: `…email.provider = smtp`. Defaults `logging`.
- **No secrets/keys/creds in source.** HMAC secrets, base URLs, API keys, FCM service-account, SMTP creds, DLR shared secret all bind from `taarifu.*` env/secret-manager properties; blank on the stub. An active real adapter with a blank secret/URL **fails fast at construction** (`IllegalStateException`) rather than silently accepting forgeries or 500-ing the first request.
- **Webhook/callback signature verification is fail-closed.**
  - Mobile-money callbacks: HMAC-SHA256 over the **raw body**, **constant-time** compare (`MessageDigest.isEqual`), any parse/crypto error → `false` (no oracle). Settlement is additionally **never-trust-the-callback** (`verifySettled` re-confirms against the rail) and idempotent in reconciliation.
  - **SMS DLR (`SmsDeliveryReportController`):** shared-secret on a header, **constant-time** compare, **fail-closed** including "no secret configured", benign `200` on reject (no forgery oracle), idempotent + non-regressing on the correlation reference.
- **MSISDN/PII masked in logs.** All adapters mask the MSISDN (`+2557…masked`), never log the body (OTP / settlement payload), never log the secret; failure reasons are exception-class only.
- **D18 fence intact.** Payments credit the wallet only via the typed `TokenLedgerApi` after confirmed settlement; no balance is read on any binding path. The free path is always available (degrade-don't-crash on rail failure).

---

## 3. Architecture & boundaries — **PASS**

- **Cross-module edges are `api → api` only; no new sync cycle.** New synchronous edges all point feature→foundation or are sanctioned foundation→foundation reads:
  - `engagement`/`accountability` → `identity.api.ElectoralScopeApi` + `institutions.api.RepresentativeQueryApi` (electoral scope, D13) — feature→foundation, acyclic.
  - `identity` → `moderation.api.SubjectContentQueryApi`/`FlagSubjectType` (and `moderation.api.event.*`) for the auto-assist content provider. This is a **one-directional** `identity → moderation.api` edge; moderation does **not** import identity (verified), so **no cycle**. `api`/`event` only — confirmed legal by ModuleBoundaryTest 7/7.
  - `payments`/`search` backfill resolve via published `*Api` ports, never a sibling's `domain`/`infrastructure`. All cross-module references are by `UUID`.
- **accountability right-of-reply** respects the boundary: same-module FK to `rating(id)`; `representative_id` (institutions) and `author_account_id` (identity) are **public UUIDs, not FKs**. The ownership fence (`RepresentativeOwnershipPort`) is **deny-by-default** until the real institutions-backed adapter is wired — a missing adapter closes the self-reply path, never opens it. The curated on-behalf path is `ROLE_ADMIN`-gated. **No token column / no token read** on the reply path (§23 fence).
- **identity profile-content returns NO PII.** `ProfileLookupApi`/`ProfileSummary` and `ProfileSubjectContentQuery` expose **only** the public id and public display name — never `idNo`/`idHash`, phone, email, or any demographic. Anonymised/tombstoned and name-less profiles resolve to a tombstone label or `empty` (no resurrected PII). The content the moderation screen receives is transient (scored, then discarded). Assist-only: it can never approve/hide/sanction; the human pipeline is the floor.
- **search backfill respects visibility and is idempotent.** `SearchBackfillService` owns no projection/privacy logic — it delegates to each owner's `SearchBackfillSource`, which reuses the owner's **live producer fence** (e.g. engagement indexes only `PUBLIC_STATUSES`; DRAFT/private/anonymous trigger an idempotent REMOVE, never an index). Upsert is keyed `(entityType, publicId)` → safe to re-run. With zero registered sources today it is a clean no-op. Fault-isolated per source; logs counts only (no title/snippet/author). Admin-gated (`hasAnyRole('ADMIN','ROOT')`).

---

## 4. Scheduled beans gated — **PASS**

- `AnnouncementExpiryScheduler` is gated by `@ConditionalOnProperty("taarifu.communications.announcement-expiry.enabled", havingValue = "true")` with the toggle **defaulting to `false`** — the bean (and its `@Scheduled` method) does not exist in tests or in any context that should not own the sweep, so it **cannot race ITs** (mirrors the outbox-relay / digest gating). The module adds no second `@EnableScheduling` (scheduling enabled once, centrally). `AnnouncementExpirySchedulerTest` exercises the sweep logic directly. No other new `@Scheduled` beans introduced.

---

## 5. Migrations — **PASS**

- New migrations: `V180__accountability_promise_status_entry.sql`, `V181__accountability_rating_reply.sql`, `V182__audit_event_type_rating_reply.sql` — **all above V172**, **no duplicate version numbers** across the tree, no V180+ collision with any sibling branch visible here. Each is fully commented, forward-only, and matches its entity (full-context ITs pass with `ddl-auto=validate`). Same-module FK to `rating(id)`; cross-module refs by UUID, not FK (ARCHITECTURE §4.3).

---

## 6. web-citizen — **PASS**

- Separate Angular 18 standalone app (`/web-citizen`), distinct from `/web-admin`. **Swahili-first:** `defaultLocale: 'sw'`, `fallbackLocale: 'en'` (SW → EN → key chain); `sw.json`/`en.json` at parity (166 lines each). **No secrets:** `apiUrl: '/api/v1'` (relative, proxied); no hardcoded host, key, or credential in source. Installable PWA (manifest + ngsw-config). **Builds** (`npm run build`, exit 0).

---

## Prioritized must-fix / backlog

1. **CENTRAL NEED (P1, central/`common`) — SMS DLR route allow-list.** Add `POST /communications/sms/dlr` to `common.security.SecurityConfig#PUBLIC_POST_PATTERNS` (the `/ussd/gateway` + `/payments/webhook/**` precedent). Until then the DLR webhook is **401'd at the URL filter and unreachable anonymously**, so SMS delivery receipts never advance `Notification` state. The controller's secret check IS the authentication and is fail-closed; the only change needed is the one-line route entry. Correctly deferred out of this isolated worktree (it edits `common`). *Self-flagged in the controller Javadoc.*
2. **CENTRAL NEED (P2) — real `RepresentativeOwnershipPort` adapter** (institutions-backed "linked account of a representative"). Until shipped, the representative **self**-reply path is closed by the deny-stub (fail-safe); the curated on-behalf (ADMIN) path works end-to-end.
3. **CENTRAL NEED (P2) — `SearchBackfillSource` adapters per owner.** None ship yet, so the admin reindex is a clean no-op. Owners (reporting/engagement/communications/institutions/responders) must register adapters that reuse their live-producer fence for the backfill to populate the index.
4. **Backlog (P3) — outbox-driven wirings** remain `// TODO(wiring)` per ADR-0013 (routing→OWNER assignment, fan-out, takedown, async rewards) until the outbox increment; expected and not regressions.
5. **Backlog (P3, test hardening)** — consider a shared `authedWithPublicId(...)` request-post-processor on `AbstractHttpIntegrationTest` so future HTTP ITs whose handlers read `CurrentUser` don't reintroduce the bare-`@WithMockUser` → 500 trap fixed here.

## Decision

The branch is **decision-complete, buildable, and audit-clean** against the PRD and the locked decisions. With the engagement IT fixed and the stray artifacts removed, all required gates are GREEN. **Approve for integration**, tracking the SMS-DLR route allow-list (P1) as the only functional gap blocking the SMS-DLR feature reaching production.
