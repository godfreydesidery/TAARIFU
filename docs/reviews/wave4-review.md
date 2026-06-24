# Wave 4 — Final Verify + Security/Architecture Review

> **Reviewer:** Salim Juma (Security & Privacy Engineer)
> **Branch:** `feature/wave4-completion` @ `790154d` (worktree off `develop` `fcc2363`)
> **Scope reviewed (4 commits over `fcc2363..790154d`):** FCM device-token registry + push targeting; full national geography seed (V105–V109); media attachment upload pipeline (presigned) wired to reporting (V121); Redis-backed multi-instance rate-limiters (auth + USSD); core civic-flow E2E + smoke tests.
> **Grounding:** PRD §15/§18/§21 (EI-5/EI-8), §23.5/§25.3 (privacy/fence), §27.5 (launch gate); ADR-0005 (Flyway owns schema, `ddl-auto=validate`); ADR-0009 (Testcontainers); ADR-0013 (cross-module via `..api..` ports); ADR-0014 (outbox); CLAUDE.md §8/§12.
> **Verdict:** **FAIL — DO NOT MERGE as-is.** One **P1 production-blocking migration defect** (V121 does not apply to PostgreSQL) and one **P1 security gap** (media download is not host/owner-scoped — anonymous/PRIVATE report media is reachable by any authenticated user). Both are small, well-scoped fixes. The rest of the wave (push registry, geo seed, Redis limiters, cross-module wiring, prod-boot defaults) is **strong and passes**.

---

## 1. Build & test verification (exact results)

| Gate | Command | Result |
|---|---|---|
| Compile + package | `./mvnw -q -DskipTests package` | **GREEN** (exit 0). NOTE: `package -DskipTests` does **not** start a Spring context or run Flyway, so it cannot catch the V121 defect — it is *not* evidence the migrations are sound. |
| Module boundary (ADR-0013 fence) | `./mvnw -q test -Dtest=ModuleBoundaryTest` | **GREEN** (exit 0). `api → api` permitted; no module reaches another's `domain`/`infrastructure`. |
| Wave4 **unit** tests | `RedisAuthRateLimiterTest, RedisUssdGatewayRateLimiterTest, DeviceTokenServiceTest, FcmHttpPushSenderTest, MediaAttachmentApiImplTest` | **GREEN — 48/48** (13+7+10+8+10). |
| Wave4 **service** test | `MediaServiceTest` (isolated) | **GREEN — 15/15.** |
| Wave4 **E2E** read contract | `PublicReadsContractE2ETest` (isolated) | **GREEN — 6/6.** |
| **FULL suite** | `./mvnw test` | **RED — 631 run, 11 failures + 25 errors.** Root cause: **V121 migration syntax error** (below). |

### Failing classes (full suite) and why
All failures trace to **two** causes, not many:

1. **V121 Flyway syntax error (the dominant cause).** `MediaMigrationValidateIntegrationTest`, `UssdMigrationValidateIntegrationTest`, `ResponderMigrationIntegrationTest`, and other `spring.flyway.enabled=true` + `ddl-auto=validate` contexts fail at **application-context load** because Flyway aborts on V121, then later contexts see `Found non-empty schema(s) "public" but no schema history table`. Exact Flyway error:
   ```
   Migration V121__media_upload_confirm_and_unbound_owner.sql failed
   SQL State : 42601
   Message   : ERROR: syntax error at or near "||"  Position: 154
   Statement : COMMENT ON COLUMN media_object.uploaded IS
       'TRUE once the client confirmed the bytes were PUT ... '
       || '(confirm step, V121); ... never bound or served.'
   ```
