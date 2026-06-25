package com.taarifu.ussd.infrastructure.adapter;

import com.taarifu.communications.api.AreaSubscriptionApi;
import com.taarifu.ussd.application.port.UssdSubscriptionPort;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Production {@link UssdSubscriptionPort} adapter — delegates to communications' published
 * {@link AreaSubscriptionApi} (the sanctioned synchronous {@code ussd → communications} contract,
 * ADR-0013 §1; ADR-0019; A3).
 *
 * <p>Responsibility: bind the USSD module's consumer-owned subscription seam to communications' real area-follow
 * command port, so a feature-phone citizen's my-area-alert intent can register the real {@code AREA}
 * {@code Subscription} that drives announcement fan-out — without this module writing communications'
 * {@code subscription} table (ADR-0013).</p>
 *
 * <p>This adapter holds <b>no logic</b>: it is a one-line delegation to the published port, the ADR-0013 pattern
 * (consumer port, producer {@code api} impl, an adapter wiring them by {@code UUID} only — never a
 * communications entity). No token is read on this path (the civic-integrity fence, D18). See the grain note on
 * {@link UssdSubscriptionPort} (CENTRAL INTEGRATION NEED — identity account→profile resolution).</p>
 */
@Component
public class SubscriptionUssdAdapter implements UssdSubscriptionPort {

    private final AreaSubscriptionApi areaSubscriptionApi;

    /**
     * @param areaSubscriptionApi communications' published area-subscription command port.
     */
    public SubscriptionUssdAdapter(AreaSubscriptionApi areaSubscriptionApi) {
        this.areaSubscriptionApi = areaSubscriptionApi;
    }

    /** {@inheritDoc} */
    @Override
    public UUID subscribeArea(UUID subscriberProfilePublicId, UUID wardPublicId) {
        return areaSubscriptionApi.subscribeArea(subscriberProfilePublicId, wardPublicId);
    }
}
