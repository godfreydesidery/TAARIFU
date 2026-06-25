package com.taarifu.ussd.application.port;

import java.util.UUID;

/**
 * Consumer-owned port describing what the USSD flows need from <b>communications</b> to forward a
 * my-area-alert intent into a real area follow (PRD §14 item 3, EI-3/EI-4; A3; ADR-0013, ADR-0019).
 *
 * <p>Responsibility: capture the communications contract this module depends on, decoupled from
 * communications' internals. When a feature-phone citizen subscribes their registered area to SMS alerts, the
 * intent (captured locally as a {@code UssdAlertSubscription}) is forwarded through this seam to register the
 * real {@code AREA} {@code Subscription} that drives announcement fan-out (FEED + SMS).</p>
 *
 * <p>WHY this interface lives in the <b>consumer</b> module (not imported from communications): per the
 * isolation rule this module must not write communications' {@code subscription} table nor import its
 * {@code application}/{@code domain}. The seam is defined here and bound (by {@code SubscriptionUssdAdapter}) to
 * communications' published {@code AreaSubscriptionApi} command port — the sanctioned synchronous
 * {@code ussd → communications} edge (ADR-0013 §1). The port speaks only public {@code UUID}s — never a
 * communications entity (ARCHITECTURE §3.2).</p>
 *
 * <p><b>⚠ Grain (ADR-0019 §1b; CENTRAL INTEGRATION NEED):</b> a communications {@code AREA} follow is keyed by
 * the subscriber's <b>profile</b> public id (the grain the fan-out resolves an MSISDN from), whereas the USSD
 * dialogue carries the MSISDN-linked <b>account</b> public id. Resolving account→profile is identity's concern
 * and is out of this increment's scope, so the live forward call stays gated until identity exposes that
 * resolution; this port + adapter are the ready-to-call seam (the contract is fixed so the wire-up is a
 * one-liner later). No token is read on this path (the civic-integrity fence, D18).</p>
 */
public interface UssdSubscriptionPort {

    /**
     * Forwards an area-alert subscription to communications, registering the real {@code AREA} follow.
     *
     * @param subscriberProfilePublicId the subscriber's <b>profile</b> public id (the fan-out grain — see the
     *                                  grain note on this type).
     * @param wardPublicId              the followed ward (Kata) public id (minimum pin granularity, PRD §9.0).
     * @return the registered follow edge's public id.
     */
    UUID subscribeArea(UUID subscriberProfilePublicId, UUID wardPublicId);
}
