# Phase-2 Wave-1 — Final Integrated Verify + Architecture/Security Review

> **Reviewer:** David Okello (Principal Solution Architect), with the security/privacy fences checked against the locked decisions.
> **Branch:** `feature/phase2-wave1` · **HEAD:** `5fc499ca80d43b231d7ba428fd8ec5e981467bfe`
> **Date:** 2026-06-25
> **Scope:** the Phase-2 wave landed on this branch — payments (ADR-0015), privacy/PDPA-DSR (ADR-0016), search/discovery (ADR-0017), moderation auto-assist + transparency (ADR-0018, *in working tree, uncommitted*), USSD completion (ADR-0019).
> **Grounding:** PRD §18/§23.5/§25.1/§25.3, D18; ARCHITECTURE.md §3/§7/§8; ADR-0013 (cross-module), ADR-0014 (outbox); CLAUDE.md §3/§8/§12.

## Verdict: **PASS** (with non-blocking must-follow items)

The wave is decision-complete, buildable, and honours every locked decision and integrity fence. All ADRs are present and well-grounded; the code matches the ADRs; boundaries hold; the integrity fences (D18 payments, PDPA erasure, search visibility, moderation no-auto-removal) are enforced **by construction** and pinned by tests. The open items are the **CENTRAL NEEDs the ADRs themselves declare** (not silent gaps) plus a small number of hygiene follow-ups.

---

## 1. Build & test verification

| Gate | Command | Result |
|---|---|---|
| Package build | `./mvnw -q -DskipTests package` | **GREEN** (exit 0) |
| Boundary suite | `./mvnw -q test -Dtest=ModuleBoundaryTest` | **GREEN** — 7/7 tests |
| New-module unit tests | payments / privacy / search / moderation (auto-assist, scorer, transparency, severity) | **GREEN** — 67 tests run, 0 failures, 0 errors (one run incl. ModuleBoundaryTest) |
| Migration-validate (Testcontainers Postgres) | `PaymentsMigrationValidateIntegrationTest` (1/1), `SearchMigrationValidateIntegrationTest` (3/3) | **GREEN** — 4 tests, BUILD SUCCESS |

No compile failures, no test failures in any module. **No exact failures to report.**

### 1.1 Integration tests (Testcontainers)
Docker is available; the migration-validate ITs ran against a real PostgreSQL container and **passed** — the authoritative confirmation that V130 (payments `top_up`) and V146 (search `search_document`, incl. the generated `tsvector` column) validate against their entities under `ddl-auto=validate`. **Reviewer note:** before merge to `develop`, also run the privacy and moderation persistence ITs in CI so V140–142 and V154 get the same real-Postgres `validate` confirmation (the unit evidence is strong, but the IT is the authoritative gate; V154 in particular adds a CHECK constraint + partial index that should be IT-validated).

---

## 2. Migration version collisions (V130+)

**No collisions. Clean.** Each ADR's reserved Flyway block is respected and sits above the prior applied tail (…V121, V122):

| Version | File | Module / ADR | Reserved block |
|---|---|---|---|
| V130 | `V130__payments_top_up.sql` | payments / 0015 | V130–V139 |
| V140 | `V140__privacy_consent.sql` | privacy / 0016 | V140–V145 |
| V141 | `V141__privacy_data_subject_request.sql` | privacy / 0016 | V140–V145 |
| V142 | `V142__audit_event_type_privacy.sql` | privacy / 0016 | V140–V145 |
| V146 | `V146__search_document.sql` | search / 0017 | V146–V149 |
| V154 | `V154__moderation_item_auto_assist.sql` | moderation / 0018 | V154–V156 |

- `ls | uniq -d` over all `V*.sql` → **no duplicate version numbers**.
- USSD (ADR-0019) correctly adds **no migration** (it is `api → api` wiring + a read over an existing index).
- V142 widens the single `ck_audit_event_type` CHECK by **drop-and-recreate** (the V96/V103/V104 pattern) — metadata-only, append-only set, `audit_event` is INSERT-only; **the audit hash-chain is untouched**.