2. **Pre-existing create-drop harness gap (NOT a wave4 regression).** `ReportingFlowIntegrationTest`, `TokensIntegrationTest`, `CivicFlowE2ETest`, `UssdGatewaySecretAuthE2ETest`, `ModerationEndpointSecurityIntegrationTest` fail/error in isolation because the `test` profile runs `ddl-auto=create-drop` with **Flyway disabled** (`application-test.yml`), and `report_code_seq` (and equivalents) are created **only by Flyway migrations** and are not entity-mapped, so `nextval('report_code_seq')` → `relation does not exist`. The pre-existing `ReportingFlowIntegrationTest` fails the **same way** in isolation, confirming this is a harness ordering dependency, not introduced by wave4. These pass only when an earlier full-Flyway context in the shared singleton container has materialised the schema — i.e. the suite is order-dependent and fragile, and V121 breaking the Flyway path collapses that scaffolding for everyone.

> **Honest bottom line on tests:** the wave4 *unit* logic is solid and green; the wave4 *integration/E2E* layer is **red**, primarily because **V121 cannot be applied to a real database**. The migration-validate ITs did their job — they caught a defect that would otherwise have surfaced on the first production `flyway migrate`.

---

## 2. MUST-FIX (prioritised)

### MF-1 (P1, prod-blocking) — V121 migration does not apply: `COMMENT ON COLUMN ... IS '…' || '…'`
- **File:** `backend/src/main/resources/db/migration/V121__media_upload_confirm_and_unbound_owner.sql`, lines 32–34 (`media_object.uploaded`) and 39–41 (`media_object.owner_id`).
- **Defect:** PostgreSQL `COMMENT ON … IS` takes a **single string literal**, not an expression — `'a' || 'b'` is a syntax error (SQLSTATE 42601). This is the *literal SQL that runs against production*; `ddl-auto=validate` + Flyway means **the app will fail to boot on a fresh prod DB at V121**.
- **Scope:** V121 is **new in this wave** (commit `1367c0e`) and has not been applied anywhere, so it is **forward-only-safe to edit in place** (no superseding migration needed — CLAUDE.md §12 only forbids editing *applied* migrations).
- **Fix:** collapse each comment into one literal (concatenate the two string fragments into a single quoted string), or use a PostgreSQL dollar-quoted literal `$$…$$`. Then re-run `./mvnw test` — the migration-validate ITs must go green.
- **Owner:** database-engineer (with security sign-off that the comment text is unchanged in meaning).
- **Regression guard:** the existing `MediaMigrationValidateIntegrationTest` already covers this once fixed; add it to the standing "every migration applies under Flyway+validate" checklist.

### MF-2 (P1, security — Broken Object-Level Authorization / IDOR; OWASP A01) — media download is not host/owner-scoped
- **Files:** `media/api/controller/MediaController.java` (`GET /media/{publicId}` and `GET /media/{mediaId}/download-url`, both only `@PreAuthorize("isAuthenticated()")`); `media/application/service/MediaService.getDownloadUrl` (line ~205, `// TODO(wiring): authorize that the current caller may view (ownerType, ownerId)`).
- **Defect:** the only gate on issuing a pre-signed GET is **scan state = CLEAN**. **Any** authenticated citizen (lowest tier) who knows/guesses a media `publicId` can obtain a working pre-signed download URL for **any CLEAN object** — including evidence photos bound to a **PRIVATE / anonymous / sensitive (corruption, GBV) report**. The CLEAN gate protects against malware, **not** against unauthorized viewing. This directly contradicts the task invariant *"anonymous-report media stays private"* and PRD §25.3 (never expose private/sensitive report content).
- **Evidence it is untested:** `MediaServiceTest` covers only the scan-state gate (`getDownloadUrl_cleanObject_signsDownload`, `…_pendingObject_isRefused`, `…_infectedObject_isRefused`, `…_unknownId_throwsNotFound`). There is **no test** asserting a non-owner / non-authorized viewer is refused, because the check is a `// TODO(wiring)`.
- **Why it slipped:** the design correctly delegates host visibility to the host module (boundary rule), but the *download path shipped without that delegation wired* — so the door is open in the interim. The MVP already files real report evidence through this pipeline (`ReportService.fileReport` → `MediaAttachmentValidator.validateAndBind` with `ownerType=REPORT`), so the exposure is live, not hypothetical.
- **Fix (before the gate, not "later"):** wire a host-visibility check on the download path via the reporting module's published `..api..` port (ADR-0013): resolve `media.ownerType/ownerId` → ask `reporting.api` "may caller X view report Y?" (owner, or an in-scope responder/staff). Until that port exists, the safe interim is to **restrict the download endpoints to the uploader + authorized staff** (deny-by-default) rather than any-authenticated. A pre-signed URL for a sensitive report must never be mintable by an arbitrary account.
- **Owner:** security + backend (reporting-module visibility `..api..` port) + media.
- **Note:** the **upload/bind** side is correctly scoped — `MediaAttachmentApiImpl.validateAndBind` enforces uploader-ownership, type match, confirmed-upload, single-bind, and **rejects account-owned media on an anonymous filing** (`ownerProfileId == null` → `BAD_REQUEST`). The gap is strictly the **serve/download** direction.

