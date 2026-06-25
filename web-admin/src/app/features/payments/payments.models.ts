/**
 * Mobile-money payments admin DTOs — mirror the backend Phase-2 payments admin contract
 * (ADR-0015 + addendum; PRD §23 token-economy top-up, §18 privacy, §21 EI-20 MobileMoneyProvider).
 *
 * <p>WHY these shapes: Phase 2 lets citizens/orgs BUY convenience tokens via Tanzanian mobile money
 * (M-Pesa, Tigo Pesa, Airtel Money, HaloPesa) through a `MobileMoneyGateway` adapter. The server models each
 * purchase as a `TopUp` whose admin view is `AdminPaymentDto` (this is the source of truth these types mirror
 * — `GET/POST /admin/payments`). CRITICAL GUARDRAIL (D18, PRD §23): tokens never buy democratic weight — this
 * is a convenience-wallet top-up ledger, NOT a way to weight votes/signatures; refund/void touch ONLY the
 * convenience wallet, never a role/vote/weight. The UI copy reinforces that (the §23 fence note).</p>
 *
 * <p>PII discipline (PRD §18, PDPA, DI5): by construction the backend `AdminPaymentDto` carries NO MSISDN —
 * the payer's phone number is never persisted on the `TopUp` aggregate, so there is nothing to leak (the
 * "MSISDN masked" requirement is satisfied because the field does not exist). The buyer is an OPAQUE account
 * UUID (`buyerId`) — never a national/voter ID. `failureReason`/`reversalReason` are redacted machine codes
 * only. The console therefore renders only opaque ids + machine codes; it never shows a raw phone or PII.</p>
 */

/** Tanzanian mobile-money rails (mirror the backend `MobileMoneyProvider` enum; PRD §23.4). CARD is Phase-2+. */
export const PAYMENT_PROVIDERS = ['MPESA', 'TIGOPESA', 'AIRTELMONEY', 'HALOPESA'] as const;

/** One mobile-money provider token. */
export type PaymentProvider = (typeof PAYMENT_PROVIDERS)[number];

/**
 * Top-up lifecycle status tokens (mirror the backend `TopUpStatus`; ADR-0015).
 *
 * <p>Transitions an operator cares about: a SUCCEEDED top-up may be REFUNDED (reverse the credit), and an
 * un-settled INITIATED/PENDING attempt may be VOIDED (cancel, no wallet effect). FAILED/VOIDED/REFUNDED are
 * terminal.</p>
 */
export const PAYMENT_STATUSES = ['INITIATED', 'PENDING', 'SUCCEEDED', 'FAILED', 'VOIDED', 'REFUNDED'] as const;

/** One top-up-status token. */
export type PaymentStatus = (typeof PAYMENT_STATUSES)[number];

/**
 * One mobile-money payment (top-up) row — mirrors the backend admin `AdminPaymentDto`. PII-minimised by
 * construction: an OPAQUE buyer account id only, no MSISDN, no national/voter ID (PRD §18, DI5). Money is in
 * MINOR units (e.g. cents) — never floating point; format with {@link minorToMajor} for display.
 */
export interface Payment {
  /** The top-up's immutable public id (UUID) — the operator-facing reference for refund/void. */
  id: string;
  /** The buyer's account public id (opaque UUID; never PII), or `null` if absent. */
  buyerId: string | null;
  /** Lifecycle status name. */
  status: PaymentStatus;
  /** Which mobile-money rail processed it. */
  provider: PaymentProvider;
  /** Provider settlement reference for reconciliation (an internal handle, not a secret), or `null`. */
  providerRef: string | null;
  /** Amount in MINOR currency units (e.g. cents); a non-negative integer. */
  amountMinor: number;
  /** Tokens credited on success (display only — tokens never buy democratic weight, §23/D18). */
  tokenAmount: number;
  /** ISO-4217 currency code (TZS for Tanzania mobile money). */
  currency: string;
  /** Redacted machine reason if FAILED, else `null` (no PII; PRD §18). */
  failureReason: string | null;
  /** Redacted machine reason if VOIDED/REFUNDED, else `null` (no PII; PRD §18). */
  reversalReason: string | null;
  /** When the top-up was initiated (ISO-8601 UTC). */
  createdAt: string;
  /** When it last changed state (ISO-8601 UTC), or `null` if never updated. */
  updatedAt: string | null;
}

/**
 * The payments totals strip (mirrors the backend admin `PaymentTotalsDto`). Aggregate counts + net money for
 * the active filter window, so the operator sees the picture without summing rows across pages. Money is in
 * MINOR units. `settledAmountMinor` is NET (SUCCEEDED only) — refunded money is surfaced separately.
 */
export interface PaymentTotals {
  /** Count of SUCCEEDED top-ups in the window. */
  succeededCount: number;
  /** Count of FAILED top-ups in the window. */
  failedCount: number;
  /** Count of still-PENDING top-ups in the window. */
  pendingCount: number;
  /** Count of REFUNDED top-ups in the window. */
  refundedCount: number;
  /** Summed amount of SUCCEEDED top-ups (net settled, minor units). */
  settledAmountMinor: number;
  /** Summed amount of REFUNDED top-ups (returned, minor units). */
  refundedAmountMinor: number;
  /** ISO-4217 currency the amounts are expressed in. */
  currency: string;
}

/** Filters for the payments list (`GET /admin/payments`); every field optional (`undefined` = no constraint). */
export interface PaymentListFilter {
  /** Provider filter. */
  provider?: string;
  /** Status filter. */
  status?: string;
  /** Inclusive window start (ISO-8601 date-time). */
  from?: string;
  /** Inclusive window end (ISO-8601 date-time). */
  to?: string;
  /** Zero-based page index. */
  page?: number;
  /** Page size (capped server-side). */
  size?: number;
}

/**
 * The body for a refund/void admin action (`POST /admin/payments/{id}/refund|void`) — mirrors the backend
 * `RefundTopUpRequest`. Carries ONLY the required audit reason; the acting administrator is taken from the
 * security context server-side, never the body. The reason is a short machine/audit code (e.g.
 * `DUPLICATE_CHARGE`) and MUST NOT contain PII (PRD §18); it is capped at 256 chars server-side.
 */
export interface RefundTopUpRequest {
  /** The required audit reason for the reversal (1–256 chars; no PII). */
  reason: string;
}

/**
 * A single derived step in a top-up's status timeline. The backend exposes no separate history endpoint, so
 * the detail view DERIVES this from the one `AdminPaymentDto` (created → current state) — an honest, contract-
 * faithful reconstruction rather than an invented audit trail.
 */
export interface PaymentTimelineStep {
  /** The status this step represents. */
  status: PaymentStatus;
  /** When this step occurred (ISO-8601), or `null` if unknown. */
  at: string | null;
  /** A redacted machine reason for this step (failure/reversal), or `null`. */
  reason: string | null;
  /** Whether this step is the payment's current (latest) state. */
  current: boolean;
}
