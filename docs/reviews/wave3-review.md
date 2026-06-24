# Wave-3 Hardening — Integrated Security Review & Final Verify

> **Reviewer:** Salim Juma (Senior Security & Privacy Engineer) · **Date:** 2026-06-24
> **Branch:** `feature/wave3-hardening` (off `develop@2a93f8d`) · isolated worktree
> **Scope:** the five wave-3 hardening increments landed on top of `develop@2a93f8d`, each closing a
> wave-2-review must-fix or a THREAT-MODEL residual risk:
> 1. `1087887` — USSD webhook aggregator auth (shared-secret filter) + per-MSISDN rate-limit (**P2-1 / TR-1**);
> 2. `8401873` — prod-safe, config-gated first-admin bootstrap (**G16 / TR-5**);
> 3. `6a5a13f` — admin-gated, audited DLQ list + replay endpoints (**P3-1**);
> 4. `c0ac9e6` — analytics civic-activity emission from reporting/moderation/responders/engagement (M15);
> 5. `b7eeaac` — outbox retention purge made transactional (`@Modifying` needs a tx).
> **Grounding:** PRD §7.1/§14/§15/§18 + Appendix E; ARCHITECTURE §3.2/§3.3/§5.1/§6.2/§9;
> ADR-0013 (cross-module integration); ADR-0014 (outbox/event-bus); THREAT-MODEL TB-3/TB-4/TB-10/TR-1/TR-3/TR-5;
> wave2-review P2-1/P3-1; CLAUDE.md §3/§8/§12.

## Verdict: **PASS** — all five review areas clean; build + boundary + new unit tests GREEN.

No production-security regression found. No secrets in source, no PII into events/DTOs/logs, every new admin
surface is deny-by-default and audited, the outbox purge transaction fix is correct, and the prod-bootstrap is
opt-in / idempotent / fail-safe with no hardcoded credential. **Two CENTRAL NEEDS** remain (both correctly
deferred under worktree isolation — this module must not edit `SecurityConfig`): register `POST /ussd/gateway`
on the public allow-list, and (production) swap the in-memory rate-limiters for Redis-backed adapters. Until the
USSD path is centrally registered the channel is **fully closed** (fail-safe), so neither blocks this merge.

---

## 1. Final verify results

