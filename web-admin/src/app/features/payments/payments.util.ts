import { Payment, PaymentTimelineStep } from './payments.models';

/**
 * Pure helpers for the payments admin view. Centralises money formatting (minor → major units), the
 * refund/void state-machine gates (mirroring the backend `TopUpStatus` legal transitions), the derived status
 * timeline, and MSISDN masking. The masking helper is retained as belt-and-braces defence in depth even though
 * the backend `AdminPaymentDto` carries no MSISDN by construction (PRD §18, DI5) — if any future/non-prod
 * payload were to surface a number, the console must still never render it raw.
 */

/**
 * Converts an integer MINOR-unit amount (e.g. cents) to a major-unit decimal number for display.
 *
 * <p>WHY: the backend returns money in minor units to avoid floating-point drift (PaymentTotalsDto/
 * AdminPaymentDto). The UI divides by 100 only at the presentation edge; callers then format with a locale-
 * aware `number`/`currency` pipe. A `null`/`undefined` input yields `0` so a missing amount renders cleanly.</p>
 *
 * @param minor the amount in minor currency units, or `null`/`undefined`.
 * @returns the major-unit decimal amount.
 */
export function minorToMajor(minor: number | null | undefined): number {
  if (minor === null || minor === undefined || Number.isNaN(minor)) {
    return 0;
  }
  return minor / 100;
}

/**
 * Whether a top-up may be REFUNDED from the console — only a SETTLED (SUCCEEDED) top-up.
 *
 * <p>Mirrors the backend `TopUpStatus` state machine (SUCCEEDED → REFUNDED). The client gate is purely a UX
 * affordance — it enables the action only when the server would accept it — and is NOT the authority: the
 * server re-checks and returns 409 if the row changed underfoot (ADR-0015). Tokens never buy democratic
 * weight; the refund reverses only convenience tokens (D18).</p>
 *
 * @param payment the loaded top-up, or `null`.
 * @returns `true` if a refund action should be enabled.
 */
export function canRefund(payment: Payment | null): boolean {
  return payment?.status === 'SUCCEEDED';
}

/**
 * Whether a top-up may be VOIDED from the console — only an UN-SETTLED (INITIATED/PENDING) attempt.
 *
 * <p>Mirrors the backend `TopUpStatus` state machine (INITIATED/PENDING → VOIDED, no wallet effect). UX-gate
 * only; the server is the authority and returns 409 if already settlement-terminal (ADR-0015).</p>
 *
 * @param payment the loaded top-up, or `null`.
 * @returns `true` if a void action should be enabled.
 */
export function canVoid(payment: Payment | null): boolean {
  return payment?.status === 'INITIATED' || payment?.status === 'PENDING';
}

/**
 * Derives a top-up's status timeline from the single `AdminPaymentDto`.
 *
 * <p>WHY derived (not fetched): the backend exposes no payment-history endpoint, so an HONEST reconstruction
 * from the one DTO is the contract-faithful option — never an invented audit trail. The timeline always shows
 * the INITIATED step (at `createdAt`) and, when the row has progressed past it, the CURRENT status step (at
 * `updatedAt`) carrying any redacted failure/reversal reason. This keeps the operator oriented (when it
 * started, where it is now, why) without claiming intermediate transitions the client cannot prove.</p>
 *
 * @param payment the loaded top-up, or `null`.
 * @returns an ordered list of {@link PaymentTimelineStep} (oldest first), or `[]` if no payment.
 */
export function buildTimeline(payment: Payment | null): PaymentTimelineStep[] {
  if (!payment) {
    return [];
  }
  const steps: PaymentTimelineStep[] = [];
  const isInitiated = payment.status === 'INITIATED';
  // Step 1 — always: the attempt was created.
  steps.push({
    status: 'INITIATED',
    at: payment.createdAt,
    reason: null,
    current: isInitiated,
  });
  // Step 2 — only when the row has moved on from INITIATED: the current state.
  if (!isInitiated) {
    const reason: string | null = payment.reversalReason ?? payment.failureReason ?? null;
    steps.push({
      status: payment.status,
      at: payment.updatedAt ?? payment.createdAt,
      reason,
      current: true,
    });
  }
  return steps;
}

/**
 * Masks an MSISDN for display, keeping only the country/prefix and the last 4 digits.
 *
 * <p>WHY a client-side mask even though the backend never stores an MSISDN: defence in depth. If any
 * environment/endpoint were to surface a number, the console must still not leak it (PRD §18 PII-redaction,
 * DI5 MSISDN minimisation). A value that is already masked (contains `*`) or too short to mask is returned
 * unchanged.</p>
 *
 * @param msisdn the raw or already-masked number, or `null`.
 * @returns the masked number (e.g. `+2557****1234`), or `null` if input was `null`.
 */
export function maskMsisdn(msisdn: string | null | undefined): string | null {
  if (!msisdn) {
    return null;
  }
  // Already masked, or no realistic digits to mask — leave as-is.
  if (msisdn.includes('*')) {
    return msisdn;
  }
  const digits = msisdn.replace(/[^\d]/g, '');
  if (digits.length <= 6) {
    return msisdn;
  }
  const prefix = msisdn.startsWith('+') ? '+' : '';
  const head = digits.slice(0, 4);
  const tail = digits.slice(-4);
  return `${prefix}${head}****${tail}`;
}
