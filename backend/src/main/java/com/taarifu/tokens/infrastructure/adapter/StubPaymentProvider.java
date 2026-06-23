package com.taarifu.tokens.infrastructure.adapter;

import com.taarifu.tokens.domain.model.enums.PaymentProviderType;
import com.taarifu.tokens.domain.port.PaymentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The no-op sandbox {@link PaymentProvider} that lets the system boot and tests run with <b>zero external
 * calls</b> — the MVP default, since purchase is Phase 2 (PRD §23.6; ARCHITECTURE.md §7).
 *
 * <p>Responsibility: satisfies the port so wiring is complete in MVP. It <b>never</b> moves money and
 * <b>never</b> auto-credits tokens: {@link #verifySettled(String)} always returns {@code false}, so no
 * {@code PURCHASE} ledger entry is ever posted by the stub. This is deliberate — the integrity stance is
 * that tokens are <i>earned/granted</i> in MVP, not bought, and the free path always suffices (PRD §23.5).</p>
 *
 * <p>WHY a logging stub rather than throwing: a thrown adapter would break context startup and any wiring
 * test; returning a benign "accepted but never settles" keeps the seam present and harmless until a real
 * mobile-money adapter is configured in Phase 2.</p>
 */
@Component
public class StubPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(StubPaymentProvider.class);

    /** Identifies the stub as the M-Pesa-shaped sandbox rail for routing; settles nothing. */
    @Override
    public PaymentProviderType providerType() {
        return PaymentProviderType.MPESA;
    }

    /**
     * Pretends to accept a collection and returns a synthetic reference; no real push is sent.
     *
     * @param request the initiation request (MSISDN is never logged in full — PRD §18).
     * @return an accepted result with a synthetic provider reference.
     */
    @Override
    public InitiationResult initiateCollection(CollectionRequest request) {
        // Never log the full MSISDN or amount in a way that builds a payment profile (PII discipline).
        log.info("StubPaymentProvider: collection initiation ignored (purchase is Phase 2); idemKey present={}",
                request != null && request.idempotencyKey() != null);
        String syntheticRef = "STUB-" + UUID.randomUUID();
        return new InitiationResult(syntheticRef, true);
    }

    /**
     * Always reports not-settled so the stub can never credit tokens.
     *
     * @param providerRef ignored.
     * @return {@code false} always (no purchase credit in MVP).
     */
    @Override
    public boolean verifySettled(String providerRef) {
        return false;
    }
}
