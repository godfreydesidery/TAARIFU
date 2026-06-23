package com.taarifu.common.security;

/**
 * Distinguishes the two JWT kinds Taarifu issues (ADR-0007, ARCHITECTURE.md §6.1).
 *
 * <p>Responsibility: carried as the {@code tokenType} claim and <b>validated on every use</b> so a
 * long-lived <i>refresh</i> token can never be presented as an <i>access</i> token (privilege/lifetime
 * confusion). The {@link JwtAuthenticationFilter} accepts only {@link #ACCESS}; the (later) refresh
 * endpoint accepts only {@link #REFRESH}.</p>
 */
public enum TokenType {

    /** Short-lived (~15 min) token authorising API calls. */
    ACCESS,

    /** Long-lived (~30 day), single-use, rotating token used only to mint new access tokens. */
    REFRESH
}
