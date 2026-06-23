package com.taarifu.ussd.domain.repository;

import com.taarifu.ussd.domain.model.UssdAlertSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@link UssdAlertSubscription} area-alert intents (ARCHITECTURE §3.3).
 *
 * <p>Responsibility: idempotent lookup of an account's live area-alert subscription so a repeated USSD
 * subscribe is a no-op rather than a duplicate. Soft-deleted (unsubscribed) rows are excluded by the
 * entity's {@code @SQLRestriction}.</p>
 */
public interface UssdAlertSubscriptionRepository extends JpaRepository<UssdAlertSubscription, Long> {

    /**
     * Finds an existing live area-alert subscription for idempotent subscribe.
     *
     * @param userPublicId the MSISDN-linked account public id.
     * @param wardId       the subscribed ward public id.
     * @return the existing live subscription, or empty.
     */
    Optional<UssdAlertSubscription> findByUserPublicIdAndWardId(UUID userPublicId, UUID wardId);
}
