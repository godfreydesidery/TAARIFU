package com.taarifu.payments.infrastructure.adapter;

import com.taarifu.payments.domain.model.enums.MobileMoneyProvider;
import com.taarifu.payments.domain.port.MobileMoneyGateway;
import com.taarifu.payments.infrastructure.config.PaymentsGatewayProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Production {@link MobileMoneyGateway} for <b>Tigo Pesa (Mixx by Yas)</b> (ADR-0015; PRD §23.6, §21 EI-20).
 * Selected by {@code taarifu.payments.gateway.provider=tigopesa}; endpoint + HMAC secret from the
 * environment.
 *
 * <p>Responsibility: a thin per-rail binding over {@link AbstractHmacMobileMoneyGateway}. Tigo Pesa
 * specifics, if they diverge from the shared HTTPS/HMAC shape, are confined here — never the domain (DI1).</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.payments.gateway.provider", havingValue = "tigopesa")
public class TigoPesaGateway extends AbstractHmacMobileMoneyGateway {

    /**
     * @param config bound gateway settings (base URL + HMAC secret from env; fail-fast if blank).
     */
    public TigoPesaGateway(PaymentsGatewayProperties config) {
        super(config);
    }

    /** @return {@link MobileMoneyProvider#TIGOPESA}. */
    @Override
    public MobileMoneyProvider provider() {
        return MobileMoneyProvider.TIGOPESA;
    }
}
