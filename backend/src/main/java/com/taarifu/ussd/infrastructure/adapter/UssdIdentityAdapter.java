package com.taarifu.ussd.infrastructure.adapter;

import com.taarifu.identity.api.AccountProvisioningApi;
import com.taarifu.ussd.application.port.UssdIdentityPort;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Production {@link UssdIdentityPort} adapter — delegates to identity's published
 * {@link AccountProvisioningApi} (the sanctioned synchronous {@code ussd → identity} contract, ADR-0013 §4d).
 *
 * <p>Responsibility: bind the USSD module's consumer-owned identity seam to identity's real
 * account-provisioning command port, so a feature-phone dialogue resolves/creates the reporter's T1 account
 * (and reads its registered area) against the real {@code app_user}/{@code profile} rows — no in-process stub
 * id any more. This replaces the prior deterministic stub; the {@code // TODO(wiring)} is now closed.</p>
 *
 * <p>This adapter holds <b>no logic</b>: it is a one-line delegation to the published port, which is exactly
 * the ADR-0013 pattern (the consumer's port, the producer's {@code api} implementation, an adapter that wires
 * them by {@code UUID} only — never an identity entity). The MSISDN is PII and is never logged here (S-4);
 * identity stores it encrypted/uniquely indexed. No token is read on this path (the civic-integrity fence,
 * D18).</p>
 */
@Component
public class UssdIdentityAdapter implements UssdIdentityPort {

    private final AccountProvisioningApi accountProvisioning;

    /**
     * @param accountProvisioning identity's published account-provisioning command port (ensure-by-MSISDN +
     *                            registered-ward read).
     */
    public UssdIdentityAdapter(AccountProvisioningApi accountProvisioning) {
        this.accountProvisioning = accountProvisioning;
    }

    /** {@inheritDoc} */
    @Override
    public UUID linkOrCreateByMsisdn(String msisdn) {
        // Idempotent at the identity side: a known MSISDN returns its existing account (one-per-phone, D11/D15).
        return accountProvisioning.ensureAccountByMsisdn(msisdn);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<UUID> registeredWardId(UUID userPublicId) {
        return accountProvisioning.registeredWardId(userPublicId);
    }
}
