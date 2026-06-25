package com.taarifu.ussd.application.service;

import com.taarifu.ussd.application.port.UssdSubscriptionPort;
import com.taarifu.ussd.domain.model.UssdAlertSubscription;
import com.taarifu.ussd.domain.repository.UssdAlertSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Records a USSD citizen's my-area alert subscription intent and (when enabled) forwards it to communications
 * as a real area follow (PRD §14 menu item 3, EI-3/EI-4; A3; ADR-0019).
 *
 * <p>Responsibility: idempotently capture "this MSISDN-linked account wants SMS alerts for this ward" so a
 * feature-phone citizen, who has no app/feed, can still be reached by area announcements. The intent is always
 * stored locally ({@link UssdAlertSubscription}) — the durable, safe record. When forwarding is enabled it is
 * then registered in communications through the consumer-owned {@link UssdSubscriptionPort} (bound to
 * communications' published {@code AreaSubscriptionApi}, ADR-0013) and the row is marked
 * {@link UssdAlertSubscription#markForwarded() forwarded}.</p>
 *
 * <p><b>⚠ WHY forwarding is config-gated (default OFF) — the account↔profile grain (ADR-0019 §1b; CENTRAL
 * INTEGRATION NEED):</b> a communications {@code AREA} follow is keyed by the subscriber's <b>profile</b> public
 * id (the grain the announcement fan-out resolves an MSISDN from), but a USSD dialogue carries only the
 * MSISDN-linked <b>account</b> public id. Forwarding the account id would register a follow at the wrong grain
 * that would <b>silently never deliver</b> — strictly worse than the safe unforwarded local intent. Resolving
 * account→profile is identity's concern and is out of this increment's scope, so the live forward is gated
 * behind {@code taarifu.ussd.alerts.forward} (default {@code false}). The port + adapter are the ready,
 * tested seam; flipping the flag (and passing a true profile id once identity exposes the resolution) is the
 * one-line completion. The local intent path is unchanged and always runs. <b>Fence (D18):</b> no token
 * collaborator on this path.</p>
 */
@Service
public class UssdAlertService {

    private static final Logger log = LoggerFactory.getLogger(UssdAlertService.class);

    private final UssdAlertSubscriptionRepository subscriptions;
    private final UssdSubscriptionPort subscriptionPort;

    /**
     * Whether to forward the captured intent to communications' published area-subscription port. Default
     * {@code false} until identity exposes account→profile resolution so the follow is keyed at the correct
     * (profile) grain (see the type Javadoc; ADR-0019 §1b CENTRAL NEED).
     */
    private final boolean forwardEnabled;

    /**
     * @param subscriptions    area-alert intent persistence (the durable local record — always written).
     * @param subscriptionPort consumer-owned communications seam (bound to {@code AreaSubscriptionApi}); used
     *                         only when forwarding is enabled.
     * @param forwardEnabled   {@code taarifu.ussd.alerts.forward} — gates the live forward call (default OFF;
     *                         see the type Javadoc on the grain CENTRAL NEED).
     */
    public UssdAlertService(UssdAlertSubscriptionRepository subscriptions,
                            UssdSubscriptionPort subscriptionPort,
                            @Value("${taarifu.ussd.alerts.forward:false}") boolean forwardEnabled) {
        this.subscriptions = subscriptions;
        this.subscriptionPort = subscriptionPort;
        this.forwardEnabled = forwardEnabled;
    }

    /**
     * Subscribes the account's registered area to SMS alerts, idempotently (a repeat is a no-op), and — when
     * forwarding is enabled — registers the real area follow in communications and marks the row forwarded.
     *
     * @param subscriberPublicId the subscriber's public id (the MSISDN-linked account id today; a profile id
     *                           once the grain CENTRAL NEED lands — see the type Javadoc).
     * @param wardId             the registered ward public id to subscribe.
     * @return {@code true} if a new subscription was created, {@code false} if one already existed.
     */
    @Transactional
    public boolean subscribeArea(UUID subscriberPublicId, UUID wardId) {
        if (subscriptions.findByUserPublicIdAndWardId(subscriberPublicId, wardId).isPresent()) {
            // Idempotent: the area is already subscribed (and, if forwarding was enabled, already forwarded).
            return false;
        }
        UssdAlertSubscription intent = subscriptions.save(
                UssdAlertSubscription.of(subscriberPublicId, wardId));
        if (forwardEnabled) {
            // Register the real AREA follow via the published port (ADR-0013). Fail-soft: a forwarding hiccup
            // must not lose the durable local intent (which a later reconciliation can re-forward), so we
            // never let it roll back the captured row — degrade, don't crash (EI-3).
            try {
                subscriptionPort.subscribeArea(subscriberPublicId, wardId);
                intent.markForwarded();
                subscriptions.save(intent);
            } catch (RuntimeException ex) {
                // Non-PII: subscriber/ward are opaque ids, never an MSISDN (S-4). The row stays unforwarded
                // for a later sweep to retry.
                log.warn("USSD area-alert forward failed (kept local intent for retry): ward={}, reason={}",
                        wardId, ex.getMessage());
            }
        }
        return true;
    }
}
