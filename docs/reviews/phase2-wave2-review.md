# Phase-2 Wave-2 — Final Integrated Verify + Architecture/Security Review

> **Verdict: PASS** (no must-fix blockers). · Reviewer: Solution Architect (David Okello) · 2026-06-25
> **Branch:** `feature/phase2-wave2` · **Baseline:** develop `0a15baa` (MVP + wave-1 merged) · **Tip reviewed:** `70d616e` + the in-worktree moderation auto-assist wiring (committed by this review).
> **Grounding:** ADR-0013 (cross-module integration), ADR-0014 (outbox/bus), ADR-0015 (payments + wave-2 addendum: reconciliation/refund/admin-query), ADR-0016 (PDPA DSR), ADR-0017 (search), ADR-0018 (moderation auto-assist + wiring-update). ARCHITECTURE.md §3/§7/§8/§10; CLAUDE.md §3/§8/§12.

## 1. Build & test status — GREEN

| Gate | Result |
|---|---|
| `./mvnw -q -DskipTests package` | **PASS** (exit 0) — full backend compiles incl. uncommitted moderation files |
| `./mvnw -q test -Dtest=ModuleBoundaryTest` | **PASS** — 7 tests, 0 failures, 0 errors |
| `PaymentsMigrationValidateIntegrationTest` (Flyway+validate, V131) | **PASS** — 2 tests (entity ⇔ DDL agree) |
| Wave-2 DSR/payments/digest unit+slice (10 classes) | **PASS** — 36 tests, 0 failures |
| Moderation auto-assist (`FlagServiceTest`, `SubjectContentResolverTest`, `AutoAssistServiceTest`) | **PASS** — 13 tests, 0 failures |

Per-class: RefundService 8 · TopUpReconciliationJob 4 · PaymentQueryService 3 · ReportingErasureHandler 2 · ReportingExportContributor 3 · EngagementErasureHandler 3 · MediaErasureHandler 2 · AccountabilityErasureHandler 3 · CommunicationsErasureHandler 3 · DigestService 5 · FlagService 5 · SubjectContentResolver 4 · AutoAssistService 4. **No failures in any module.**

## 2. Wiring review

**Search index populated only from published events / owner-push (no source-module reach-in).** `com.taarifu.search` imports **no** sibling `domain`/`infrastructure` (grep clean). Population is the inbound `SearchIndexApi` (owner→search push, ADR-0017 §1) via `SearchIndexService`; the only event consumer is `SearchVisibilityMaintenanceHandler` on `MODERATION_SANCTION_APPLIED` (ids/enum only). No `search → sibling` sync edge. **PASS.** (No wave-2 change to search; restated as still-holding.)

**Moderation auto-assist is assist-ONLY (no action path, anonymity intact).** `AutoAssistService` depends on `ContentSafety`, `ModerationItemRepository`, `SeverityPolicy`, `ClockPort`, `OutboxWriter` — and on **nothing that can write a `ModerationAction`**. `triage()` can only `ModerationItem.markAutoAssisted` (open/escalate/hold for a human) + append an `auto_moderation_triaged` fact. Only `ModerationQueueService.takeAction` (human, D16-guarded) can action. The wave-2 wiring-update makes the screen live inside `FlagService.flag` via `SubjectContentResolver → SubjectContentQueryApi` (a registry in `moderation.api`; owners implement it — moderation imports **no** content owner). Degradation: no owner port registered ⇒ `Optional.empty()` ⇒ screen skipped, item still goes to a human (EI-18 floor); the flag is never blocked/failed by auto-assist. **Anonymity intact:** transient text is scored then discarded (never persisted/logged); the analytics fact carries `actorRef=null` + `outcome=<top signal>` — no author identity; an author-less (anonymous sensitive) subject passes `null` and the D16 grain is vacuously satisfied. **PASS.**

**PDPA erasure fan-out crypto-shreds/anonymises per module WITHOUT breaking the audit hash-chain; ACCESS export aggregates all contributors.** All six erasure handlers (identity wave-1 + reporting/engagement/media/accountability/communications wave-2) register on `ErasureRequested.EVENT_TYPE`; the in-process `EventDispatcher` fans one event to all of them **sequentially, in registration order** (so no concurrent audit appends per fan-out). Each wave-2 handler runs in its own `REQUIRES_NEW` tx, **severs a reference / soft-deletes routing PII** (reporting: reporter+actor; engagement: signatures/responses/questions; media: uploader linkage [EXIF/geo stripped at promote-time]; accountability: rating author; communications: device tokens + notification prefs), is **idempotent** (re-pass finds nothing live → no-op, no second tombstone), logs **counts + subject ref only**, and **appends** one `SUBJECT_DATA_ERASED` audit row — it **never mutates** an audit row, so the hash-chain is extended, never broken (V163 widens the CHECK domain to admit the code). The de-identified civic record survives (§25.1). `SubjectDataExportService` injects `List<SubjectExportContributor>` and composes **all** registered sections (per-contributor try/catch isolates a faulty one); wave-2 adds reporting/engagement/media/accountability to the wave-1 identity+consent set. **PASS.**

