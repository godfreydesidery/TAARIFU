/**
 * Mobile-money payments admin DTOs — mirror the backend Phase-2 `Payment` admin query contract
 * (D19 payments; PRD §23 token economy purchase, §21 EI-20 PaymentProvider).
 *
 * <p>WHY these shapes: Phase 2 lets citizens/orgs BUY tokens via mobile money (M-Pesa, Tigo Pesa, Airtel
 * Money, HaloPesa) + card, through a `PaymentProvider` adapter (PRD §23). The `Payment` record carries
 * {wallet, package, provider, providerRef, amount, status, idempotencyKey} (PRD §23). The admin console
 * reads a paged, filterable list of these for reconciliation and a totals strip. CRITICAL GUARDRAIL: tokens
 * never buy democratic weight (§23) — this is a wallet top-up ledger, NOT a way to weight votes/signatures;
 * the UI copy reinforces that.</p>
 *
 * <p>PII discipline (PRD §18, PDPA, DI5): the payer MSISDN is the citizen's phone — sensitive PII. It is
 * shown MASKED in the list (e.g. `+2557****1234`); the backend is expected to return it already masked, and
 * this client additionally masks defensively (see `maskMsisdn`). No national/voter ID ever appears on a
 * payment row. The console acts on the immutable {@link Payment.id} / {@link Payment.providerRef}.</p>
 */

/** Mobile-money / card provider tokens (mirror the backend `PaymentProvider` enum; PRD §23). */
export const PAYMENT_PROVIDERS = ['MPESA', 'TIGOPESA', 'AIRTELMONEY', 'HALOPESA', 'CARD'] as const;

/** One payment-provider token. */
export type PaymentProvider = (typeof PAYMENT_PROVIDERS)[number];

/** Payment lifecycle status tokens (mirror the backend `PaymentStatus`; PRD §23). */
export const PAYMENT_STATUSES = ['PENDING', 'PAID', 'FAILED', 'REFUNDED'] as const;

/** One payment-status token. */
export type PaymentStatus = (typeof PAYMENT_STATUSES)[number];

/**
 * One mobile-money payment row (mirrors the backend admin `PaymentDto`). PII-minimised: a MASKED payer
 * MSISDN only — never the raw number or any national/voter ID (PRD §18, DI5).
 */
export interface Payment {
  /** The payment's immutable public id (ULID/UUID). */
  id: string;
  /** Provider reference / transaction id from the mobile-money network (for reconciliation). */
  providerRef: string | null;
  /** Which network/instrument processed it. */
  provider: PaymentProvider;
  /** Lifecycle status name. */
  status: PaymentStatus;
  /** Amount in minor units? No — the backend returns a decimal major-unit amount; see {@link currency}. */
  amount: number;
  /** ISO-4217 currency code (TZS for Tanzania mobile money). */
  currency: string;
  /** Token package code purchased (e.g. `PACK_SMALL`), or `null` for an ad-hoc top-up. */
  packageCode: string | null;
  /** Number of tokens credited on success (display only — tokens never buy democratic weight, §23). */
  tokens: number | null;
  /** The payer's MSISDN, masked (e.g. `+2557****1234`); never the raw number (PRD §18). */
  maskedMsisdn: string | null;
  /** The buyer account's public id (never the raw identity). */
  accountId: string | null;
  /** When the payment was initiated (ISO-8601). */
  createdAt: string;
  /** When it reached a terminal status (ISO-8601), or `null` if still pending. */
  settledAt: string | null;
}

/**
 * The payments totals strip (mirrors the backend admin `PaymentTotalsDto`). Aggregate sums for the active
 * filter window, so the operator sees the money picture without summing rows client-side across pages.
 */
export interface PaymentTotals {
  /** ISO-4217 currency the totals are expressed in. */
  currency: string;
  /** Total count of payments matching the filter. */
  count: number;
  /** Sum of `PAID` amounts (the collected total). */
  paidAmount: number;
  /** Count of `PAID` payments. */
  paidCount: number;
  /** Sum of `PENDING` amounts (in flight). */
  pendingAmount: number;
  /** Sum of `FAILED` amounts. */
  failedAmount: number;
  /** Sum of `REFUNDED` amounts (money returned). */
  refundedAmount: number;
}

/** Filters for the payments list (`GET /admin/payments`); every field optional (`undefined` = no constraint). */
export interface PaymentListFilter {
  /** Provider filter. */
  provider?: string;
  /** Status filter. */
  status?: string;
  /** Inclusive window start (ISO-8601 date). */
  from?: string;
  /** Exclusive window end (ISO-8601 date). */
  to?: string;
  /** Zero-based page index. */
  page?: number;
  /** Page size (capped server-side). */
  size?: number;
}
