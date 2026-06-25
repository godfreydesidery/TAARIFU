# ADR-0015: Payments — mobile-money token top-up (a NEW `com.taarifu.payments` module that initiates a collection, verifies a signed callback idempotently, reconciles, and credits the buyer's token wallet — top-up ONLY, the D18 fence stays)

**Status:** Accepted · 2026-06-25 · Backend Engineer (Baraka Mushi) · Phase-2 Wave-1
**Extends:** ADR-0002 (modular monolith), ADR-0004 (ports & adapters + a stub for every external system), ADR-0008 (single `ApiResponse` envelope), ADR-0013 (cross-module integration — synchronous reads/commands via a callee's published `..api..` port; async via events), ADR-0014 (transactional outbox + in-process event bus). This ADR realises the **purchase** half of the tokens module's pre-cut Phase-2 seam (the `Payment`/`TokenPackage`/`PaymentProvider` schema landed in `V31`–`V33`, ARCHITECTURE.md §7 `PaymentProvider` row).
**Grounding:** PRD §23.4 (token packages), **§23.5 (idempotent, reconciliation-driven settlement — never trust-the-callback; the integrity fence)**, §23.6 / §25.10 (Phase-2 headline: tokens purchasable via mobile money; **tokens NEVER buy democratic weight — D18**), §21 EI-20 / DI1 (mobile-money adapter behind a port; a stub so the system boots), §18 (PII out of logs/events; no secrets in source). ARCHITECTURE.md §3 (module map, dependency rule, four-layer), §4 (BaseEntity, Flyway+validate, minor-units money), §5 (envelope), §7 (ports/adapters + stub), §8 (outbox). CLAUDE.md §3 (SOLID/KISS/clean boundaries/fail-safe), §8 (no entity leak, UUID ids, Javadoc), §12 (no secrets, no PII leak, **tokens never gate/buy democratic-weight actions**).

## Context

PRD §23.6 makes "buy tokens with mobile money" a Phase-2 headline. The schema seam was deliberately modelled in MVP **inside the tokens module** (`tokens.domain.model.Payment`, `TokenPackage`, the `tokens.domain.port.PaymentProvider` + `StubPaymentProvider`, migrations `V31`–`V33`) so Phase-2 would need no breaking migration. What does **not** exist yet is the **flow**: initiating a collection (STK push), receiving and verifying an asynchronous provider callback, reconciling it idempotently to exactly one purchase row, and crediting the buyer's wallet.

Tanzanian mobile-money reality shapes every decision:
- **M-Pesa / Tigo Pesa / Airtel Money / HaloPesa are STK-push + collection-callback**, asynchronous: the citizen approves on their handset and settlement arrives later via a webhook the aggregator POSTs to us.
- **Callbacks are duplicated and out-of-order.** A provider will deliver the same settlement twice, or deliver a settlement before our initiation row is visible. So settlement must be **idempotent and reconciliation-driven, never trust-the-callback** (PRD §23.5).
- **The free token path always suffices** (PRD §23.5). Purchase is additive convenience; if no adapter is configured/healthy, purchase is simply unavailable and the citizen continues on free quotas — never blocked.

Two architectural forces had to be reconciled against the locked decisions:

1. **The civic-integrity fence (D18, PRD §23.5) is non-negotiable.** A purchased token may buy only convenience/volume/reach. It must **never** appear in the authorization path of a binding democratic action (sign petition / rate rep / binding poll), and the purchase flow must never grant a role, a vote, priority, or verification status. The credit it produces is a **top-up of the convenience wallet only**.
2. **Module isolation (ADR-0013).** This increment builds a **new, isolated module** `com.taarifu.payments`. It may depend only on the shared kernel `common`, its own internals, and other modules' published `..api..` packages. It must **not** reach into `tokens`' `domain`/`infrastructure` (the `Payment` entity, `WalletRepository`, `WalletService` all live behind tokens' boundary).

> **Why a NEW module and not extend `tokens`?** The assignment scopes this work to `com.taarifu.payments` with its own Flyway block (`V130`–`V139`), and there is a real separation-of-concerns argument: `tokens` owns the **wallet + ledger + fence**; `payments` owns the **money-movement lifecycle + vendor rails + webhook security + reconciliation**. Money movement is a different bounded context (different threat model, different vendors, different residency surface) and is exactly the kind of high-risk surface that benefits from its own boundary. The pre-existing `tokens.Payment` seam stays as the historical MVP stub; this module supersedes its *flow* role. Payments credits the wallet **only** through the published `tokens.api` port (top-up), never by writing tokens' tables.

## Decision

Build `com.taarifu.payments` as a four-layer module that owns a `TopUp` purchase aggregate, a pluggable `MobileMoneyGateway` outbound port with `@ConditionalOnProperty` adapters (logging stub default + per-rail real adapters, env-config only), a **signed-webhook** ingress (HMAC verify, fail-closed, idempotent on the provider reference), and **reconciliation** that credits the buyer's token wallet via `tokens.api` exactly once on a SUCCEEDED settlement — **top-up only**.

### 1. The `TopUp` aggregate + `top_up` table — migration `V130` (Flyway block `V130`–`V139`, reserved for payments)

A `TopUp extends BaseEntity` (internal `Long id`, public `UUID publicId`, audit, soft-delete, `@Version` — ARCHITECTURE §4.2), `com.taarifu.payments.domain.model.TopUp`. It is the money-movement lifecycle for one wallet top-up attempt. It is **distinct from** `tokens.Payment` (which it supersedes for the flow); it references the **buyer** and the **wallet** by opaque public `UUID` only — **no cross-module FK** (ADR-0013; the wallet lives in tokens).

| Column | Type | Purpose / WHY |
|---|---|---|
| `id` / `public_id` / `version` / audit / soft-delete | BaseEntity | standard. `public_id` is the citizen-facing reference. |
| `buyer_id` | `uuid` not null | the buyer's account public id (opaque — never an FK into identity). |
| `wallet_owner_type` | `varchar(16)` not null | `USER` \| `ORGANIZATION` — which wallet to credit (mirrors tokens' `WalletOwnerType` names; passed to `tokens.api`). |
| `provider` | `varchar(16)` not null | rail: `MPESA`/`TIGOPESA`/`AIRTELMONEY`/`HALOPESA` (payments-owned enum; CARD is out of Phase-2 mobile-money scope). |
| `provider_ref` | `varchar(128)` null | the provider's settlement correlation reference; set on initiation/callback. **Unique-when-present** (partial unique index) so one settlement = one row (replay-safe). |
| `amount_minor` | `bigint` not null, `>= 0` | amount in **minor currency units** (never floating-point money — fintech rounding safety). |
| `token_amount` | `bigint` not null, `> 0` | tokens to credit on SUCCEEDED (priced by the catalogue / ad-hoc). |
| `currency` | `varchar(3)` not null default `'TZS'` | ISO-4217. |
| `status` | `varchar(16)` not null default `'INITIATED'` | `INITIATED` → `PENDING` → `SUCCEEDED` \| `FAILED` (terminal). |
| `idempotency_key` | `varchar(200)` not null **unique** | one purchase attempt = one row; a replayed `initiate` (client retry, duplicate request) returns the original row, never a second collection. |
| `credit_event_id` | `uuid` null | the idempotency key used for the **wallet credit** (so a redelivered SUCCEEDED callback credits exactly once). Set when the credit is posted. |
| `failure_reason` | `varchar(256)` null | machine reason on FAILED; **redacted — no PII, no provider body** (PRD §18). |

**Indexes:** `uq_top_up_idempotency (idempotency_key)`; `uq_top_up_provider_ref (provider, provider_ref) WHERE provider_ref IS NOT NULL` (partial unique — the reconciliation anchor); `ix_top_up_buyer (buyer_id)`; `ix_top_up_status (status)`. `ddl-auto=validate` — the entity must match `V130` exactly. Forward-only, with the mandatory SQL comments (CLAUDE.md §8).

### 2. `MobileMoneyGateway` outbound port + `@ConditionalOnProperty` adapters (a logging stub default; real adapters env-config, NO creds in source)

`com.taarifu.payments.domain.port.MobileMoneyGateway` — a plain interface (no vendor type leaks; `ModuleBoundaryTest.domainPortsHaveNoVendorImports` stays GREEN):
- `InitiationResult initiateCollection(CollectionRequest)` — STK push; returns the provider reference + accepted flag.
- `boolean verifyCallbackSignature(String provider, byte[] rawBody, String signatureHeader)` — **HMAC verification of the raw callback body against the per-provider shared secret** (env). Fail-closed: an unverified body is rejected before any state change.
- `CallbackResult parseCallback(byte[] rawBody)` — maps the provider body to `(providerRef, settled, amountMinor)` — never trusts it for crediting until reconciled to our row.
- `boolean verifySettled(String providerRef)` — the **reconciliation** call: confirms settlement against the provider out-of-band (the source of truth — never the raw callback).

Adapters in `infrastructure.adapter`, selected by `taarifu.payments.gateway.provider`:
- **`LoggingMobileMoneyGatewayStub`** — `@ConditionalOnProperty(name="taarifu.payments.gateway.provider", havingValue="logging", matchIfMissing=true)`. The **prod-bootable default**: accepts an initiation with a synthetic ref, never settles (`verifySettled` → false), logs **redacted** (masked MSISDN, no body). Lets the whole system boot and tests run with **zero external calls** (ARCHITECTURE §7). This is also the integrity-safe default — with no real rail configured, nothing is ever credited.
- **`MpesaGateway` / `TigoPesaGateway` / `AirtelMoneyGateway` / `HaloPesaGateway`** — `@ConditionalOnProperty(... havingValue="mpesa"|"tigopesa"|"airtelmoney"|"halopesa")`, each reading its endpoint + **shared secret from the environment** (`taarifu.payments.gateway.*`); **NO credentials in source** (CLAUDE.md §12, PRD §18). Exactly one gateway bean exists in every environment (mutually exclusive on the one property), mirroring the `SmsGateway` pattern. The real adapters are thin HTTPS clients (RestClient, already on the classpath) — vendor SDKs, if ever needed, stay confined here.

> **Degradation (EI-20):** the gateway never hard-fails the citizen path. A down/unconfigured rail → purchase unavailable, free quotas continue. Initiation failures return a typed `SERVICE_UNAVAILABLE`, not a 500.

### 3. Webhook ingress — HMAC verify, fail-closed, idempotent on the provider ref

`POST /payments/webhook/{provider}` (controller in `api.controller`). It is the aggregator callback, so it carries **no user JWT** — it is authenticated by **HMAC of the raw body against the per-provider secret** (`MobileMoneyGateway.verifyCallbackSignature`), fail-closed. The handler:
1. Reads the **raw bytes** (HMAC must be over the exact bytes received), verifies the signature → on mismatch, return a benign 200/accepted-but-ignored *without* revealing the reason (no oracle), and log a redacted warning. **No state change on an unverified body.**
2. Parses the callback to `(providerRef, settled)`.
3. Delegates to `ReconciliationService.reconcile(provider, providerRef, settled)`, which is **idempotent on `provider_ref`**: it finds the one matching `TopUp`, and — only if `verifySettled(providerRef)` confirms against the provider (never the raw callback alone) — transitions it to SUCCEEDED and credits the wallet **once**.

> **WHY a permit at the URL filter is a CENTRAL NEED:** `common.security.SecurityConfig` (which I cannot edit under module isolation) must add `/payments/webhook/**` to `PUBLIC_POST_PATTERNS` (the same precedent as `/ussd/gateway`). Until then the endpoint is HMAC-secured in code but unreachable anonymously. Listed as a CENTRAL NEED, not silently worked around.

### 4. Crediting the wallet — top-up ONLY, via `tokens.api`, idempotent, fence-preserving

On a confirmed SUCCEEDED settlement, `ReconciliationService` credits the buyer's token wallet. Because payments must not touch tokens' tables (ADR-0013), it credits through the **published `tokens.api` port**. The existing `tokens.api.TokenLedgerApi` exposes only `meter` and `reward` — **neither is a purchase top-up**. So this module defines a small **outbound port** `com.taarifu.payments.domain.port.WalletCreditPort` (`creditPurchase(ownerType, ownerId, tokenAmount, idempotencyKey)`), with:
- a default **logging stub adapter** (`@ConditionalOnProperty(... havingValue="logging", matchIfMissing=true)`) so dev/test boot and the flow is testable without tokens wiring; and
- a **`TokensApiWalletCreditAdapter`** (`havingValue="tokens-api"`) that delegates to `tokens.api.TokenLedgerApi.topUp(...)` — a **CENTRAL NEED**: `TokenLedgerApi` must gain a fence-safe `topUp` credit method (a `PURCHASE`-type, idempotent credit that grants tokens only, never a role/vote/weight). `TokenTransactionType.PURCHASE` already exists in the ledger for exactly this.

**The credit is idempotent** on `credit_event_id` (recorded on the `TopUp` and passed as the ledger idempotency key) so a redelivered SUCCEEDED callback credits exactly once — the same anti-double-credit discipline the ledger already enforces.

**🔒 Fence (D18) — proven by construction and by test:**
- The credit path takes `(ownerType, ownerId, tokenAmount, idempotencyKey)` and produces a convenience-wallet `PURCHASE` credit **only**. There is no code path from a payment to a role grant, a signature, a rating, a poll outcome, routing/SLA/priority, or a verification status.
- `payments` does **not** depend on `engagement`/`accountability` (no binding-action surface in its compile graph) and never reads a token balance for authorization.
- A unit test asserts the fence: a SUCCEEDED top-up calls exactly `WalletCreditPort.creditPurchase` and **no democratic-weight API**, and fails closed if a credit is attempted on a non-SUCCEEDED status.

### 5. Outbox event (async, additive)

On SUCCEEDED, `ReconciliationService` (inside its `@Transactional`, after the status write) appends `EventEnvelope<TopUpSucceeded>` via `common.outbox.OutboxWriter` (ADR-0014). `TopUpSucceeded` is a new record in `payments.api.event` carrying **ids/codes/amounts only — NO PII, no MSISDN** (PRD §18): `{topUpId, buyerId, walletOwnerType, tokenAmount, provider}`. This unblocks a later receipt-notification / analytics handler without coupling the credit path to them. The **wallet credit itself is synchronous** (the citizen expects their tokens promptly and the credit is the whole point of the flow); only the *notification/analytics* fan-out is deferred to the bus.

## Consequences

- (+) **Phase-2 headline delivered behind the fence (PRD §23.6, D18):** tokens become purchasable via mobile money, and the purchase produces a convenience-wallet top-up only — never democratic weight. The fence is preserved *by construction* (no binding-action dependency, no balance read) and pinned by a test.
- (+) **Idempotent, reconciliation-driven settlement (PRD §23.5):** unique `idempotency_key` (initiation), partial-unique `provider_ref` (settlement), and `credit_event_id` (the credit) make duplicate/out-of-order callbacks no-ops; the credit is posted only after `verifySettled` confirms against the provider, never on the raw callback.
- (+) **KISS, pluggable, secret-free (ARCHITECTURE §7, CLAUDE.md §12):** one `MobileMoneyGateway` port, a prod-bootable logging stub default, per-rail `@ConditionalOnProperty` adapters with env-only secrets, exactly one bean per environment. The system boots and CI runs with zero external calls.
- (+) **Clean boundary (ADR-0013):** payments depends only on `common`, its own internals, and `tokens.api`; it credits the wallet only through a published port. `ModuleBoundaryTest` stays GREEN.
- (−) **Two CENTRAL NEEDs** the owning teams must land before the real flow runs end-to-end: (a) `tokens.api.TokenLedgerApi.topUp(...)` (a fence-safe `PURCHASE` credit) + its impl, and (b) `common.security.SecurityConfig` permitting `POST /payments/webhook/**`. Until (a) lands, payments credits through its logging stub adapter (the flow is fully tested against the port). Until (b) lands, the webhook is HMAC-secured but not anonymously reachable.
- (−) **A second `Payment`-shaped table** (`top_up`) coexists with the legacy `tokens.payment` MVP stub. Accepted: the legacy seam stays as history; `top_up` is the live flow's aggregate. A later cleanup can deprecate `tokens.payment` once nothing references it.
- (−) **Synchronous wallet credit** on the callback path adds a tokens-module call inside the reconciliation transaction. Accepted: the credit is the purpose of the flow and must be visible immediately; it is idempotent and the only synchronous cross-module command (a metering-style command, sanctioned by ADR-0013 §1 as the `TokenLedgerApi` shape). Notification/analytics stay async via the outbox.
- **Revisit triggers:** (a) **card / non-mobile rails** → add a sibling port/adapter (CARD already exists in tokens' enum; out of this Phase-2 mobile-money scope); (b) **refunds/chargebacks** → add a `REFUND`-type credit reversal (the ledger type exists); (c) **a real broker lands** (ADR-0014 revisit) → `TopUpSucceeded` rides it unchanged; (d) **velocity/AML limits** → add a per-buyer/day collection cap in `TopUpService` (the natural place), behind config.

## Decision summary

- **New module `com.taarifu.payments`** (four-layer; Flyway block `V130`–`V139`, `V130` = `top_up`). `TopUp extends BaseEntity`, references buyer/wallet by opaque `UUID` (no cross-module FK), money in **minor units**, status `INITIATED→PENDING→SUCCEEDED|FAILED`, **unique** `idempotency_key`, **partial-unique** `(provider, provider_ref)`, idempotent `credit_event_id`.
- **`MobileMoneyGateway` port** with a **logging stub default** + per-rail `@ConditionalOnProperty` adapters (M-Pesa/Tigo Pesa/Airtel Money/HaloPesa), **secrets from env only**, exactly one bean per environment, degrade-don't-crash.
- **Endpoints:** `POST /payments/top-ups` (initiate, JWT + `@PreAuthorize("isAuthenticated()")`, `Idempotency-Key`), `GET /payments/top-ups/{publicId}` (status, own-only), `POST /payments/webhook/{provider}` (**HMAC-verified, fail-closed, idempotent on `provider_ref`**, no JWT).
- **Credit = top-up ONLY**, via `WalletCreditPort` → (`tokens-api` adapter) `tokens.api.TokenLedgerApi.topUp` — **CENTRAL NEED**; idempotent on `credit_event_id`; **never** a role/vote/weight. `TopUpSucceeded` (ids/amounts only, NO PII) emitted via the outbox for async receipt/analytics.
- **🔒 Fence (D18):** no binding-action dependency in the payments compile graph; no balance read in any authorization path; a unit test fails closed if the credit path is anything other than a convenience top-up.
- **CENTRAL NEEDs:** (1) `tokens.api.TokenLedgerApi.topUp(WalletOwnerType, UUID, long tokenAmount, String idempotencyKey)` + impl (fence-safe `PURCHASE` credit); (2) `common.security.SecurityConfig` → permit `POST /payments/webhook/**` in `PUBLIC_POST_PATTERNS`.

---

## Addendum (Phase-2 Wave-2): reconciliation job + refunds/voids + admin payment query

**Status:** Accepted · 2026-06-25 · Backend Engineer (Baraka Mushi) · Phase-2 Wave-2. Deepens this ADR beyond the happy path; realises revisit triggers **(b) refunds/chargebacks** and adds an operations read surface + a scheduled settlement safety net. **Flyway:** `V131` (within the reserved payments block `V130`–`V139`). The instruction header's "`V166`–`V169`" is superseded by the module's own documented reservation (ADR-0015 §1) — `V131` is the next free migration in the payments block; nothing else uses `V131`.

### A1. `@Scheduled` reconciliation job — `TopUpReconciliationJob`
A safety net for lossy TZ rails: webhooks get lost/duplicated/reordered, so a top-up can sit `PENDING` forever. Each tick (fixed-delay, default 5m) claims a **bounded, row-locked** batch of stale non-terminal rows (`TopUpRepository.claimStaleNonTerminalForUpdate`, `FOR UPDATE` + page-size cap — disjoint per instance, short lock; the outbox-relay claim discipline) and per row either (a) **re-confirms** against the provider by reusing the exact idempotent, fence-safe `ReconciliationService.reconcile` path (never trust-the-callback — there is **one** settlement code path), or (b) **expires** a row older than `expireAfter` to `FAILED(RECONCILE_EXPIRED)` so the citizen UI can settle on a final state, or (c) leaves a young un-settled row `PENDING` (a benign mismatch, counted/logged). **Idempotent + double-credit-safe** (the `isTerminal()` short-circuit + the stable per-top-up credit key; a tick and a webhook racing the same row credit once). **Gated** by `taarifu.payments.reconciliation.enabled` (default `true`, `matchIfMissing`) — the same enabled-toggle pattern as the outbox relay, so it is disabled in tests (`application-test.yml`) to avoid racing ITs. `@EnableScheduling` is already provided centrally by `common.outbox` — **no central change needed**. Logs counts + rail only, **no PII**.

### A2. REFUND / VOID — `RefundService` + `WalletReversalPort`
A new state-machine edge: `SUCCEEDED → REFUNDED` (reverse a settled credit) and `INITIATED|PENDING → VOIDED` (cancel an un-settled attempt, **no** wallet effect). `TopUpStatus` gains `VOIDED`/`REFUNDED`; `TopUp` gains `markVoided`/`markRefunded`, `isRefundable()`, and `reversal_event_id`/`reversal_reason` (`V131`). `isTerminal()` is redefined as **settlement-terminal** (any non-`INITIATED/PENDING` status — so a redelivered callback is still a no-op on `SUCCEEDED`) while `SUCCEEDED` stays **refund-eligible**. The reversal goes through a new outbound port `WalletReversalPort` (mirror of `WalletCreditPort`): a logging stub (the wired default) + a `tokens-api` adapter. **Idempotent** on a stable per-top-up `reversal_event_id` (a retried refund debits once); a refund of an already-`REFUNDED` row is a no-op; a refund of a non-`SUCCEEDED` row (or a void of a settled row) is a typed `CONFLICT`; a blank reason is `BAD_REQUEST` (auditability). A DB `CHECK` binds `reversal_event_id` to the `REFUNDED` status so a credited-then-reversed row is distinguishable from a never-credited void at the database. **🔒 D18 holds:** a refund reverses **only** convenience tokens — never a signature/rating/poll outcome/role/routing/SLA/verification; `RefundService` has no binding-action dependency and no balance read.

### A3. Admin payment query — `PaymentQueryApi` (`com.taarifu.payments.api`)
A published, read-only `api` port (entity-free; the `ModuleBoundaryTest.noEntityInPublishedApiOrEvents` rule applies) implemented by `PaymentQueryService`, surfaced at `GET /admin/payments` (list/search by status/provider/date window, paged), `GET /admin/payments/totals` (counts + **net settled** [excludes refunds] / refunded sums, minor units), `GET /admin/payments/{id}` (single status), plus `POST /admin/payments/{id}/refund` and `/void`. All `@PreAuthorize("hasRole('ADMIN')")` (ROOT inherits via the role hierarchy). **MSISDN masked → satisfied by construction:** the payer number is *never persisted* on `TopUp`, so there is nothing to mask; `AdminPaymentDto` is a value-field record with **no MSISDN field** (a test pins this), and carries only redacted machine reasons + an opaque `buyerId` (no PII, no secret).

### A4. Pre-existing build fix (in-module)
The worktree's `AbstractHmacMobileMoneyGateway` did **not** implement `MobileMoneyGateway.parseCallback` (declared on the port, called by the webhook controller) — a pre-existing compile break at `HEAD`. Implemented in the abstract base as a tolerant, alias-aware JSON parse of `(providerRef, settled)` (the claim never credits — only `verifySettled` does). Confined to `infrastructure.adapter` (no vendor type on the port; `domainPortsHaveNoVendorImports` stays GREEN).

### Addendum CENTRAL NEED
- **(3) `tokens.api.TokenLedgerApi.refund(WalletOwnerType, UUID accountPublicId, long amount, String paymentReference)` + impl** — a fence-safe `REFUND`-type reversal of a prior `PURCHASE`, idempotent on `paymentReference` (the `TokenTransactionType.REFUND` ledger type already exists). Until it lands, `taarifu.payments.wallet-reversal.adapter` stays `logging` (the refund flow is fully tested against the port); the `tokens-api` reversal adapter binds the method **reflectively** (the same sanctioned isolation crutch this ADR used for `topUp`) and **fails fast** with a clear message if selected before the method exists — never a silent no-op refund.
