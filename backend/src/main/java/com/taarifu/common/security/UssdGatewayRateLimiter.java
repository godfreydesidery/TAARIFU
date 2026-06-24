package com.taarifu.common.security;

import com.taarifu.common.domain.port.CryptoPort;

/**
 * Anti-automation primitive for the open USSD aggregator webhook — per-MSISDN throttling of session
 * turns and new-dialogue (account-creation) bursts (wave2-review P2-1, THREAT-MODEL TB-3/TR-1, S-2).
 *
 * <p>Responsibility: the single seam {@code POST /ussd/gateway} calls before doing abusable work
 * (advancing a session, and — on the first hit of a fresh dialogue — auto-creating a T1 account by
 * MSISDN). The aggregator link is authenticated by a shared secret ({@code UssdGatewaySecretFilter}),
 * but a compromised/over-eager aggregator, or a legitimate one relaying a flood, could still drive the
 * unauthenticated, no-OTP account-creation + report-filing surface. This limiter caps that per number.</p>
 *
 * <p>Keys are always <b>hashed</b> MSISDN identifiers (a {@link CryptoPort#blindIndex(String)} of the
 * normalised number) supplied by the caller, so no raw phone surfaces in a key that could reach
 * logs/metrics (S-4, PDPA 2022/2023). MSISDN is PII (THREAT-MODEL A3).</p>
 *
 * <p>WHY a separate port from {@link AuthRateLimiter} (not a reused method): the USSD webhook has a
 * different abuse shape — a high-frequency keypress dialogue plus a distinct, tighter account-creation
 * trigger — and a different key (MSISDN, not an account/challenge). Keeping it a focused interface keeps
 * each limiter single-responsibility (SOLID) and lets the production Redis-backed adapter swap in behind
 * this contract exactly as for the auth limiter (KISS, ADR-0004). Like the auth limiter, the in-memory
 * default is single-instance; a Redis adapter is required for multi-instance correctness (TR-3).</p>
 */
public interface UssdGatewayRateLimiter {

    /**
     * Checks (and records) one inbound USSD keypress against the per-MSISDN session-turn cap.
     *
     * <p>Bounds the keypress rate from a single number so neither a flood of menu interactions nor a
     * burst of report filings can be driven through the webhook for one MSISDN.</p>
     *
     * @param msisdnHash the hashed (blind-indexed) caller MSISDN; never the raw number.
     * @return {@code true} if the turn is permitted; {@code false} once the per-MSISDN cap is exceeded.
     */
    boolean allowSessionTurn(String msisdnHash);

    /**
     * Checks (and records) the start of a <b>new dialogue</b> against the tighter per-MSISDN
     * new-session cap.
     *
     * <p>The first hit of a fresh dialogue is the moment the USSD flow auto-links/creates the MSISDN
     * account at T1 (no OTP — the SIM proves the number). This stricter cap throttles unauthenticated
     * account-provisioning/enumeration bursts independently of the keypress rate (wave2-review P2-1).</p>
     *
     * @param msisdnHash the hashed (blind-indexed) caller MSISDN; never the raw number.
     * @return {@code true} if a new dialogue may start; {@code false} once the per-MSISDN cap is exceeded.
     */
    boolean allowNewSession(String msisdnHash);
}
