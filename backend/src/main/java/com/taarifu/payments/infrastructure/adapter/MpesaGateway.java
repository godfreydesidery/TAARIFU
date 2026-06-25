package com.taarifu.payments.infrastructure.adapter;

import com.taarifu.payments.domain.model.enums.MobileMoneyProvider;
import com.taarifu.payments.domain.port.MobileMoneyGateway;
import com.taarifu.payments.infrastructure.config.PaymentsGatewayProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Production {@link MobileMoneyGateway} for <b>Vodacom M-Pesa</b> (ADR-0015; PRD §23.6, §21 EI-20).
 * Selected by {@code taarifu.payments.gateway.provider=mpesa}; endpoint + HMAC secret from the environment.
 *
 * <p>Responsibility: a thin per-rail binding over {@link AbstractHmacMobileMoneyGateway} (which owns the
 * HTTPS submit, the fail-closed HMAC callback verification, and the out-of-band settlement check). M-Pesa
 * STK-push specifics, if they ever diverge from the shared shape, are confined to this class — never the
 * domain (DI1).</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.payments.gateway.provider", havingValue = "mpesa")
public class MpesaGateway extends AbstractHmacMobileMoneyGateway {

    /**
     * @param config bound gateway settings (base URL + HMAC secret from env; fail-fast if blank).
     */
    public MpesaGateway(PaymentsGatewayProperties config) {
        super(config);
    }

    /** @return {@link MobileMoneyProvider#MPESA}. */
    @Override
    public MobileMoneyProvider provider() {
        return MobileMoneyProvider.MPESA;
    }
}
