package com.taarifu.common.security;

/**
 * Anti-automation primitive for the auth surface — OTP send/verify limits and login lockout/backoff
 * (AUTH-DESIGN §9, ADR-0011 §6, S-2).
 *
 * <p>Responsibility: the single seam every auth flow calls before doing expensive/abusable work. Keys
 * are always <b>hashed</b> identifiers (account/phone/IP hash) so no raw phone or IP surfaces in a key
 * that could reach logs/metrics (S-4, PDPA). The production target is a Redis-backed token bucket /
 * sliding window (ephemeral, auto-expiring); the contract is provider-agnostic. On a counter-store
 * outage the implementation <b>fails closed</b> for the auth surface (security over availability,
 * AUTH-DESIGN §15).</p>
 *
 * <p>WHY a port (not Redis calls inline): Redis is not yet wired in this repo; the default adapter is an
 * in-memory window so the auth flows are buildable and unit-testable now (no infra), and the Redis
 * adapter swaps in behind this interface later (KISS, ADR-0004).</p>
 */
public interface AuthRateLimiter {

    /**
     * Checks (and records) an OTP <b>send</b> against the per-recipient rate caps (e.g. 1/60s).
     *
     * @param recipientHash the hashed phone/email the OTP would go to.
     * @return {@code true} if the send is permitted; {@code false} if a cap is exceeded (→ 429).
     */
    boolean allowOtpSend(String recipientHash);

    /**
     * Records a failed OTP verify attempt against a challenge's per-challenge cap window.
     *
     * @param challengeKey the challenge public id (string) the attempt targets.
     * @return {@code true} if further attempts are still permitted; {@code false} once the cap is hit.
     */
    boolean allowOtpVerifyAttempt(String challengeKey);

    /**
     * Pre-check before a login credential check: is the account currently locked out, and how long
     * must the caller wait (backoff)? Implementations enforce exponential backoff with jitter after a
     * few failures and a hard lock after the threshold.
     *
     * @param accountHash the hashed account identifier (phone/email).
     * @return {@code true} if a login attempt is permitted now; {@code false} if locked/backing off (→ 429).
     */
    boolean allowLoginAttempt(String accountHash);

    /**
     * Records a failed login, advancing the backoff/lockout state for the account.
     *
     * @param accountHash the hashed account identifier.
     */
    void recordLoginFailure(String accountHash);

    /**
     * Clears the failure/lockout state after a successful login.
     *
     * @param accountHash the hashed account identifier.
     */
    void resetLogin(String accountHash);
}
