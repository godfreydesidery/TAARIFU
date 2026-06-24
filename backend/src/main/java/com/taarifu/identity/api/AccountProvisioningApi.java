package com.taarifu.identity.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The identity module's <b>public, in-process command port</b> for provisioning (or resolving) a citizen
 * account directly by MSISDN — the channel-agnostic "ensure an account exists for this phone" seam used by
 * the feature-phone (USSD) entry point (PRD §14, EI-4, US-0.1; ADR-0013 §1, §4d).
 *
 * <p>Responsibility: expose the single mutating operation "give me the account for this E.164 phone, creating
 * a T1 one if none exists" plus the read needed to honour the "use my registered area" shortcut, as a
 * published contract a sibling channel calls synchronously ({@code ussd → identity}) <b>without</b> importing
 * identity's {@code domain}/{@code application} (ARCHITECTURE §3.2). Identity stays the single writer of
 * {@code app_user}/{@code profile}; the caller sees only public {@code UUID}s, never an identity entity.</p>
 *
 * <p>WHY this is a sanctioned synchronous {@code ussd → identity} edge (ADR-0013 §4's fourth revisit-trigger
 * — "a synchronous cross-feature command that is not metering"): a USSD dialogue must resolve the reporter's
 * account <b>within the same request</b> before it can file/track on the next keypress — it cannot wait for
 * an async event. The edge introduces <b>no cycle</b> (identity never calls ussd) and reuses the same account
 * invariants signup enforces. Recorded as a CENTRAL INTEGRATION NEED for the architect to ratify.</p>
 *
 * <p>WHY no OTP on this path: over USSD the mobile network already proves SIM ownership, so the account is
 * created/linked directly at <b>T1</b> (the same tier OTP signup yields — see {@code SignupService}). The
 * one-account-per-phone invariant (D11/D15) is preserved by construction: a known MSISDN returns its existing
 * account, never a second one. This port carries <b>no token/balance</b> input or output — the USSD file path
 * it feeds is a civic-core action gated on tier only (the civic-integrity fence, D18, §23.5).</p>
 */
public interface AccountProvisioningApi {

    /**
     * Idempotently ensures an account exists for the given phone and returns its immutable public id
     * (EI-4, US-0.1).
     *
     * <p><b>Idempotent:</b> if the MSISDN is already known, the existing account's public id is returned
     * unchanged (no second account, no state change — D11/D15). If it is unknown, a new account is created
     * <b>ACTIVE at T1</b> with a phone-verified person {@code Profile} and the base {@code CITIZEN} role, in
     * one transaction, exactly as a phone+OTP signup would yield (minus the OTP round-trip, which the network
     * substitutes for). The tier is computed server-side, never asserted by the caller (MF-2).</p>
     *
     * @param e164 the caller's phone in E.164 form (PII — identity stores it encrypted/uniquely indexed and
     *             never logs it raw; the caller must not log it either, S-4).
     * @return the account's immutable public id (the JWT-subject grain) — the reporter handle the USSD flows
     *         carry forward.
     * @throws com.taarifu.common.error.ApiException {@code BAD_REQUEST} if {@code e164} is blank.
     */
    UUID ensureAccountByMsisdn(String e164);

    /**
     * Reads the account's registered ("home") ward — its single <b>primary</b> {@code ProfileLocation}'s ward
     * (D12) — backing the USSD "use my registered area" shortcut so a feature-phone citizen need not drill the
     * geography tree on every report (PRD §14, UC-D02).
     *
     * <p>Returns the <b>primary</b> location's ward (the default report-area context), not the
     * voter-ID-authoritative {@code isElectoral} ward — filing a report is a residence/locality action, not a
     * binding electoral one, so the default-context pin is the correct source (D12 vs D13). Deny-by-default at
     * every missing link: no profile, or no primary pin, yields empty.</p>
     *
     * @param accountPublicId the account's public id (as returned by {@link #ensureAccountByMsisdn(String)}).
     * @return the registered (primary) ward's public id, or empty if the account has none set yet.
     */
    Optional<UUID> registeredWardId(UUID accountPublicId);
}
