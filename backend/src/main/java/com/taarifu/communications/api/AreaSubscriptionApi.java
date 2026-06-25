package com.taarifu.communications.api;

import java.util.UUID;

/**
 * The communications module's <b>public, in-process command port</b> for registering a citizen's
 * <b>area (Kata) follow for SMS alerts</b> (ADR-0013 §1; ADR-0019; A3; PRD §9.1/§13/§14, UC-G05, M4).
 * A sibling channel that captures a citizen's intent to receive alerts for an area — above all the
 * feature-phone {@code ussd} module's "my-area alerts" menu (PRD §14 item 3) — depends on, and calls, this
 * interface synchronously ({@code ussd → communications}), <b>without</b> writing communications'
 * {@code subscription} table directly (ARCHITECTURE §3.2). It is the published façade over the internal
 * {@code SubscriptionService} idempotent follow.
 *
 * <p>Responsibility: expose "this subscriber wants alerts for this area" as the sanctioned cross-module
 * contract, registering a {@code Subscription} of {@code targetType = AREA} that the announcement fan-out
 * ({@code AnnouncementPublishedHandler}) resolves into per-recipient notifications (FEED + SMS) for an area
 * announcement. Communications stays the single writer of its {@code subscription} table; the caller sees only
 * public {@code UUID}s.</p>
 *
 * <p><b>WHY the subscriber is a PROFILE public id (not an account id):</b> a {@code Subscription} is keyed by
 * {@code followerProfileId}, and the fan-out resolves a recipient's MSISDN via
 * {@code identity.api.RecipientContactApi.contactFor(profileId)} — so an area follow registered at any other
 * grain would <b>silently never deliver</b>. This port therefore contracts a <b>profile</b> id and the caller
 * must supply one. (The {@code ussd} module currently carries only the MSISDN-linked <i>account</i> id; the
 * account→profile resolution is identity's concern and is a CENTRAL INTEGRATION NEED — see ADR-0019 §1b. This
 * contract is fixed now so the wire-up is a one-liner once identity exposes that resolution.)</p>
 *
 * <p><b>Idempotent</b> (DI4): re-subscribing the same {@code (subscriber, area)} is a no-op that returns the
 * existing follow edge — never a duplicate (the partial unique index on
 * {@code (follower_profile_id, target_type, target_id)} is the hard backstop).</p>
 *
 * <p><b>🔒 Civic-integrity fence (D18, §23.5):</b> no token/balance input or output anywhere on this port — a
 * feature-phone citizen is never priced or gated out of receiving their area's alerts.</p>
 */
public interface AreaSubscriptionApi {

    /**
     * Idempotently registers an area (Kata) follow for the subscriber, so area announcements reach them over
     * their preferred channels (FEED + SMS fallback for a feature-phone citizen).
     *
     * @param subscriberProfilePublicId the subscribing citizen's <b>profile</b> public id (the fan-out grain —
     *                                  see the WHY note on the type); must not be {@code null}.
     * @param wardPublicId              the followed ward (Kata) public id — the geography area at the minimum
     *                                  pin granularity (PRD §9.0); must not be {@code null}.
     * @return the follow edge's public id (existing on a repeat, new otherwise).
     */
    UUID subscribeArea(UUID subscriberProfilePublicId, UUID wardPublicId);
}
