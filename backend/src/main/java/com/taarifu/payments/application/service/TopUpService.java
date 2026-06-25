package com.taarifu.payments.application.service;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.payments.domain.model.TopUp;
import com.taarifu.payments.domain.model.enums.MobileMoneyProvider;
import com.taarifu.payments.domain.model.enums.WalletOwnerKind;
import com.taarifu.payments.domain.port.MobileMoneyGateway;
import com.taarifu.payments.domain.repository.TopUpRepository;
import com.taarifu.payments.infrastructure.config.PaymentsGatewayProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Application service that owns the <b>buyer-facing</b> top-up lifecycle: initiate a collection and read a
 * top-up's status (ADR-0015; PRD §23.4/§23.5/§23.6).
 *
 * <p>Responsibility: prices the requested token amount, creates the {@link TopUp} row <b>idempotently</b>,
 * asks the {@link MobileMoneyGateway} to push the collection, and records the provider reference. The
 * actual settlement + wallet credit happens later, on the verified webhook, in
 * {@link ReconciliationService}.</p>
 *
 * <p><b>Idempotency (PRD §23.5):</b> {@link #initiate} dedups on the caller's idempotency key — a replayed
 * initiate returns the existing top-up and never triggers a second collection. A race on the unique key is
 * caught and resolved to the one row.</p>
 *
 * <p><b>🔒 Fence (D18):</b> nothing here reads or gates on a token balance; this is pure money-movement.
 * The credit it ultimately leads to is a convenience top-up only (never democratic weight).</p>
 *
 * <p><b>Privacy (PRD §18):</b> the payer MSISDN is passed to the gateway adapter but never persisted on the
 * {@link TopUp}, never logged in full, and never placed on an event payload.</p>
 */
@Service
@Transactional
public class TopUpService {

    private final TopUpRepository topUps;
    private final MobileMoneyGateway gateway;
    private final PaymentsGatewayProperties config;

    /**
     * @param topUps  top-up persistence.
     * @param gateway the active mobile-money rail (real adapter or the logging stub).
     * @param config  bound gateway settings (pricing, currency).
     */
    public TopUpService(TopUpRepository topUps, MobileMoneyGateway gateway,
                        PaymentsGatewayProperties config) {
        this.topUps = topUps;
        this.gateway = gateway;
        this.config = config;
    }

    /**
     * Initiates a mobile-money token top-up for the buyer, idempotently.
     *
     * @param buyerId        the authenticated buyer's account public id.
     * @param ownerType      which wallet to credit (USER for a citizen).
     * @param provider       the mobile-money rail.
     * @param tokenAmount    tokens to purchase (must be positive).
     * @param payerMsisdn    the payer mobile-money number (E.164); used only to push the collection — never
     *                       persisted/logged in full.
     * @param idempotencyKey the caller's idempotency key (a replay returns the original top-up).
     * @return the (existing or newly-initiated) top-up.
     * @throws ApiException {@link ErrorCode#BAD_REQUEST} if {@code tokenAmount <= 0};
     *                      {@link ErrorCode#SERVICE_UNAVAILABLE} if the rail does not accept the collection.
     */
    public TopUp initiate(UUID buyerId, WalletOwnerKind ownerType, MobileMoneyProvider provider,
                          long tokenAmount, String payerMsisdn, String idempotencyKey) {
        if (tokenAmount <= 0) {
            throw new ApiException(ErrorCode.BAD_REQUEST);
        }
        // Idempotent initiate: a replay returns the original row, never a second collection (PRD §23.5).
        var existing = topUps.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        long amountMinor = priceMinor(tokenAmount);
        TopUp topUp = new TopUp(buyerId, ownerType, provider, amountMinor, tokenAmount,
                config.currency(), idempotencyKey);

        TopUp saved;
        try {
            saved = topUps.save(topUp);
        } catch (DataIntegrityViolationException raced) {
            // A concurrent identical initiate won the unique idempotency key; return that one (no double-push).
            return topUps.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> raced);
        }

        // Push the collection (STK push). The MSISDN is handed to the adapter only; never stored on the row.
        MobileMoneyGateway.InitiationResult result = gateway.initiateCollection(
                new MobileMoneyGateway.CollectionRequest(amountMinor, config.currency(), payerMsisdn,
                        idempotencyKey));
        if (!result.accepted()) {
            // Degrade-don't-crash (EI-20): mark FAILED and surface a typed unavailable; the free path stands.
            saved.markFailed("RAIL_NOT_ACCEPTED");
            topUps.save(saved);
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        saved.markPending(result.providerRef());
        return topUps.save(saved);
    }

    /**
     * Reads a top-up for its owner (status polling).
     *
     * <p><b>Own-only (PDPA, PRD §18):</b> the buyer id is matched against the authenticated caller so one
     * citizen cannot read another's purchase.</p>
     *
     * @param publicId the top-up public id.
     * @param buyerId  the authenticated caller's public id (must own the top-up).
     * @return the top-up.
     * @throws ResourceNotFoundException if it does not exist or is not owned by the caller (no existence
     *                                   oracle for someone else's top-up).
     */
    @Transactional(readOnly = true)
    public TopUp getOwnTopUp(UUID publicId, UUID buyerId) {
        return topUps.findByPublicId(publicId)
                .filter(t -> t.getBuyerId().equals(buyerId))
                .orElseThrow(() -> new ResourceNotFoundException("payments.topUp.notFound", publicId));
    }

    /**
     * Prices a token quantity to minor currency units.
     *
     * <p>WHY a simple unit price (not a catalogue yet): MVP-of-Phase-2 prices ad-hoc by a configured
     * minor-units-per-token; a {@code TokenPackage} catalogue (tokens module) can override this later. A
     * zero configured price (the stub default) yields a zero amount — harmless because the stub never
     * settles, so no free tokens are ever credited.</p>
     */
    private long priceMinor(long tokenAmount) {
        return Math.multiplyExact(config.priceMinorPerToken(), tokenAmount);
    }
}