---

## 3. Architecture review (new modules + ADRs 0015–0019)

### 3.1 Clean api-port boundaries — no domain reach-in
`ModuleBoundaryTest.noModuleDependsOnAnotherModulesDomainOrInfrastructure()` is the predicate-based rule from ADR-0013 §3, and it uses `resideInAPackage("com.taarifu..")` — so it **automatically covers the new modules** (payments/privacy/search/ussd) with no edit needed. Verified GREEN.

Cross-module edges observed, all `api → api` (or consuming an `api.event` record) — **sanctioned**:
- `payments → tokens.api.TokenLedgerApi` (the only tokens touch; via `TokensApiWalletCreditAdapter`).
- `privacy → identity.api.UserAdminQueryApi` (active-role check, synchronous read).
- `identity → privacy.api.event.ErasureRequested` (consumes the event record asynchronously — no synchronous call back into privacy, so **no cycle**: privacy→identity.api sync + identity→privacy.api.event async is split by synchronicity, exactly ADR-0016 §7).
- `moderation → analytics.api.event.*` (the auto-assist analytics fact).
- `ussd → communications.api` + `ussd → geography.api` (ADR-0019).
- `search` depends on `common` only; siblings push to `search.api.SearchIndexApi` (owner→search) — no `search → sibling` edge, no cycle.

`privacy` has **zero infrastructure adapters** (matches ADR-0016 — no external service). `payments` adapters are all `@ConditionalOnProperty` (7 files) with logging-stub `matchIfMissing` defaults.

