package com.taarifu.common.security;

/**
 * Distinguishes the two JWT kinds Taarifu issues (ADR-0007, ARCHITECTURE.md §6.1).
 *
 * <p>Responsibility: carried as the {@code tokenType} claim and <b>validated on every use</b> so a
 * long-lived <i>refresh</i> token can never be presented as an <i>access</i> token (privilege/lifetime
 * confusion). The {@link JwtAuthenticationFilter} accepts only {@link #ACCESS}; the refresh endpoint
 * accepts only {@link #REFRESH}; the staff second-factor endpoint accepts only {@link #MFA_CHALLENGE}.</p>
 */
public enum TokenType {

    /** Short-lived (~15 min) token authorising API calls. */
    ACCESS,

    /** Long-lived (~30 day), single-use, rotating token used only to mint new access tokens. */
    REFRESH,

    /**
     * Very short-lived (~5 min) intermediate token issued after the first login factor (password/OTP)
     * succeeds but a staff TOTP second factor is still outstanding (N-4). It carries <b>no roles and no
     * trust tier</b> and authorises <b>only</b> the {@code POST /auth/login/totp} step — it can never
     * be presented as an {@link #ACCESS} token. Exchanging it for a real pair requires a valid TOTP.
     */
    MFA_CHALLENGE
}
