package com.taarifu.common.security;

import org.springframework.security.core.AuthenticationException;

/**
 * Raised when a JWT fails signature, expiry, or {@code tokenType} validation
 * (ADR-0007, ARCHITECTURE.md §6.1).
 *
 * <p>Responsibility: extends Spring Security's {@link AuthenticationException} so the
 * {@link com.taarifu.common.error.GlobalExceptionHandler} maps it to a generic
 * {@code UNAUTHENTICATED} envelope — the response never reveals <i>why</i> a token was rejected
 * (PRD §18, anti-oracle).</p>
 */
public class JwtVerificationException extends AuthenticationException {

    /** @param message internal diagnostic (logged only; never serialised to the client). */
    public JwtVerificationException(String message) {
        super(message);
    }

    /**
     * @param message internal diagnostic.
     * @param cause   underlying parse/crypto cause.
     */
    public JwtVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