### MF-3 (P2, security hygiene) — media scan-callback authenticated only by `isAuthenticated()`
- **File:** `MediaController.scanCallback` (`POST /media/{mediaId}/scan-callback`).
- **Note:** the code already flags this as a CENTRAL NEED (scanner service-principal and/or HMAC webhook signature). Any authenticated citizen can currently POST a `CLEAN`/`INFECTED` verdict for any object. Acceptable as an interim seam, but it must carry a machine-principal/HMAC guard before the scanner is wired in production. Track as P2 to the gate.

---

## 3. PASS — what is correct (with evidence)

### 3.1 Device-token handling (EI-5) — PASS
- **Never logged:** `DeviceTokenService` and `FcmHttpPushSender` log only `profile={UUID}` + platform + counts; the token string never reaches a log line, an event, or a cross-user DTO. `DeviceTokenDto` omits the token value; `RegisterDeviceTokenRequest` carries it inbound only. Verified in code and in `FcmHttpPushSenderTest`/`DeviceTokenServiceTest` (10+8 green).
- **Owner-scoped:** `unregister` rejects another profile's token (`FORBIDDEN`); `tokensFor` is keyed by recipient profile; `pruneInvalid` is an idempotent soft-delete. One citizen cannot silence/enumerate another's device.
- **DB invariant:** V122 adds a **partial unique index** `ux_device_token_token_live ON device_token(token) WHERE deleted=false` — one live registration per device, re-register-after-logout allowed; `ddl-auto=validate`-safe (partial index not validated by Hibernate, documented in the migration). `profile_id` is a bare UUID, not a cross-module FK (boundary-correct). V122 applies cleanly.
- **FCM body privacy:** push payload is title + short body + opaque `deepLinkRef` only; never logged; service-account key loaded from an env/secret-mount path, fail-fast if absent.

### 3.2 Redis rate-limiter adapters (W3-2) — PASS
- **No secrets in source:** Redis host/port from `${TAARIFU_REDIS_HOST/PORT}`, password from `SPRING_DATA_REDIS_PASSWORD` (env) — none committed.
- **Hashed keys preserved (S-4/PDPA):** both `RedisAuthRateLimiter` and `RedisUssdGatewayRateLimiter` namespace a **caller-supplied hashed** identifier (`taarifu:rl:auth:*`, `taarifu:rl:ussd:*`). No raw phone/email/IP/MSISDN reaches a key, log, or metric; keys are never logged.
- **Fail-closed on outage:** every gate returns `false` (deny) on a Redis error — the auth/USSD surface tightens, never opens, when the counter store is unreachable (AUTH-DESIGN §15).
- **Thresholds match in-memory byte-for-byte** (asserted in `RedisAuthRateLimiterTest` 13, `RedisUssdGatewayRateLimiterTest` 7 — green). Sliding-window via sorted-set with same-millisecond disambiguation is correct.