### 3.2 Outbox usage (ADR-0014)
- payments `ReconciliationService` appends `TopUpSucceeded` (ids/amounts only, no MSISDN/PII) **in the SUCCEEDED transaction**.
- privacy `DataSubjectRequestService` appends `ERASURE_REQUESTED` (ids only) **in the DSR-insert transaction** (atomic — a crash can't record the request without triggering the severing).
- `IdentityErasureHandler` consumes it as a `DomainEventHandler`, `REQUIRES_NEW`, idempotent via `Profile.isAnonymised()`.
- moderation `AutoAssistService` appends an `auto_moderation_triaged` `CivicActivityRecorded` fact in the triage transaction (ids/codes only).
- search `SearchVisibilityMaintenanceHandler` consumes `MODERATION_SANCTION_APPLIED` (idempotent bulk UPDATE to `STAFF`).
All payloads are ids/codes/enums only. **No PII in any event.**

### 3.3 No new sync cycles
Confirmed acyclic. The only synchronous cross-module reads are feature/foundation→foundation (`privacy→identity.api`, `payments→tokens.api`, `ussd→geography.api`/`communications.api`). The reporting↔responders and privacy↔identity reverse directions are split onto the outbox. The slice cycle-check inside `ModuleBoundaryTest` stays GREEN.

### 3.4 Prod-boot still holds
Every external-service adapter has a `matchIfMissing=true` / logging-stub default (payments `MobileMoneyGateway` → `LoggingMobileMoneyGatewayStub`; `WalletCreditPort` → `LoggingWalletCreditStub`; moderation `ContentSafety` → `HeuristicContentSafetyScorer`). With no rail/classifier configured the system boots with **zero external calls** and no credit is ever posted (integrity-safe default). No secrets in source — all gateway creds via `taarifu.payments.gateway.*` env, fail-fast on a blank secret when a real rail is active.

---

## 4. Security / privacy review

### 4.1 Payments fence (D18) — **PASS**
- The **only** effect of a settled top-up is `WalletCreditPort.creditPurchase` (a `PURCHASE`-type convenience credit). `ReconciliationService`'s collaborator set contains **no binding-action module**, never reads a balance for authorization, never grants role/vote/weight. Fence is structural and pinned by `ReconciliationServiceTest` (asserts the one effect is a credit of exactly the purchased token amount, and nothing else).
- **Webhook HMAC + idempotent + fail-closed:** `AbstractHmacMobileMoneyGateway.verifyCallbackSignature` does HMAC-SHA256 over the **raw bytes** with a **constant-time compare** (`MessageDigest.isEqual`), fail-closed (any parse/crypto error → false, no oracle). `PaymentWebhookController` verifies before any state change and returns a benign 200 on an invalid signature (no forgery oracle, no retry-storm incentive). Pinned by `HmacWebhookVerificationTest`.
- **Never-trust-the-callback:** `ReconciliationService.reconcile` calls `gateway.verifySettled(providerRef)` (out-of-band provider confirmation) before crediting; an unverified settlement leaves the row PENDING with no credit. Three idempotency layers: partial-unique `(provider, provider_ref)`, terminal-status short-circuit, stable per-top-up `credit_event_id`.
- **PII:** MSISDN masked in logs (`+2557…masked`); raw body never logged; HMAC secret never logged.

### 4.2 PDPA erasure — **PASS**
- **Crypto-shred:** `IdentityErasureHandler` nulls the encrypted `idNo` ciphertext **and** the `idHash` blind index (the strongest per-row severing of the national/voter ID available today), nulls the rest of profile/user PII, tombstones names, phone → non-reusable token (one-account permanence, D15), deletes `ProfileLocation`s.
- **Audit hash-chain intact:** erasure **appends** an `IDENTITY_ERASED` tombstone — it never mutates or deletes an audit row. V142 widens the CHECK by metadata-only drop-recreate; `audit_event` stays INSERT-only. Chain is extended, never broken (§25.1).
- **No PII leak:** `ErasureRequested` carries `subjectPublicId` + `dsrPublicId` only; the handler reads no PII from the payload, never decrypts/logs the plaintext ID; logs by subject reference only.
- **Idempotent** (at-least-once safe via `isAnonymised()`), **own transaction** (`REQUIRES_NEW`), **active-role constraint** blocks self-erasure of an accountability trail (via `identity.api.UserAdminQueryApi`).
- The default export **omits the raw ID number** (returns type + verification state) pending Legal sign-off — correctly routed to Legal, not guessed.

### 4.3 Search visibility — **PASS**
- The visibility gate is a **server-side query predicate** (`(:includeStaff = TRUE OR d.visibility = 'PUBLIC')`), driven by the live role set resolved from `CurrentUser`; staff = `MODERATOR`/`ADMIN`/`ROOT`. A guest/citizen can never see a `STAFF` row — it is **filtered out**, not 403'd per row (anti-enumeration, PRD §18). Pinned by `SearchQueryServiceTest`.
- Empty/blank query → empty page (no corpus dump). The pushed projection carries public display fields + opaque ids only; `authored_by_account_id` is used solely for visibility maintenance and never returned. Suspended-author content drops to `STAFF` via the idempotent outbox handler.
- The native FTS query re-states `deleted = FALSE` explicitly (`@SQLRestriction` does not apply to native SQL) — **correct**, avoids leaking soft-deleted rows.

### 4.4 Moderation auto-assist — **PASS**
- **No auto-removal:** `AutoAssistService` has **no path** to a `ModerationAction` — it can only open/escalate/mark a `ModerationItem` (hold-and-prioritise) and emit analytics. Only the human, D16-guarded `ModerationQueueService.takeAction` can approve/hide/remove/suspend. Pinned by `AutoAssistServiceTest`.
- **Anonymity preserved:** `subjectAuthorAccountId` is nullable (anonymous sensitive reports, D-Q1) and the later D16 guard is vacuously satisfied; the analytics fact carries no author identity, no content text.
- **Degradation (EI-18):** no provider / below-threshold → no hold → all-to-human. The heuristic is the `matchIfMissing` default; conservative threshold (default 0.80).
- **Transparency report** is `@PreAuthorize("hasAnyRole('ADMIN','ROOT','MODERATOR')")`, aggregate counts keyed on codes/enums only — no person/location/body/moderator identity.

### 4.5 Secrets in source — **PASS**
No secrets committed. Payments HMAC secret + base URL from env (`taarifu.payments.gateway.*`), fail-fast on blank when a real rail is active. Stubs/`@ConditionalOnProperty` mirror the existing notification-adapter discipline.

---

## 5. Prioritized must-fix / must-follow

**P0 — blocking before the real flows run end-to-end (declared CENTRAL NEEDs, not silent gaps):**

1. **`common.security.SecurityConfig` allow-listing (ADR-0015 §3, ADR-0017 §4).** `PUBLIC_POST_PATTERNS` does NOT yet include `/payments/webhook/**`, and `PUBLIC_GET_PATTERNS` does NOT include `/search/**`. Until the security-config owner adds them:
   - the payment webhook is HMAC-secured but **unreachable anonymously** (aggregator callbacks will be 401'd) — payments cannot settle end-to-end.
   - `GET /search` is **auth-gated at the URL filter** despite its `permitAll()` method annotation — guests cannot use public discovery. *(Fails safe: more restrictive than intended, but the feature is non-functional for guests.)*
   These edits belong to the kernel owner (modules correctly did not edit `common.security`).
2. **`tokens.api.TokenLedgerApi.topUp(...)` (ADR-0015 §4).** Not yet present. `TokensApiWalletCreditAdapter` currently invokes it **reflectively** and **fails closed** (`SERVICE_UNAVAILABLE`) if absent — fence-safe and not the default bean (logging stub is the default), but a reflection bridge. The tokens owner must add the typed fence-safe `PURCHASE` credit; then replace the reflective call with a direct typed call (must-fix-on-landing, see P2-1).

**P1 — correctness/coverage hardening (do before/with merge to develop):**

3. **Confirm the migration-validate ITs GREEN in CI** (`PaymentsMigrationValidateIntegrationTest`, `SearchMigrationValidateIntegrationTest`) — the authoritative `ddl-auto=validate` gate for V130/V140–142/V146/V154 against a real Postgres. Do not merge without this confirmed.
4. **Tighten `ModuleBoundaryTest.commonKernelDependsOnNoFeatureModule`'s explicit module list** to include `payments`, `privacy`, `search`, `ussd`. Today `common` is verified clean (no references found), but this specific rule's allow-list is hard-coded and would NOT catch a future `common → payments/privacy/search/ussd` regression. The newer predicate rule covers them; this older rule is the gap. Low risk, cheap fix.

**P2 — hygiene / on-landing follow-ups:**

5. **Replace the reflective `topUp` bridge** in `TokensApiWalletCreditAdapter` with a typed call once CENTRAL NEED #2 lands (the ADR says so explicitly). Reflection is the temporary isolation crutch.
6. **Commit the in-tree ADR-0018 work.** The moderation auto-assist + transparency change (ADR-0018, V154, `AutoAssistService`, `TransparencyReportService`, `ContentSafety` port/adapter, the modified `ModerationItem`/repositories/`SeverityPolicy`/tests, `application.yml`) is **uncommitted in the working tree**. It is build- and test-GREEN; commit it as its own `feat(moderation): …` so the wave is captured atomically.
7. **Wave-wide producer wiring debt (by design, tracked).** search index `upsert` calls, `AutoAssistService.triage` call-sites, the USSD area-alert forward (gated on identity account→profile), and the per-module erasure handlers/export contributors are all declared `// TODO(wiring)` CENTRAL NEEDs. Safe (index empty / no-op until wired), but track them so discovery, auto-screening, and full-coverage erasure actually light up.

---

## 6. Summary

- **Architecture:** clean. New modules respect ADR-0013 boundaries (auto-covered by the predicate boundary rule), use the outbox correctly, introduce no cycles, and keep prod-boot stub-defaulted and secret-free.
- **Security/privacy:** all four fences hold by construction and are test-pinned — payments D18 (tokens never buy democratic weight), PDPA crypto-shred + intact hash-chain, search server-side visibility, moderation no-auto-removal + anonymity.
- **Migrations:** no collisions; reserved blocks honoured; `validate`-aligned (confirm ITs in CI).
- **Open items** are the ADR-declared CENTRAL NEEDs (security-config allow-list, `TokenLedgerApi.topUp`) plus small hygiene fixes — none is a hidden defect.
