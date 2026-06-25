package com.taarifu.payments.api.event;

import com.taarifu.payments.domain.model.enums.MobileMoneyProvider;
import com.taarifu.payments.domain.model.enums.WalletOwnerKind;

import java.util.UUID;

/**
 * Published domain event: a mobile-money token top-up settled and the wallet was credited (ADR-0015,
 * ADR-0014 §4).
 *
 * <p>Responsibility: the stable, public {@code payments.api.event} contract carried over the transactional
 * outbox so async consumers (a purchase-receipt notification, analytics) can react without coupling to the
 * credit path. It is emitted in the same transaction as the SUCCEEDED transition + wallet credit (atomicity,
 * ADR-0014 §2).</p>
 *
 * <p><b>🔒 ids/codes/amounts ONLY — NO PII</b> (PRD §18, §12; ADR-0014 §1/§4): no MSISDN, no name, no
 * provider body. A consumer that needs more re-reads the aggregate by {@link #topUpId} via the owner's
 * {@code *QueryApi}. The {@code ModuleBoundaryTest.noEntityInPublishedApiOrEvents} rule keeps this package
 * entity-free.</p>
 *
 * @param topUpId         the top-up's public id (the outbox {@code aggregateId}).
 * @param buyerId         the buyer's account public id (opaque UUID).
 * @param walletOwnerType which wallet was credited (USER/ORGANIZATION).
 * @param tokenAmount     the number of convenience tokens credited.
 * @param provider        the mobile-money rail that settled.
 */
public record TopUpSucceeded(
        UUID topUpId,
        UUID buyerId,
        WalletOwnerKind walletOwnerType,
        long tokenAmount,
        MobileMoneyProvider provider
) {

    /** The outbox taxonomy key handlers register on (ADR-0014 §4). */
    public static final String EVENT_TYPE = "TOP_UP_SUCCEEDED";

    /** The outbox aggregate-type tag for this event's producing aggregate (ADR-0014 §1). */
    public static final String AGGREGATE_TYPE = "TOP_UP";
}