### 3.3 Geo-seed integrity (V105–V109) — PASS
- **No duplicate codes / idempotent:** deterministic `seed_uuid(type, code)` public ids + `ON CONFLICT (public_id) DO NOTHING`; councils derived set-based from the district tree (`<district>-C01`) with a `NOT EXISTS` guard so pilot councils (V73/V74) are not duplicated.
- **FK / referential integrity:** ward→constituency mappings resolve by **code via subselects**; a missing ward or constituency code yields `NULL` and the row is skipped by `NOT NULL` columns — partial coverage never corrupts or aborts. Respects the temporal invariants `ux_ward_constituency_current` (≤1 current per ward) and `ex_ward_constituency_no_overlap`.
- **Honest provenance:** every multi-constituency district is explicitly marked `[PICK]`/`GAP`/`UNVERIFIED`; intra-district ward splits that aren't authoritatively obtainable are **left unmapped on purpose** rather than fabricated — exactly right for electoral-attribution integrity and the R4/R5 per-region geography gate. Council legal names are honest structural placeholders flagged `[UNVERIFIED]` for enrichment before each region's go-live. These migrations apply cleanly under Flyway.

### 3.4 Prod-boot still holds (all ports resolve a bean, no dev profile) — PASS
- Every adapter uses `@ConditionalOnProperty(..., matchIfMissing=true)` for its safe default, so a **no-profile production context** resolves exactly one bean per port with no external infra:
  - Rate-limiters → `InMemoryAuthRateLimiter` / `InMemoryUssdGatewayRateLimiter` (`backend=memory` default); Redis only on `=redis`.
  - Object store → `InMemoryObjectStore` (`object-store=stub` default); S3 on `=s3`.
  - Communications → logging stubs (`sms/push/email.provider=logging` default); real adapters fail-fast only when explicitly selected and mis-configured.
- No secrets in source (`application.yml` is all `${VAR}`); JWT secret fail-fast on absence/short key; MFA enforced-by-default; USSD webhook fail-closed when secret unset; CORS allow-list empty-by-default (never `*`-with-credentials).

### 3.5 Cross-module wiring & E2E honesty — MIXED (design PASS, harness fragile)
- **Design correct:** reporting touches media only through `media.api.MediaAttachmentApi` (an `api → api` port); routing to a responder OWNER is async via the outbox (`REPORT_ROUTED`), no synchronous `reporting → responders` cycle (ADR-0013/0014). Attachment validate-and-bind runs in the file transaction (atomic rollback on a bad/forged/foreign id). Events/DTOs carry ids only — no PII, no reporter, no geo-point.
- **E2E honesty caveat:** `CivicFlowE2ETest` is well-written and drives the **real** outbox relay deterministically (no sleeps) with clearly `@Primary` test-only ports — but it **cannot pass in isolation** under the create-drop test profile (missing `report_code_seq`), and currently fails in the full suite as collateral of the V121 break. The suite's reliance on a shared singleton container + create-drop + a Flyway-only sequence makes integration coverage **order-dependent**. Recommend (separate from this wave's must-fixes): flip the report-filing ITs/E2E to the **Flyway+validate** profile the migration-validate ITs already use, so they own a faithful prod-shaped schema and are not order-dependent.

---

## 4. Central / cross-team needs (flagged, outside this worktree's edits)
- **Reporting-module visibility `..api..` port** for MF-2 (host "may caller view report Y?" check) — security + reporting + media.
- **Scanner service-principal / HMAC webhook signature** for MF-3 — SRE + security.
- **Test-harness hardening:** report-filing ITs/E2E should run on Flyway+validate (eliminates the `report_code_seq` create-drop gap and the order-dependence) — qa + backend.

---

## 5. Go / No-Go

**NO-GO for merge until MF-1 and MF-2 are fixed and `./mvnw test` is green.**

- **MF-1 (V121)** is a one-line-class fix (single-literal comments) and is forward-only-safe; it unblocks the entire migration-validate IT suite.
- **MF-2 (media IDOR)** must not ship: a pre-signed download of a sensitive/anonymous report's evidence reachable by any authenticated account is exactly the citizen-safety/PDPA failure the gate exists to prevent. Wire the host-visibility check, or restrict the download endpoints to uploader+authorized-staff in the interim, and add a non-owner-refused test.

The wave's substance — push registry, national geography seed, Redis limiters, cross-module ports, prod-safe defaults — is **good work and passes**. The two blockers are narrow, identified, and testable.
