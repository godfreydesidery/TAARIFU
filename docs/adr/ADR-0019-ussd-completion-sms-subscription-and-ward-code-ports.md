# ADR-0019: Completing the USSD reporting channel — communications' public SMS-send + area-subscription command ports, and geography's public ward-by-code query port (closing TODO-WIRING A3, A7)

**Status:** Accepted · 2026-06-25 · Backend (Baraka Mushi)
**Extends:** ADR-0013 (cross-module integration — published `..api..` ports for synchronous reads, events for async, the `ModuleBoundaryTest` rule that permits `api → api` while keeping `domain`/`infrastructure` encapsulated), ADR-0014 (transactional outbox). Precedents in code: `reporting.api.UssdReportApi` (a published command port the `ussd` module consumes for file/track), `identity.api.AccountProvisioningApi` (a published command port `ussd → identity`), `identity.api.RecipientContactApi` (`communications → identity`), `institutions.api.RepresentativeQueryApi` (`*QueryApi` read port).
**Grounding:** PRD §14 (USSD/SMS feature-phone channel), UC-D02 (file → ticket by SMS), US-3.9, §9.0 (ward = minimum pin granularity; the official administrative `code`), §13 (notification channels), §18 + PDPA (PII discipline — never log/store/emit a raw MSISDN), D18/§23.5 (civic-integrity fence — tokens never on a civic-core path). Closes `docs/reviews/TODO-WIRING-AUDIT.md` items **A3** and **A7** and CENTRAL INTEGRATION NEED #1.

## Context

Two Phase-2 gaps left the USSD reporting channel half-wired (TODO-WIRING-AUDIT §2):

- **A3** — the `ussd` module's two outbound integrations into `communications` are logged stubs because `communications` had not published a public command port:
  - the **ticket-confirmation SMS** (UC-D02: "ticket code returned by SMS") goes through the consumer-owned `ussd.application.port.UssdSmsSender`, bound to `LoggingUssdSmsSenderStub` (logs a redacted line, sends nothing);
  - the **my-area-alert forwarding** (`UssdAlertService.subscribeArea`) records a local `UssdAlertSubscription` intent but cannot register the real follow/notification preference in `communications`.

  The isolation rule (ADR-0013, ARCHITECTURE §3.2) correctly forbids `ussd` from importing `communications.domain.port.SmsGateway` or writing the `subscription` table directly — so both waited on `communications` publishing `..api..` command ports.

- **A7** — `ussd.UssdMenuMachine.resolveWardCode` accepts only a typed **UUID** and rejects anything else, so a feature-phone citizen cannot type a friendly **ward code** (the official administrative `code` on `geography.Location`, e.g. a Kata code). `geography` exposes only controllers/DTOs under `..api..` — no published lookup port a sibling can call in-process.

The scope of this change is exactly three modules: **communications** (publish the ports + impls), **geography** (publish the ward-by-code query port + impl), **ussd** (wire its three consumer-owned ports to the new published ports). No other module is touched; no identity change is made here.

## Decision

### 1. `communications` publishes two public command ports in `com.taarifu.communications.api`

Both follow the established shape (`tokens.api.TokenLedgerApi` / `reporting.api.UssdReportApi`): a plain interface in the `..api..` package, taking/returning only `UUID`s + small records/enums, implemented by a `@Service` in `communications.application.service`, the caller injecting the interface.

**(a) `SmsSendApi` — outbound SMS send.** One method, `send(SmsSendCommand)`, that delegates to the existing internal `communications.domain.port.SmsGateway` (which the real least-cost/DLR aggregator adapter or the prod-safe `LoggingSmsGatewayStub` already back, masking the recipient before logging). It never throws on a routine delivery failure (it returns an accepted/failed result) — a confirmation SMS must never break a citizen flow (EI-3). It exposes **no token** input/output (fence, D18).

- **WHY a published `..api..` port (not exposing `SmsGateway` cross-module):** `SmsGateway` lives in `communications.domain.port` — an internal layer the boundary rule forbids a sibling from importing. The `..api..` port is the sanctioned synchronous contract; it keeps the aggregator/DLR quirks and the masking discipline owned inside `communications`.
- **🔒 PII:** the command carries a raw E.164 recipient (the gateway must address a real handset) and the body. Per S-4/PDPA the impl logs nothing raw, hands the value straight to the masking `SmsGateway`, and the command never lands in an event, feed, or audit row. The `purpose`/`idempotencyKey` are non-PII tags so a relay/retry never double-sends.

**(b) `AreaSubscriptionApi` — register an area (Kata) follow for SMS alerts.** One method, `subscribeArea(UUID subscriberProfilePublicId, UUID wardPublicId)`, idempotent, that registers a `Subscription` of `targetType = AREA` keyed by the subscriber's **profile public id** — the exact grain the announcement fan-out (`AnnouncementPublishedHandler`) and `identity.api.RecipientContactApi` resolve, so an area announcement actually reaches the feature-phone subscriber over SMS. A repeat is a no-op (the partial unique index on `(follower, target_type, target_id)` is the hard backstop), reusing `SubscriptionService`'s idempotent follow.

