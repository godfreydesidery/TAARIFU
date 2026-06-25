# Communications channels — sandbox configuration (FCM push, SMS aggregator, DLR webhook, expiry sweep)

> **Owner:** Integrations. **Scope:** how to point the `communications` module's real channel adapters at a
> **sandbox** (or leave them on the safe logging stubs), what **env vars** drive them, and how to switch on
> the announcement-expiry sweep. **No secrets live in source** (CLAUDE.md §12, PRD §18) — every credential is
> an environment placeholder; the values below are sandbox examples only.
>
> **Grounding:** PRD §21 EI-3 (SMS), EI-5 (push), §13 (channels/delivery status), §18 (PII); ARCHITECTURE §7
> (ports/adapters + stub-per-port); ADR-0004 (ports & adapters), ADR-0013 (`api → api`), ADR-0017 (search
> remove-on-expire).

## 0. The default is "no external calls"

Every channel port (`SmsGateway`, `PushSender`, `EmailSender`) selects its adapter by
`@ConditionalOnProperty` and **defaults to a prod-bootable logging stub** (`matchIfMissing = true`). With **no
configuration**, the whole platform boots and runs E2E with **zero external calls** — this is what makes
dev/test/CI and region-by-region staged onboarding work. You opt **in** to a real adapter per channel by
setting that channel's `provider`. The announcement-expiry sweep and (effectively) the SMS DLR webhook are
**off until configured** too.

| Port | Default bean (no config) | Real adapter | Selector |
|---|---|---|---|
| `SmsGateway` | `LoggingSmsGatewayStub` | `HttpSmsGateway` | `taarifu.communications.sms.provider=http` |
| `PushSender` | `LoggingPushSenderStub` | `FcmHttpPushSender` | `taarifu.communications.push.provider=fcm` |
| `EmailSender` | `LoggingEmailSenderStub` | `SmtpEmailSender` | `taarifu.communications.email.provider=smtp` |

## 1. FCM push (EI-5) — sandbox

`FcmHttpPushSender` is a **thin HTTP adapter** over FCM HTTP v1 (no firebase-admin SDK). It mints a
service-account-signed JWT, exchanges it for a short-lived OAuth2 bearer at Google's token endpoint, and POSTs
each message to `https://fcm.googleapis.com/v1/projects/<projectId>/messages:send`. It fans out to **every**
registered device token for the recipient and **prunes** `UNREGISTERED`/`INVALID_ARGUMENT` tokens.

**Env / config (non-secret holder: `CommunicationsChannelProperties.Push`):**

| Key | Sandbox example | Notes |
|---|---|---|
| `taarifu.communications.push.provider` | `fcm` | selects the real adapter; unset ⇒ logging stub |
| `taarifu.communications.push.project-id` | `taarifu-sandbox` | a **test** Firebase project |
| `taarifu.communications.push.credentials-file` | `/run/secrets/fcm-sa.json` | path to the GCP **service-account JSON**; mounted from a secret store — **never** committed |
| `taarifu.communications.push.request-timeout` | `5s` | token-exchange + send timeout |

```bash
export TAARIFU_COMMUNICATIONS_PUSH_PROVIDER=fcm
export TAARIFU_COMMUNICATIONS_PUSH_PROJECT_ID=taarifu-sandbox
export TAARIFU_COMMUNICATIONS_PUSH_CREDENTIALS_FILE=/run/secrets/fcm-sa.json
```

Sandbox tips: use a **dedicated test Firebase project** and a test device/emulator token. Payloads stay
**minimal** (title + short body + opaque deep-link ref) and are never logged — push transits third-party
infra (PRD §18). Degradation: a recipient with **no token** ⇒ the dispatcher falls back to SMS; the FEED item
is always retained (EI-5).

## 2. SMS aggregator (EI-3) — sandbox

`HttpSmsGateway` POSTs `{to, from, text, reference}` as JSON to the aggregator's HTTPS submit endpoint with the
API key on a configurable header; a 2xx is "accepted/queued". The `reference` carries our **idempotency key**
so a retry is deduped and the **DLR can be correlated back** (see §3).

**Env / config (`CommunicationsChannelProperties.Sms`):**

| Key | Sandbox example | Notes |
|---|---|---|
| `taarifu.communications.sms.provider` | `http` | selects the real adapter; unset ⇒ logging stub |
| `taarifu.communications.sms.submit-url` | `https://sandbox.sms-aggregator.co.tz/v1/submit` | the aggregator's **sandbox** submit URL |
| `taarifu.communications.sms.sender-id` | `TAARIFU` | the **TCRA-registered** alphanumeric sender-id / shortcode (D-Q7) — start procurement early (R27) |
| `taarifu.communications.sms.api-key` | `<sandbox-key>` | **env only, never committed** (PRD §18) |
| `taarifu.communications.sms.auth-header` | `Authorization` | header the key is sent in; `Authorization` ⇒ `Bearer <key>`, otherwise the raw key (e.g. `X-API-Key`) |
| `taarifu.communications.sms.request-timeout` | `5s` | per-request timeout so a slow aggregator never piles up threads |

