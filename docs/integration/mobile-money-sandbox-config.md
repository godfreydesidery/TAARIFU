# Mobile-money sandbox configuration & callback URLs (EI-20)

> **Owner:** Integrations Engineer · **Module:** `com.taarifu.payments` · **ADR:** ADR-0015 · **PRD:** §23.4/§23.5/§23.6, §21 EI-20, §18 (PDPA), DI1/DI3/DI4/DI6.
>
> Operational note for wiring the four real Tanzanian mobile-money rails behind the single `MobileMoneyGateway` port. **The logging stub is the wired default** — `dev`/`test` and any no-profile production context boot with zero secrets and zero external calls. A real rail is activated **only** by an explicit `taarifu.payments.gateway.provider` value, and exactly one gateway bean is ever active (the `@ConditionalOnProperty` selector, mirroring the `SmsGateway` pattern).

## 1. How a rail is selected

Set exactly one provider value; everything else binds from the environment / secret manager (**never source**, PRD §18, CLAUDE.md §12):

```yaml
taarifu:
  payments:
    gateway:
      provider: mpesa            # logging (default) | mpesa | tigopesa | airtelmoney | halopesa
      base-url: ${PAYMENTS_RAIL_BASE_URL}      # rail HTTPS base (no trailing slash)
      hmac-secret: ${PAYMENTS_RAIL_HMAC_SECRET} # callback HMAC shared secret (SECRET)
      merchant-id: ${PAYMENTS_RAIL_MERCHANT_ID} # short-code / merchant / business number (NOT a secret)
      signature-header: X-Signature             # header the rail presents the callback HMAC on
      price-minor-per-token: 100                # TZS minor units per token
      currency: TZS
      request-timeout: 8s                       # per-request connect/read budget (no thread pile-up)
```

- An active real adapter with a **blank `base-url` or `hmac-secret` fails fast at construction** — it will not boot silently accepting forged callbacks or 500 on the first top-up.
- `merchant-id` is sent in the collection request (so it is not a secret) but is still env-bound so it is never hard-coded. Rails that do not need one (Airtel in sandbox) leave it null.

## 2. Callback (webhook) URL — same for every rail

Register this single URL with the aggregator, substituting the rail segment:

```
POST  https://<host>/api/v1/payments/webhook/{PROVIDER}
        {PROVIDER} ∈ MPESA | TIGOPESA | AIRTELMONEY | HALOPESA   (the MobileMoneyProvider enum name)
Header: X-Signature: <lowercase-hex HMAC-SHA256 of the EXACT raw body, keyed on hmac-secret>
```

- **Authentication is the HMAC**, not a user JWT (the `/ussd/gateway` shared-secret precedent). The signature is computed over the **exact raw bytes** received — re-serialising the parsed body would break it.
- **Fail-closed:** an invalid/missing signature → benign `200` with **no state change** and no reason disclosed (no forgery oracle). The aggregator is never encouraged to retry-storm on a 4xx/5xx for an event we already handled.
- **Never-trust-the-callback (PRD §23.5):** the callback's claimed outcome never credits a wallet. Reconciliation re-confirms settlement **against the rail's status endpoint** before crediting, and credits **exactly once** (idempotent on `(provider, provider_ref)` + a per-top-up credit key).
- **Privacy:** the raw body and any MSISDN it carries are never logged; only the rail and a verified/ignored outcome are.

> **CENTRAL NEED:** `common.security.SecurityConfig` must add `POST /payments/webhook/**` to its `PUBLIC_POST_PATTERNS` (the `/ussd/gateway` precedent) so the aggregator can reach the URL without a user token. Until then the endpoint is HMAC-secured in code but unreachable anonymously. (Declared on `PaymentWebhookController`.)

## 3. Per-rail specifics (request, callback, status)

The shared `AbstractHmacMobileMoneyGateway` owns transport + HMAC; each adapter overrides only what genuinely differs (Template-Method hooks: `collectionPath`, `buildCollectionBody`, `extractProviderRef`, `parseCallbackBody`, `statusPath`, `parseStatus`).

