package com.taarifu.ussd.application.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Consumer-owned port describing what the USSD flows need from <b>identity</b>: link or create a T1 account
 * by MSISDN, and read that account's registered ("use my area") ward (PRD §14, EI-4, US-0.1; ADR-0013).
 *
 * <p>Responsibility: capture the identity contract this module depends on, decoupled from identity's
 * internals. A feature-phone caller is authenticated by ownership of the SIM, so signup-by-MSISDN creates a
 * <b>T1</b> account directly (no OTP round-trip) or returns the existing one — "one person, one account"
 * still holds via identity's unique-phone guard (D11/D15).</p>
 *
 * <p>WHY this interface lives in the <b>consumer</b> module (not imported from identity): identity does not
 * yet publish a {@code com.taarifu.identity.api} command port for MSISDN signup — only an OTP-driven signup
 * service exists. Per the isolation rule this module must not import identity's {@code application}/
 * {@code domain}. So we define the seam here and bind it to a dev stub now; the production adapter delegates
 * to identity's published port once it lands ({@code // TODO(wiring)}; see CENTRAL INTEGRATION NEEDS). The
 * port speaks only public {@code UUID}s — never an identity entity (ARCHITECTURE §3.2).</p>
 */
public interface UssdIdentityPort {

    /**
     * Links the MSISDN to an existing account or creates a new <b>T1</b> account for it (EI-4, US-0.1).
     *
     * <p>WHY no OTP: on USSD the network already proves SIM ownership, so the account is created/linked
     * directly at T1 (the same tier OTP signup yields). The one-account-per-phone invariant is enforced by
     * identity (D11/D15): an existing MSISDN returns its account, never a duplicate.</p>
     *
     * @param msisdn the caller's E.164 phone (PII — identity stores/encrypts it; never logged raw here).
     * @return the account's immutable public id.
     */
    UUID linkOrCreateByMsisdn(String msisdn);

    /**
     * Reads the account's registered ("home") ward, used for the "use my registered area" shortcut so a
     * feature-phone citizen need not drill the geography tree on every report (PRD §14, UC-D02).
     *
     * @param userPublicId the MSISDN-linked account public id.
     * @return the registered ward public id, or empty if the account has none set yet.
     */
    Optional<UUID> registeredWardId(UUID userPublicId);
}
