package com.taarifu.identity.domain.model.enums;

/**
 * The reason an OTP challenge was issued (AUTH-DESIGN §3, §13.1).
 *
 * <p>Responsibility: ties a one-time code to the flow it authorises so a code minted for one purpose
 * cannot be replayed into another (e.g. a {@link #SIGNUP} code must never complete a {@link #LOGIN}).
 * The purpose is bound into the {@code otp_challenge} row and checked on verify.</p>
 */
public enum OtpPurpose {

    /** First-time phone signup → trust-tier T1 (one account per phone, D11/D15). */
    SIGNUP,

    /** Passwordless / recovery login for an existing active account. */
    LOGIN,

    /** Verify a secondary channel (e.g. email) during profile completion → may promote to T2. */
    VERIFY
}
