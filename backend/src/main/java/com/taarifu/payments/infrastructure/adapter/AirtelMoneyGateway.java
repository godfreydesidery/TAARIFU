package com.taarifu.payments.infrastructure.adapter;

import com.taarifu.payments.domain.model.enums.MobileMoneyProvider;
import com.taarifu.payments.domain.port.MobileMoneyGateway;
import com.taarifu.payments.infrastructure.config.PaymentsGatewayProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Production {@link MobileMoneyGateway} for <b>Airtel Money</b> (ADR-0015; PRD §23.6, §21 EI-20). Selected
 * by {@code taarifu.payments.gateway.provider=airtelmoney}; endpoint + HMAC secret from the environment.
 *
 * <p>Responsibility: a thin per-rail binding over {@link AbstractHmacMobileMoneyGateway}. Airtel Money
 * specifics, if they diverge from the shared HTTPS/HMAC shape, are confined here — never the domain (DI1).</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.payments.gateway.provider", havingValue = "airtelmoney")
public class AirtelMoneyGateway extends AbstractHmacMobileMoneyGateway {

    /**
     * @param config bound gateway settings (base URL + HMAC secret from env; fail-fast if blank).
     */
    public AirtelMoneyGateway(PaymentsGatewayProperties config) {
        super(config);
    }

    /** @return {@link MobileMoneyProvider#AIRTELMONEY}. */
    @Override
    public MobileMoneyProvider provider() {
        return MobileMoneyProvider.AIRTELMONEY;
    }
}