### 3.1 M-Pesa — Vodacom (Daraja-style STK push) · `provider=mpesa`
- **Collection:** `POST {base}/mpesa/stkpush/v1/processrequest` — `{BusinessShortCode(=merchant-id), TransactionType:"CustomerPayBillOnline", Amount, PartyA, PhoneNumber, AccountReference(=idempotencyKey), TransactionDesc}`.
- **Ref:** `CheckoutRequestID` (from the submit JSON envelope), used for callback + status correlation.
- **Callback:** nested `Body.stkCallback.{CheckoutRequestID, ResultCode}`; **`ResultCode == 0` is success** (Daraja convention).
- **Status:** `GET {base}/mpesa/stkpushquery/v1/query/{CheckoutRequestID}` → settled iff `ResultCode == 0`.
- `merchant-id` = the M-Pesa **BusinessShortCode**. Sandbox: Daraja test short-code (e.g. `174379`).

### 3.2 Tigo Pesa — Mixx by Yas · `provider=tigopesa`
- **Collection:** `POST {base}/push-billpay` — `{referenceId(=idempotencyKey), msisdn, amount, currency, merchantAccountNumber(=merchant-id)}`.
- **Ref:** `referenceId` (echoed) / `transactionReference`.
- **Callback:** `{referenceId, status}`; **`status == "SUCCESS"` is settled**, `"FAILED"` is not.
- **Status:** `GET {base}/push-billpay/{referenceId}` → settled iff `status == SUCCESS`.

### 3.3 Airtel Money · `provider=airtelmoney`
- **Collection:** `POST {base}/merchant/v1/payments/` — nested `subscriber.{country:"TZ", currency, msisdn}` + `transaction.{amount, country:"TZ", currency, id(=idempotencyKey)}`.
- **Ref:** `data.transaction.id` (echoed).
- **Callback:** nested `transaction.{id, status.code}`; **`status.code == "TS"` (Transaction Success) is settled**, `"TF"` is not.
- **Status:** `GET {base}/standard/v1/payments/{transactionId}` → settled iff `status.code == "TS"`.
- `merchant-id` is optional in sandbox (omit / leave null).

### 3.4 HaloPesa — Halotel · `provider=halopesa`
- **Collection:** `POST {base}/api/PushPayment` — `{externalId(=idempotencyKey), msisdn, amount, currency, businessNumber(=merchant-id)}`.
- **Ref:** `transactionId` / `externalId` (echoed).
- **Callback:** `{externalId, responseCode}`; **`responseCode == "000"` (or numeric `0`) is success**.
- **Status:** `GET {base}/api/PushPayment/{externalId}` → settled iff a success code.

## 4. Resilience & degradation (EI-20)

- **Timeout** (`request-timeout`, default 8s) on every synchronous outbound call so a slow rail never piles up threads.
- **Degrade-don't-crash:** a transport failure on initiation returns a not-accepted result; `TopUpService` marks the top-up `FAILED` and surfaces a typed `SERVICE_UNAVAILABLE`. **The free token path is always available** — purchase being unavailable never blocks the citizen.
- **Settlement verify also degrades:** an unconfirmed/erroring status check → not-settled → not credited (a top-up is never credited on an unconfirmed reference).
- **D18 fence:** the only effect of a settled top-up is a convenience-wallet credit (`WalletCreditPort.creditPurchase`). No role/vote/weight is ever granted; no balance is read for authorization. A purchased token buys convenience/reach only — never democratic weight.

## 5. Sandbox onboarding checklist (per rail, before go-live)

1. Procure rail sandbox credentials + the callback HMAC secret (store in the secret manager).
2. Set `provider`, `base-url`, `hmac-secret`, `merchant-id` for the target environment (env, not source).
3. Register the callback URL `…/api/v1/payments/webhook/{PROVIDER}` with the aggregator; confirm the signature header name matches `signature-header`.
4. Ensure `POST /payments/webhook/**` is in the security public-POST allow-list (CENTRAL NEED, §2).
5. Run an end-to-end sandbox top-up; confirm: push received on handset → callback HMAC verifies → status query confirms → wallet credited **exactly once** → `TopUpSucceeded` emitted (ids/amounts only, no PII).
6. Verify dashboards (DI6): per-adapter success/error rate, p95 latency, settlement-confirm rate, and spend; alert on error spikes and on DLQ.
