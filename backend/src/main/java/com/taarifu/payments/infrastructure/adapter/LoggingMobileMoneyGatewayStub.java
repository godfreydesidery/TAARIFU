package com.taarifu.payments.infrastructure.adapter;

import com.taarifu.payments.domain.model.enums.MobileMoneyProvider;
import com.taarifu.payments.domain.port.MobileMoneyGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Default {@link MobileMoneyGateway} — the <b>prod-bootable, integrity-safe no-op</b> selected when no real
 * rail is configured (ADR-0015; ARCHITECTURE.md §7). Lets every environment boot and tests run with
 * <b>zero external calls</b>.
 *
 * <p>Responsibility: satisfies the port so the module wires in {@code dev}/{@code test} and a no-profile
 * production context with no aggregator configured. It <b>never moves money</b> and
 * <b>never settles</b>: {@link #verifySettled(String)} always returns {@code false}, so no top-up is ever
 * credited by the stub. This is deliberate — with no real rail configured the integrity-safe outcome is
 * "purchase unavailable, free path continues" (PRD §23.5), never an accidental credit.</p>
 *
 * <p><b>Privacy (PRD §18):</b> the payer MSISDN and amount are never logged in a way that builds a payment
 * profile — only a redacted, presence-only line.</p>
 *
 * <p><b>WHY {@code matchIfMissing = true}</b> (the {@code SmsGateway} precedent): with no
 * {@code taarifu.payments.gateway.provider} set, this is the one active bean, so a no-profile prod context
 * boots safely degrading to no-op; a real adapter is selected only by an explicit provider value, and the
 * two are mutually exclusive on the same property so <b>exactly one gateway bean exists</b> in every
 * environment.</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.payments.gateway.provider", havingValue = "logging",
        matchIfMissing = true)
public class LoggingMobileMoneyGatewayStub implements MobileMoneyGateway {

    private static final Logger log = LoggerFactory.getLogger(LoggingMobileMoneyGatewayStub.class);

    /** Identifies the stub as the M-Pesa-shaped sandbox rail for routing; settles nothing. */
    @Override
    public MobileMoneyProvider provider() {
        return MobileMoneyProvider.MPESA;
    }

    /**
     * Pretends to accept a collection and returns a synthetic reference; no real push is sent.
     *
     * @param request the initiation request (MSISDN never logged in full — PRD §18).
     * @return an accepted result with a synthetic provider reference.
     */
    @Override
    public InitiationResult initiateCollection(CollectionRequest request) {
        log.info("StubMobileMoneyGateway: collection ignored (no real rail configured); idemKeyPresent={}",
                request != null && request.idempotencyKey() != null);
        return new InitiationResult("STUB-" + UUID.randomUUID(), true);
    }

    /**
     * The stub accepts no real signatures — it always reports unverified so no callback can drive a credit.
     *
     * @param rawBody         ignored.
     * @param signatureHeader ignored.
     * @return {@code false} always (fail-closed; the stub cannot credit).
     */
    @Override
    public boolean verifyCallbackSignature(byte[] rawBody, String signatureHeader) {
        return false;
    }

    /**
     * Parses nothing meaningful — the stub never settles.
     *
     * @param rawBody ignored.
     * @return a not-settled result with no reference.
     */
    @Override
    public CallbackResult parseCallback(byte[] rawBody) {
        return new CallbackResult(null, false);
    }

    /**
     * Always reports not-settled so the stub can never credit a wallet.
     *
     * @param providerRef ignored.
     * @return {@code false} always.
     */
    @Override
    public boolean verifySettled(String providerRef) {
        return false;
    }
}