| Step | Command | Result |
|---|---|---|
| Package (no tests) | `./mvnw -q -DskipTests package` (in `backend/`) | **GREEN** (exit 0) |
| Boundary | `./mvnw -q test -Dtest=ModuleBoundaryTest` | **GREEN** (exit 0) — the USSD→common, admin→common.outbox, and producer→analytics.api edges keep the graph acyclic; `domain`/`infrastructure` stay encapsulated; cross-module references go through `<callee>.api` only |
| New unit tests | `UssdGatewaySecretFilterTest, UssdGatewayControllerTest, InMemoryUssdGatewayRateLimiterTest, OutboxAdminServiceTest, AnalyticsEventHandlerTest` | **GREEN** (exit 0) — the WARN lines in output are the *expected* fail-closed rejection logs asserted by the filter test |
| Producer unit tests (emit didn't regress logic) | `PetitionServiceTest, SurveyServiceTest, ModerationQueueServiceTest, ReportServiceTest, ResponderAdminServiceTest, RoutingHandlerTest` | **GREEN** (exit 0) — routing logs reference report/responder/ward/category public ids only, no PII |

**Exact failures:** none. Working tree clean (all five increments already committed on the branch). The
PostGIS-backed ITs (`AdminOutboxSecurityIntegrationTest`, `ProdAdminBootstrapIntegrationTest`,
`OutboxMaintenancePurgeIntegrationTest`) were not re-executed in this pass (Testcontainers); their source was
reviewed and is sound — recommend they run in CI on the container stage before the `develop` merge.

---

## 2. USSD webhook — aggregator auth + per-MSISDN rate-limit (P2-1 / TR-1) — **PASS**

Closes wave2-review P2-1. The open, no-OTP T1 account-creation + report-filing surface is no longer drivable by
arbitrary internet callers. **No open provisioning surface.**

- **Aggregator authentication — fail-closed, constant-time.** `UssdGatewaySecretFilter` verifies a configured
  shared-secret header on `POST /ussd/gateway` *before* the controller runs or the body is parsed. When **no
  secret is configured** (`UssdGatewayProperties.isConfigured()==false`) the webhook is treated as **disabled**
  and every call is rejected — an un-provisioned aggregator link is a closed one, never fail-open (CLAUDE.md §3,
  PRD §18). The compare is `MessageDigest.isEqual` over SHA-256 digests, so neither a wrong value nor a length
  mismatch leaks a timing oracle. Self-scopes to `POST /ussd/gateway` via `shouldNotFilter`, so it cannot
  interfere with the rest of the API (proved by `nonGatewayPath_passesThroughUntouched`).
- **No secret / no PII in logs (S-4).** A rejection logs only a non-PII reason code (`NOT_CONFIGURED` /
  `MISSING_SECRET` / `BAD_SECRET`) — never the presented/expected secret, never the request body (which carries
  the MSISDN). The rejection body is the plain-text USSD wire form (`END …`, Swahili-first, GSM-7-safe), not the
  JSON envelope and not a stack trace; the test asserts the body never echoes the secret.
- **Per-MSISDN rate-limit — two independent caps.** The controller hashes the MSISDN (`crypto.blindIndex`)
  *before* touching the limiter, so the limiter (and any metric/log it touches) never sees a raw phone (S-4,
  PDPA). `InMemoryUssdGatewayRateLimiter` enforces a keypress cap (`MAX_TURNS=30 / 1 min`) **and** a tighter
  new-dialogue cap (`MAX_NEW_SESSIONS=5 / 10 min`) that gates exactly the account-creation trigger (empty
  `text` = first hit). A breach returns a plain `END` throttle line and **does not invoke the state machine**
  (`turnCapTripped_…doesNotInvokeMachine`). Sliding windows are pruned per check; idle entries are dropped, so
  memory is bounded.
- **Defence-in-depth posture is fail-safe.** The filter sits at `HIGHEST_PRECEDENCE` (ahead of Spring Security),
  but `POST /ussd/gateway` is **deliberately not on the central public allow-list**, so `SecurityConfig`'s
  `anyRequest().authenticated()` 401s it regardless. The channel is therefore **fully closed in prod today** —
  it cannot be accidentally opened. Enabling it is a single, auditable central change (see CENTRAL NEEDS).

→ **CENTRAL NEED (owed, not a blocker for this merge):** register `POST /ussd/gateway` on the kernel
`SecurityConfig` public allow-list. Only *then* is the channel reachable — and it is already paired with
aggregator auth (this filter) + per-MSISDN rate-limit. Keep `TAARIFU_USSD_GATEWAY_SECRET` unset until the
aggregator link is provisioned (fail-closed). Production also wants the Redis-backed limiter (TR-3).

## 3. Prod-safe first-admin bootstrap (G16 / TR-5) — **PASS**

`ProdAdminBootstrap` closes the chicken-and-egg of a fresh prod DB with zero accounts, **without ever shipping a
default credential** (contrast the correctly `@Profile("dev")`-only `DevAdminSeeder`).

- **Config-gated, not profile-gated.** `@ConditionalOnProperty("taarifu.bootstrap.admin.enabled"="true")` — the
  bean is *not instantiated at all* unless an operator deliberately opts in (`application.yml` defaults the flag
  to `false`). No runner, no account, when off.
- **NO hardcoded creds — fail-safe.** Phone and password come only from env
  (`TAARIFU_BOOTSTRAP_ADMIN_PHONE` / `_PASSWORD`, blank defaults in `application.yml`). Enabled-but-unconfigured,
  or a weak password (`isStrongPassword`: ≥12 chars, mixed classes), is a **WARN + no-op** — there is
  deliberately no fallback constant (no equivalent of the dev seeder's published test password). The password is
  BCrypt-hashed before storage and **never logged**; the operator banner logs the phone (an account key) and the
  enrolment-file path only.
- **Idempotent + one-account-per-person.** First guard: "any ACTIVE ROOT already exists?" → skip — can never mint
  a second super-administrator, safe to leave enabled across reboots. The configured-phone-already-taken case is
  likewise a no-op (D11/D15). Missing role catalogue (un-migrated DB) → WARN + no-op, never fabricates rows.
- **Fails safe, never fails the boot.** Every guard is a logged no-op, never a thrown exception that would take
  the platform down; an enrolment-file write failure is caught and logged (the account already exists; reset MFA
  to recover).
- **No MFA deadlock, secret stays out of the log (S-4).** Provisions + activates a fresh 160-bit TOTP secret so
  first login is not deadlocked by the staff-MFA gate, and surfaces the `otpauth://` URI strictly out-of-band to
  a `0600` file (best-effort on non-POSIX FS, with a WARN) — never to the application log. Banner instructs
  enrol → delete file → rotate password → disable the flag.
- **Audited (R, L-1).** A successful bootstrap appends `ROLE_GRANTED` (actor = `SYSTEM_ACTOR` sentinel, subject =
  the new ROOT, reason `ROOT:BOOTSTRAP`) — references only, no PII. The bootstrap admin carries no national/voter
  ID, so nothing field-encrypted/blind-indexed is written.

## 4. Admin DLQ list + replay endpoints (P3-1) — **PASS**

Closes wave2-review P3-1. The DLQ ops surface is **ADMIN-gated and audited with no PII**.

- **Deny-by-default, ADMIN/ROOT only.** `AdminOutboxController.listFailed` and `.replay` both carry
  `@PreAuthorize("hasAnyRole('ADMIN','ROOT')")`. `AdminOutboxSecurityIntegrationTest` proves anonymous → 401,
  CITIZEN → 403, MODERATOR → 403, ADMIN → 2xx — it **fails closed** if a `@PreAuthorize` were removed. MFA is
  upstream (an ADMIN/ROOT access token is only minted after the TOTP step).
- **No PII surfaced.** The list returns `FailedEventDto` (id / eventType / attempts / failedAt / age) — **never**
  the payload or the redacted `last_error` text. The kernel `OutboxReplayService.FailedOutboxView` projection
  drops both at the source; the `OutboxEvent` entity never crosses the module boundary (admin consumes only the
  published `OutboxReplayService` seam — ARCHITECTURE §3.2).
- **Replay is FAILED-pinned, idempotent, bounded, audited.** `OutboxReplayService` / `OutboxEventRepository`
  pin `WHERE status='FAILED'` on every requeue, so a `PROCESSED` row is never reset (no re-fire of a delivered
  effect) and an in-flight `PENDING` row is never disturbed; a re-queued row is reset to a clean slate. The
  by-window replay is capped (`purgeBatchSize` default). Every replay is audited via the new append-only
  `OUTBOX_DLQ_REPLAYED` type (added at the *end* of the enum — additive, taxonomy not broken), with the acting
  admin read from the **security context (never a body field)** and refs + counts only, never PII. The list is a
  read and is correctly not audited.

## 5. Analytics civic-activity emission (M15) — **PASS**

The emission **carries no PII and is non-blocking**.

- **PII-free by construction.** `CivicActivityRecorded` (in `analytics.api.event`) has no field for free text, a
  precise GPS point, or any direct identifier. Reviewed **all six** emit sites (ReportService via the
  `.of(...)` factory; PetitionService, SurveyService, ModerationQueueService, ResponderAdminService,
  RoutingHandler via the canonical constructor): **every site passes `actorRef = null`** — never the account
  UUID, name, phone, or ID. Geo/category dimensions carry only ward-or-coarser area ids and category ids; the
  `outcome` is a controlled-vocabulary code (e.g. `OWNER`, `BINDING`, the moderation action). So a PII leak into
  the queryable/replayable outbox is unrepresentable, satisfying THREAT-MODEL TB-10 and Appendix E.4.
- **Non-blocking, off the citizen path.** Each producer appends the event to the **transactional outbox in its
  own domain transaction** (`outboxWriter.append(...)`), so the civic write and the analytics intent commit
  atomically and the analytics record runs **asynchronously on the relay thread** — a slow/failed analytics
  write never touches the actor's request (Appendix E "never on the critical path"; PRD §15).
- **Idempotent + forward-compatible.** `AnalyticsEventHandler` dedups on the outbox `eventId`
  (`uq_analytics_event_event_id`), so an at-least-once redelivery records the fact exactly once. An unknown
  string-coded dimension is a **no-op success** (dropped, not DLQ'd), so a newer producer can emit a value an
  older analytics build ignores. The handler reads no identifier and logs none.
- **Integrity fence intact (D18 / §23.5).** The petition/survey emits are passive side-records emitted *after*
  the binding act completes; they neither read a token balance nor influence the signature/response count.
  Tokens still never buy democratic weight.

## 6. Outbox retention purge now transactional (`b7eeaac`) — **PASS**

The `@Modifying` bulk delete needs an active transaction, and a custom `@Query` method is **not** wrapped in one
by default (unlike generated CRUD), so without a boundary it threw `InvalidDataAccessApiUsageException`.

- **Correctly placed: one short transaction *per batch*.** `@Transactional` lives on the repository method
  `deleteProcessedOlderThan` (reached through the Spring Data proxy on each loop iteration), **not** on the
  `OutboxMaintenance.purgeProcessed` loop method. This is the right call on two counts: (a) annotating the loop
  method would wrap the whole drain in **one long-lock transaction** — the regression this avoids; and (b) a
  `@Transactional` method invoked from within the same bean would not even be proxied (self-invocation pitfall).
  Each batch commits before the next iteration → bounded row locks, incremental drain.
- **FAILED rows are never purged.** The predicate pins `status='PROCESSED'` explicitly (not just an age cutoff,
  which FAILED rows also carry), so the DLQ is preserved for diagnostics/replay.
- **Multi-instance safe.** Idempotent, bounded bulk delete on a time cutoff; concurrent instances delete disjoint
  eligible sets (or find nothing) — no correctness hazard. Logs by count only, never row contents (PRD §18).

---

## 7. Prioritized must-fix / owed items

| # | Pri | Finding | Owner | Action |
|---|---|---|---|---|
| W3-1 | **P2 (CENTRAL)** | `POST /ussd/gateway` not yet on the kernel public allow-list, so the channel is closed (fail-safe). The module-side controls (secret filter + per-MSISDN rate-limit) are landed and correct. | kernel `SecurityConfig` + ops | Register the path; keep `TAARIFU_USSD_GATEWAY_SECRET` set from the secret manager before exposure. Tracks THREAT-MODEL TR-1 / G18 (Phase-2 channel gate, not MVP). |
| W3-2 | **P2 (CENTRAL)** | USSD + auth rate-limiters are **in-memory** (lost on restart, not shared across instances). Real, testable seam exists behind `UssdGatewayRateLimiter` / `AuthRateLimiter` via `@ConditionalOnMissingBean`. | Eng/SRE | Swap in Redis-backed adapters for production. Tracks TR-3 / G14. |
| W3-3 | P3 (track) | The container-backed ITs (`AdminOutboxSecurityIntegrationTest`, `ProdAdminBootstrapIntegrationTest`, `OutboxMaintenancePurgeIntegrationTest`) were source-reviewed, not re-run in this isolated pass. | CI / QA | Ensure they run GREEN on the Testcontainers stage before the `develop` merge. |
| W3-4 | P4 (track) | Bootstrap TOTP enrolment file is `0600` best-effort; on a non-POSIX FS (Windows) it is written unrestricted with a WARN. Acceptable (prod is Linux), noted for ops. | Eng/SRE | Confirm prod hosts are POSIX; operator must delete the file after enrolment regardless. |

### Central integration needs (not editable under worktree isolation)
- **W3-1** Register `POST /ussd/gateway` on `SecurityConfig`'s public allow-list (paired with the already-landed
  aggregator auth + per-MSISDN rate-limit).
- **W3-2** Redis-backed `UssdGatewayRateLimiter` + `AuthRateLimiter` for multi-instance production.

## Decision

**PASS.** All five wave-3 increments are sound: the USSD webhook is authenticated (fail-closed, constant-time)
and per-MSISDN rate-limited with no open provisioning surface; the prod-bootstrap is config-gated, idempotent,
fail-safe, with no hardcoded credential and no secret in the log; the admin DLQ endpoints are ADMIN-gated and
audited with no PII; analytics emission carries no PII and is non-blocking off the citizen path; and the outbox
purge is transactional per batch (short locks, FAILED preserved). Build, `ModuleBoundaryTest`, and the new unit
tests are GREEN. The only open items are the two CENTRAL NEEDS (USSD path registration, Redis rate-limiters) —
both already tracked in the THREAT-MODEL register (TR-1 / TR-3), correctly deferred to the kernel, and neither a
blocker because the USSD channel stays fully closed until the central change lands.
