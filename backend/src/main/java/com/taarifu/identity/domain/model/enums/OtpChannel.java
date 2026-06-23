package com.taarifu.identity.domain.model.enums;

/**
 * The delivery channel for an OTP challenge (AUTH-DESIGN §12, §13.1).
 *
 * <p>Responsibility: records how a one-time code was (or should be) delivered. SMS is the primary,
 * near-universal channel in the Tanzanian context (feature-phone inclusion, PRD §15); email is the
 * cheaper fallback when the citizen has a verified email (AUTH-DESIGN §15 cost note).</p>
 */
public enum OtpChannel {

    /** Delivered to the account phone via the {@code SmsGateway} port (default). */
    SMS,

    /** Delivered to the account email (cheaper fallback / email-verify flow). */
    EMAIL
}
