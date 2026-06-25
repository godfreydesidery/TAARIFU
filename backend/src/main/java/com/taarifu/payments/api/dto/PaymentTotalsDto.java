package com.taarifu.payments.api.dto;

/**
 * ADMIN summary totals over a payment query window (ADR-0015 addendum; PRD §18).
 *
 * <p>Responsibility: the aggregate figures an operator needs alongside the paged list — how much settled,
 * how many succeeded/failed/pending/refunded — computed over the same optional status/provider/date filter.
 * Money is in <b>minor units</b> (never floating-point); counts are plain longs. No PII, no secrets.</p>
 *
 * <p>WHY {@code settledAmountMinor} totals only SUCCEEDED (not REFUNDED): it is the <b>net settled revenue</b>
 * still standing — a refunded top-up's money was returned, so it is excluded from the settled total and
 * surfaced separately via {@code refundedCount}/{@code refundedAmountMinor} for reconciliation.</p>
 *
 * @param succeededCount      number of SUCCEEDED top-ups in the window.
 * @param failedCount         number of FAILED top-ups in the window.
 * @param pendingCount        number of still-PENDING top-ups in the window.
 * @param refundedCount       number of REFUNDED top-ups in the window.
 * @param settledAmountMinor  summed {@code amount_minor} of SUCCEEDED top-ups (net settled, minor units).
 * @param refundedAmountMinor summed {@code amount_minor} of REFUNDED top-ups (returned, minor units).
 * @param currency            ISO-4217 currency the amounts are expressed in.
 */
public record PaymentTotalsDto(
        long succeededCount,
        long failedCount,
        long pendingCount,
        long refundedCount,
        long settledAmountMinor,
        long refundedAmountMinor,
        String currency
) {
}
