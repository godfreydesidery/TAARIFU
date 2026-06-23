# Security + Architecture Review — Transactional Outbox Increment (ADR-0014)

**Reviewer:** Salim Juma (Security & Privacy Engineer) · **Date:** 2026-06-23
**Branch:** `develop` @ `ed88a3e` · **Scope:** the new outbox/relay substrate (V97), its two wired flows
(`ANNOUNCEMENT_PUBLISHED` fan-out, `REPORT_ROUTED` → OWNER assignment), and the new admin/moderation
read queues that landed alongside it.
**Verdict: PASS** — no open P1/P2. Two P3 and two P4 nits, none gating.

Grounds: ADR-0014, ADR-0013, ADR-0008; PRD §15 DI3, §16, §18, §21 DI3, §23.5/D18, §24.3/D21; CLAUDE.md §3/§8/§12.

---

## 1. Build + test gate (all GREEN)

| Gate | Result |
|---|---|
| `./mvnw -q -DskipTests package` | **GREEN** (clean) |
| `./mvnw -q test -Dtest=ModuleBoundaryTest` | **GREEN** — 6 tests, 0 failures |
| `OutboxRelayTest` | **GREEN** — 8/8 |
| `RoutingHandlerTest` | **GREEN** — 7/7 |
| `AnnouncementServiceTest` | **GREEN** — 12/12 |
| `ReportServiceTest` | **GREEN** — 16/16 |
| `AppealServiceTest` | **GREEN** — 8/8 |
| `ReportsAdminServiceTest` | **GREEN** — 4/4 |
| `ModerationQueueServiceTest` | **GREEN** — 4/4 |
| `DashboardServiceTest` / `UserAdminServiceTest` | **GREEN** — 5/5, 6/6 |

(The stack trace seen on console during `OutboxRelayTest` is a *logged-and-caught* handler failure exercised
by the negative-path tests — the relay never rethrows it; surefire reports 0 errors.)

---

## 2. At-least-once + idempotent handlers (the core correctness constraint)

**PASS.** Delivery is at-least-once by design (`OutboxRelay.process` runs the handler then marks PROCESSED
in the same batch tx — a crash in that window re-delivers). Both wired handlers are idempotent and the tests
prove it:

- **No double-notify (fan-out).** `AnnouncementPublishedHandler` delegates to `NotificationDispatchService`,
  which keys every queued row on `type:channel:recipient:sourceId` (sourceId = the stable announcement id),
  backed by a unique `(idempotency_key)` index on `notification`. A redelivered event recomputes identical
  keys → no re-send. Recipients are also de-duplicated in-handler (area-follow ∪ category-follow), and the
  author is removed (no self-notification). `OutboxRelayTest.redeliveredEvent_…butEffectAppliesOnce` proves
  effect-once under double delivery at the relay level.
- **Exactly one OWNER assignment/report.** `RoutingHandler` is idempotent on two layers: a read-side
  `findOwner(...)` no-op, and the DB partial-unique `ux_responder_assignment_one_owner` backstop caught as
  `DataIntegrityViolationException` and swallowed as "already routed". `RoutingHandlerTest` asserts both:
  `handle_reportAlreadyHasOwner_isIdempotentNoOp` (no save, **no** back-event) and
  `handle_concurrentOwnerConstraintViolation_isSwallowedAsNoOp` (no duplicate `RESPONDER_ASSIGNED`). The
  back-event is emitted **only** on a genuinely new OWNER, so a redelivery never produces a duplicate
  `RESPONDER_ASSIGNED` → no duplicate downstream report mutation. Config gaps (no rule / no eligible
  responder) are a **no-op success**, not a DLQ entry — a missing routing rule never blocks citizen filing.

Residual (accepted): no ordering guarantee; both flows are order-insensitive (set-union upsert; single
idempotent insert) per ADR-0014 ordering caveats.

---

## 3. No PII in outbox payloads

**PASS.** Payloads are ids/codes/enums only, enforced by the event-record contracts and the column comment
on `outbox_event.payload`:

- `ReportRouted` = `{reportId, categoryId, wardId, occurredAt}` — UUIDs + timestamp; no reporter, title,
  description, or geo-point.
- `AnnouncementPublished` = announcement/author/area/category UUIDs + channel **names** + timestamp; no
  title/body, no recipient identity beyond the opaque profile id.
- `ResponderAssignedEvent` (the back-event) = assignment/report/responder UUIDs + role/enum + timestamps.
- Consumers re-read richer detail by id via the owner's `*QueryApi` (ADR-0013).
- `last_error` is redacted to `<ExceptionClass>: <clipped message>` — **no stack trace, no payload** (the
  relay and writer both deliberately omit payload text from exception messages, citing PRD §18). `OutboxRelay`
  logs by `eventId`/`eventType` only.

Standing discipline noted, not a finding: the no-PII invariant on `payload` is a *contract*, not a mechanical
guard — see P3-1.

---

## 4. No synchronous reporting → responders cycle