- **WHY profile-id grain (not the USSD account id):** `communications.Subscription.followerProfileId` is a **profile** id; the fan-out resolves a recipient's MSISDN via `RecipientContactApi.contactFor(profileId)`. Registering at any other grain would silently fail to deliver. This port therefore contracts a **profile id** and the caller must supply one.
- **The account→profile resolution gap (named, not invented):** the `ussd` module today carries only the MSISDN-linked **account** public id (from `AccountProvisioningApi.ensureAccountByMsisdn`), not the profile id. Resolving account → profile is **identity's** concern and identity is **out of this change's scope** (assigned modules: ussd/communications/geography). So this increment wires the SMS-send leg of A3 fully (the dialogue carries the MSISDN directly) and lands the `AreaSubscriptionApi` contract + impl, but leaves the `ussd → communications` area-alert call gated on a **new CENTRAL NEED**: identity must expose `profileIdForAccount(UUID accountId)` (or `AccountProvisioningApi` must return the profile id) so the USSD adapter passes a true profile id. Until then `UssdAlertService` keeps recording the local intent unforwarded (`forwarded = false`) — the safe, already-shipped degrade — rather than registering a follow at the wrong grain that would never deliver. The contract is fixed now so the identity increment is a one-line wire-up later.

### 2. `geography` publishes `WardCodeQueryApi` in `com.taarifu.geography.api`

A `*QueryApi` read port (the `RepresentativeQueryApi`/`IssueCategoryQueryApi` shape): `Optional<UUID> wardIdByCode(String wardCode)` resolving the official administrative `code` of a `Location` of `type = WARD` to its public id. Case-insensitive, trims input, returns empty (never throws) for an unknown/blank code so the USSD machine shows a friendly "invalid, try again" `CON` rather than crashing the dialogue. Implemented by a `@Service @Transactional(readOnly = true)` in `geography.application.service`, exposing only the `UUID` — never a `Location` entity.

- **WHY a read port (not the routing-style async):** the citizen's next keypress depends on resolving the ward **within the same request** (they must pick an area before describing the issue) — it cannot wait for an event. This is the canonical synchronous `*QueryApi` case (ADR-0013 §1), `ussd → geography`, no cycle (geography never calls ussd).
- The lookup is backed by a new repository method (`findPublicIdByCodeAndType`) using the existing unique index on `location.code`; no schema change.

### 3. `ussd` wires its three consumer-owned ports to the new published ports

- `UssdSmsSender` gets a **production adapter** (`CommunicationsUssdSmsSender`) that delegates to `communications.api.SmsSendApi`, replacing the logging stub as the default bean while keeping a stub fallback available. The ticket-confirmation SMS now really sends (UC-D02), never throwing on failure (the END line already carries the code).
- `UssdMenuMachine.resolveWardCode` first tries a typed UUID (back-compat), then falls back to `geography.api.WardCodeQueryApi.wardIdByCode` via a new consumer-owned `UssdGeographyPort` + adapter — so a citizen can type a friendly ward code (A7).
- `UssdAlertService` is left forwarding-ready: the `AreaSubscriptionApi` collaborator is available behind a consumer-owned `UssdSubscriptionPort`, but the call stays gated on the identity account→profile CENTRAL NEED (see §1b). The local intent + `markForwarded()` seam already exist (V95).

### 4. Boundary & migration

- All cross-module edges are `ussd → communications.api` and `ussd → geography.api` — `api → api`, permitted by `ModuleBoundaryTest.noModuleDependsOnAnotherModulesDomainOrInfrastructure` (ADR-0013 §3). No `ussd` class imports another module's `domain`/`infrastructure`. The new impls live in their owner's `application.service` and use only their **own** module's `domain`.
- **No Flyway migration needed.** A3-SMS uses the existing `SmsGateway`; A3-subscription uses the existing `subscription` table + the existing `ussd_alert_subscription.forwarded` column (V95); A7 is a read over the existing unique `location.code` index. The reserved block (V153+) stays unused for this increment.

## Consequences

- (+) Closes A3 (SMS leg fully; subscription contract + impl landed) and A7 (ward-by-code) through the one canonical mechanism, with no new concept and no boundary erosion — the suite stays GREEN.
- (+) The civic-integrity fence holds by construction: none of the three new ports, nor any USSD path, references `tokens` (D18).
- (+) PII discipline preserved: the raw MSISDN crosses only into the masking `SmsGateway`; no port emits/logs/stores it.
- (−) The area-alert forwarding is **contract-complete but not call-complete**: it remains gated on the identity account→profile CENTRAL NEED. Deliberate — registering a follow at the wrong grain would silently never deliver, which is worse than the current safe unforwarded intent.
- **Revisit trigger:** identity exposes account→profile resolution → wire `UssdAlertService` to `AreaSubscriptionApi` and call `markForwarded()`; one-line change against the contract fixed here.

## Decision summary

- **communications.api:** `SmsSendApi` (delegates to internal `SmsGateway`, masked, fail-soft, no tokens) and `AreaSubscriptionApi` (idempotent `AREA` follow at **profile-id grain**).
- **geography.api:** `WardCodeQueryApi.wardIdByCode` (`*QueryApi` read; official `WARD` `code` → public id; empty-not-throw).
- **ussd:** production `UssdSmsSender` adapter → `SmsSendApi`; `resolveWardCode` → `WardCodeQueryApi` via a consumer-owned geography port; `AreaSubscriptionApi` collaborator wired but the forward call stays gated on the identity account→profile CENTRAL NEED.
- **No migration.** All edges are `api → api`; internals stay encapsulated.