**Cross-module edges are api/outbox only — no new sync cycles.** Every cross-module import introduced by wave-2 source: `{reporting,engagement,media,accountability,communications} → privacy.api[.event]` (consuming the `ErasureRequested` event record — async, feature→foundation, ADR-0016 §5); `payments → tokens.api` + `tokens.domain.model.enums.WalletOwnerType` (the sanctioned `TokenLedgerApi` command port + the `domain.model.enums` value-type carve-out (c) in `ModuleBoundaryTest`, the documented `topUp`/`refund` precedent). No new **synchronous** cross-module edge; privacy consumes nobody synchronously (it only publishes the event). `ModuleBoundaryTest` GREEN confirms acyclicity mechanically. **PASS.**

**Prod-boot still holds (every adapter/scheduler stub/toggle defaults safe).** `wallet-reversal.adapter=logging` (matchIfMissing — moves no real tokens; tokens-api is opt-in and fails-fast, never silent no-op, if selected before the CENTRAL NEED lands). `digest.enabled=false` default (the `@Scheduled DigestService` bean is `@ConditionalOnProperty havingValue=true` with no matchIfMissing → not even instantiated). `reconciliation.enabled=true` default but the `MobileMoneyGateway` defaults to the logging stub (`verifySettled→false`), so on a fresh prod boot with no rail configured nothing is ever credited (stale rows expire to FAILED). **PASS.**

## 3. Security/privacy fences

- **Payments D18 (refunds never touch democratic weight) + HMAC + idempotency.** `RefundService`/`TokensApiWalletReversalAdapter`/`TopUpReconciliationJob` have **no** binding-action dependency and read **no** balance for authz. A refund reverses only convenience tokens via `WalletReversalPort` (REFUND ledger type), idempotent on a stable per-top-up `reversal_event_id`; a `CHECK` binds `reversal_event_id` to `REFUNDED` at the DB. Webhook stays HMAC-verified/fail-closed (unchanged); settlement is reconciliation-driven (`reconcile` is the single credit path — reused verbatim by the job, never trust-the-callback). **PASS.**
- **Notifications / digest — no PII.** `DigestService` is a lean feed-count nudge; erasure handler logs counts + subject ref only and **never logs a device-token string** (a secret). **PASS.**
- **Search visibility.** Unchanged in wave-2; server-side `STAFF`-only predicate for staff roles, `PUBLIC` for others (anti-enumeration). **PASS.**

## 4. Migration collisions across V160–V174 (and the whole set)

No duplicate Flyway version numbers anywhere (`uniq -d` empty). Wave-2 migrations: **V131** `payments_top_up_refund_void` (within the reserved payments block V130–V139; ADR-0015 addendum supersedes the brief's "V166–V169" with the module's own documented reservation — accepted), **V163** `audit_event_type_subject_data_erased` (widens the audit CHECK, append-only), **V170** `communications_digest_notification_type`. Wave-1 (V130/V140–142/V146/V154) already on develop. **No collision. PASS.**

## 5. New `@Scheduled` beans that could race ITs

Two new in wave-2, both correctly gated:
- `DigestService.@Scheduled` — `@ConditionalOnProperty(taarifu.communications.digest.enabled, havingValue=true)` (no matchIfMissing) → off by default, absent in tests.
- `TopUpReconciliationJob.@Scheduled` — `@ConditionalOnProperty(...reconciliation.enabled, matchIfMissing=true)` but **disabled in `application-test.yml`** (`enabled: false`, with a comment naming the anti-race purpose).

(Pre-existing `OutboxRelay` gated by `taarifu.outbox.relay.enabled`; `OutboxMaintenance` is hourly/benign.) **PASS.**

## 6. Observations (non-blocking — not wave-2 regressions)

- **(Pre-existing) Audit hash-chain has no serialization lock.** `AuditEventWriter.persist` does read-head → compute → save in a `REQUIRES_NEW` tx without `SELECT … FOR UPDATE`/serializable. Concurrent audit writers across threads/instances could fork the chain. Wave-2 does **not** regress this: erasure handlers append sequentially within one fan-out (dispatcher is single-threaded per event). Flag for a future hardening increment (a chain-head advisory lock or a dedicated serialized writer) before multi-instance audit volume grows — owner: security-privacy-engineer.
- **(Carried) CENTRAL NEEDs from ADR-0015/0016 remain open** (see below) — none blocks boot; all degrade safely.

## 7. CENTRAL NEEDS (owned outside this worktree)

1. `tokens.api.TokenLedgerApi.refund(WalletOwnerType, UUID, long, String)` (fence-safe REFUND, idempotent on reference) — until then `payments.wallet-reversal.adapter` stays `logging` (the tokens-api adapter binds reflectively and fails-fast if selected early). **Owner: tokens.**
2. `common.security.SecurityConfig` permit `POST /payments/webhook/**` and `/search/**` GET (carried from wave-1). **Owner: common/security.**
3. Owner modules to call `SearchIndexApi.upsert(...)` on their write paths (search is correct-but-empty until then) and to publish `SubjectContentQueryApi` impls so the auto-assist screen runs on more subject types. **Owners: reporting/institutions/responders/communications/engagement.**
4. `analytics` enum to gain `AUTO_MODERATION_TRIAGED` (additive; until then the handler drops it as a no-op). **Owner: analytics.**

## 8. Prioritized must-fix

**None.** Wave-2 is decision-complete, boundary-clean, fence-preserving, and GREEN. Recommended (non-blocking) follow-ups: (a) the audit hash-chain serialization hardening (§6), (b) land CENTRAL NEED #1 to flip refunds off the logging stub.