```bash
export TAARIFU_COMMUNICATIONS_SMS_PROVIDER=http
export TAARIFU_COMMUNICATIONS_SMS_SUBMIT_URL=https://sandbox.sms-aggregator.co.tz/v1/submit
export TAARIFU_COMMUNICATIONS_SMS_SENDER_ID=TAARIFU
export TAARIFU_COMMUNICATIONS_SMS_API_KEY=<sandbox-key>
export TAARIFU_COMMUNICATIONS_SMS_AUTH_HEADER=Authorization
```

**Cost & rate note (R29, PRD §15):** SMS is **metered and costly** and is used **sparingly** — it is the
**fallback**, never the default; OTP is **never solely SMS-dependent** (email fallback always exists). Plan for
**multi-provider** routing (≥2 aggregators) for failover/least-cost where contracts allow; this adapter is one
route behind the `SmsGateway` port, so a second route is another adapter selected by config. The MSISDN and
body (which for OTP carries the code) are sent to the aggregator but **never logged** (only a masked recipient,
purpose, and length).

## 3. SMS delivery-report (DLR) webhook (EI-3) — sandbox

The aggregator confirms delivery asynchronously by calling **`POST /communications/sms/dlr`**. The handler is
**shared-secret authenticated, fail-closed, idempotent** on the correlation reference (the `/payments/webhook`
+ `/ussd/gateway` precedent). It correlates the DLR's `reference` (the aggregator's echo of our submit
`reference` = the dispatch row's idempotency key) to the `Notification` and advances it
`SENT → DELIVERED` / `→ FAILED` — non-regressing and replay-safe (out-of-order/duplicate DLRs are handled).

**Env / config (`SmsDlrProperties`, prefix `taarifu.communications.sms.dlr`):**

| Key | Sandbox example | Notes |
|---|---|---|
| `taarifu.communications.sms.dlr.secret` | `<sandbox-dlr-secret>` | shared secret the aggregator presents; **env only**. **Unset ⇒ the webhook is fail-closed** (accepts nothing) |
| `taarifu.communications.sms.dlr.header` | `X-DLR-Secret` | header carrying the secret (constant-time compared) |
| `taarifu.communications.sms.dlr.reference-field` | `reference` | JSON field carrying the echoed reference (the correlator) |
| `taarifu.communications.sms.dlr.status-field` | `status` | JSON field carrying the delivery status |
| `taarifu.communications.sms.dlr.delivered-value` | `DELIVERED` | status value meaning "delivered" (case-insensitive); any other ⇒ a failure report |

```bash
export TAARIFU_COMMUNICATIONS_SMS_DLR_SECRET=<sandbox-dlr-secret>
# field names default to reference/status/DELIVERED — override only if the aggregator differs
```

Point the aggregator's sandbox DLR/callback URL at `https://<your-host>/communications/sms/dlr` and configure
it to present the shared secret on `X-DLR-Secret`. Simulate one locally (once the route is allow-listed — see
**CENTRAL NEED** below):

```bash
curl -sS -X POST https://<your-host>/communications/sms/dlr \
  -H 'Content-Type: application/json' \
  -H 'X-DLR-Secret: <sandbox-dlr-secret>' \
  -d '{"reference":"<the submit reference>","status":"DELIVERED"}'
```

> **CENTRAL NEED:** `common.security.SecurityConfig` must add `POST /communications/sms/dlr` to its
> `PUBLIC_POST_PATTERNS` (the `/payments/webhook/**` + `/ussd/gateway` precedent) so the aggregator can reach
> it **without a user JWT**. The endpoint is fully secret-authenticated in code; until the route is
> allow-listed it returns 401 from the security filter.

## 4. Announcement-expiry sweep (PRD §12, ADR-0017 §1)

`AnnouncementExpiryScheduler` is a gated `@Scheduled` job that transitions `PUBLISHED` announcements past their
`expireAt` to `EXPIRED` and (via `AnnouncementService.expire`) **removes them from the search/discovery
index** so an expired announcement is not discoverable forever. It is **off by default** (safe-for-tests) — it
mutates shared announcement state, so it must not race tests.

**Env / config (`AnnouncementExpiryProperties`, prefix `taarifu.communications.announcement-expiry`):**

| Key | Default | Notes |
|---|---|---|
| `taarifu.communications.announcement-expiry.enabled` | `false` | set `true` in **exactly one** instance that should own the sweep |
| `taarifu.communications.announcement-expiry.cron` | every 15 min | six-field Spring cron (sec min hour day month weekday) |
| `taarifu.communications.announcement-expiry.zone` | `Africa/Dar_es_Salaam` | zone the cron is evaluated in (EAT) |
| `taarifu.communications.announcement-expiry.grace` | `PT0S` | grace subtracted from the cutoff (`expireAt <= now − grace`) |

```bash
export TAARIFU_COMMUNICATIONS_ANNOUNCEMENT_EXPIRY_ENABLED=true
```

Scheduling itself is enabled centrally by the outbox config's `@EnableScheduling` — this module declares none
(DRY). The sweep is idempotent and fail-soft per row, so two instances racing or a re-run never errors.

## 5. Verifying with zero external calls

Leave all three `provider` keys **unset** and the expiry/DLR secret **unset**: the logging stubs are active,
the expiry job bean does not exist, and the DLR webhook is fail-closed. The full notification/feed path runs
E2E in tests with **no network**. To exercise a real sandbox, set only the channel you are testing — the rest
stay on stubs.