**PASS.** Verified by import scan: `reporting` imports **nothing** from `responders`; `responders` imports
`reporting` **only** through `reporting.api.event` (taxonomy constants) and the `*QueryApi` ports — never a
domain/infrastructure class. Routing is async: `reporting` appends `REPORT_ROUTED` and returns; the responders
`RoutingHandler` reacts on the relay thread; the back-edge (`RESPONDER_ASSIGNED`) is **also** via the outbox.
`ModuleBoundaryTest` (6/6) enforces the `api → api`-only rule. The citizen's filing is never coupled to a
responders outage (PRD §15 DI3).

---

## 5. Method-security on the new admin/moderation queues

**PASS — `@EnableMethodSecurity` is on; every new handler is annotated; SecurityConfig allow-list unchanged.**

| Endpoint | Guard |
|---|---|
| `GET /admin/reports`, `/admin/reports/{id}`, `/admin/stats` | `@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")` per method |
| `GET /moderation/appeals` | `@PreAuthorize("hasRole('MODERATOR')")` |
| `POST /moderation/actions/{actionId}/appeals` | `@PreAuthorize("isAuthenticated()")` + author check in service |
| `POST /moderation/appeals/{appealId}/decision` | `@PreAuthorize("hasRole('MODERATOR')")` + appeal-independence (D16/§25.8) in service |
| `GET /moderation/items`, `POST /moderation/items/{id}/actions` | `@PreAuthorize("hasRole('MODERATOR')")` |

- **No `/admin/**` or `/moderation/**` entry in any `PUBLIC_*` allow-list** — they are deny-by-default;
  anonymous → 401, wrong role → 403. The appeals queue added no SecurityConfig change.
- **MFA:** staff roles are only minted after the TOTP step (AUTH-DESIGN §14.1), so requiring the staff role
  *is* the MFA-gated path — no extra per-method MFA expression needed.
- **PII minimisation:** the admin reports queue/detail are served as the reporting module's PII-minimised
  projections (no reporter identity, no precise geo-point); the internal timeline reaches only ADMIN/MODERATOR
  handlers (PRD §18 / PDPA, D-Q1). `AdminSecurityIntegrationTest` covers the role gates.

Appeal-independence (the decider must differ from the original actioning moderator) is a live cross-row check
correctly placed in the service, not a static `@PreAuthorize` — verified present in `AppealService` and
covered by `AppealServiceTest`.

---

## 6. Dev still boots (no S3 dependency, stub adapters under default profile)

**PASS.** `InMemoryObjectStore` is `@ConditionalOnProperty(name="taarifu.media.object-store",
havingValue="stub", matchIfMissing=true)` → it is the default with **no** config. `S3ObjectStore` is
`havingValue="s3"` with **no** `matchIfMissing`, so the AWS SDK adapter is wired only when explicitly
selected. `StubMalwareScanner` is the default scanner. `OutboxConfig` enables `@EnableScheduling` +
`OutboxProperties` with safe defaults (poll 1000ms, 8 attempts, backoff base/cap) — the relay works out of
the box with no central config change. Atomicity is mechanically enforced: `OutboxWriterImpl.append` is
`@Transactional(Propagation.MANDATORY)` — a careless out-of-tx append fails loudly rather than writing a
non-atomic event.

> Note: the task brief named the toggle `taarifu.media.store=s3`; the actual property key is
> `taarifu.media.object-store=s3`. Behaviour matches the intent (dev = in-memory, S3 only on opt-in).

---

## Prioritized must-fix list

**P1 / P2 — none.** The increment is shippable as-is against the security gate.

**P3 (should-fix, next increment — not gating):**

1. **No mechanical PII guard on the outbox payload.** The no-PII rule is enforced only by contract + reviewer
   discipline. Add the ADR-0013 §3 follow-up ArchUnit tightening so a `*.api.event` record / `*QueryApi`
   return type cannot reference an `@Entity`, and consider a serialization-time assertion (test fixture that
   fails if a payload record exposes a field named like a PII attribute). Cheap insurance for the highest-
   sensitivity invariant.
2. **No retention/purge job for `outbox_event` yet.** ADR-0014 names a hard-purge of old PROCESSED rows and
   FAILED-row (DLQ) alerting as "operability", but neither exists. PROCESSED rows accumulate unbounded and
   FAILED rows are silent. Land the purge job + a `FAILED`-count alert (SRE) before production load. The
   partial PENDING index keeps the *relay* fast meanwhile, so this is operational hygiene, not correctness.

**P4 (nit):**

3. **Javadoc typos in `AnnouncementService`** — several `<\b>` / escaped-slash artefacts
   (`Moderation hold<\b>`, `published\expired\held`, `WHY a 404 … hidden\expired`). Cosmetic; fix when next
   touched (CLAUDE.md §8 — keep comments clean).
4. **DLQ has no replay path.** FAILED rows are terminal with no operator re-queue endpoint (an ADR-0014
   revisit trigger). Acceptable at MVP; track for when FAILED volume warrants it.
