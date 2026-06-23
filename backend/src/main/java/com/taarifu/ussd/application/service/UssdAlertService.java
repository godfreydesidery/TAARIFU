package com.taarifu.ussd.application.service;

import com.taarifu.ussd.domain.model.UssdAlertSubscription;
import com.taarifu.ussd.domain.repository.UssdAlertSubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Records a USSD citizen's my-area alert subscription intent (PRD §14 menu item 3, EI-3/EI-4).
 *
 * <p>Responsibility: idempotently capture "this MSISDN-linked account wants SMS alerts for this ward" so a
 * feature-phone citizen, who has no app/feed, can still be reached by area announcements. The intent is
 * stored locally ({@link UssdAlertSubscription}); forwarding it to communications as a real
 * follow/notification preference is deferred until a published communications command port exists
 * ({@code // TODO(wiring)}).</p>
 */
@Service
public class UssdAlertService {

    private final UssdAlertSubscriptionRepository subscriptions;

    /**
     * @param subscriptions area-alert intent persistence.
     */
    public UssdAlertService(UssdAlertSubscriptionRepository subscriptions) {
        this.subscriptions = subscriptions;
    }

    /**
     * Subscribes the account's registered area to SMS alerts, idempotently (a repeat is a no-op).
     *
     * @param userPublicId the MSISDN-linked account public id.
     * @param wardId       the registered ward public id to subscribe.
     * @return {@code true} if a new subscription was created, {@code false} if one already existed.
     */
    @Transactional
    public boolean subscribeArea(UUID userPublicId, UUID wardId) {
        if (subscriptions.findByUserPublicIdAndWardId(userPublicId, wardId).isPresent()) {
            return false;
        }
        subscriptions.save(UssdAlertSubscription.of(userPublicId, wardId));
        // TODO(wiring): forward to communications to register the real area Subscription / SMS-channel
        // NotificationPreference, then markForwarded(). Requires a published com.taarifu.communications.api
        // command port (this module must not write communications' tables — ADR-0013). See CENTRAL
        // INTEGRATION NEEDS.
        return true;
    }
}
