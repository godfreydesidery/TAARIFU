package com.taarifu.communications.application.service;

import com.taarifu.communications.api.AreaSubscriptionApi;
import com.taarifu.communications.domain.model.Subscription;
import com.taarifu.communications.domain.model.enums.SubscriptionTargetType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Communications' implementation of the published {@link AreaSubscriptionApi} — the synchronous
 * {@code ussd → communications} "register an area follow for SMS alerts" seam (ADR-0013 §1; ADR-0019; A3;
 * PRD §9.1/§13/§14, UC-G05, M4).
 *
 * <p>Responsibility: turn a feature-phone citizen's "alert me for my area" intent into a real
 * {@link Subscription} of {@code targetType = AREA}, keyed by the subscriber's <b>profile</b> public id — the
 * grain the announcement fan-out and {@code RecipientContactApi} resolve, so an area announcement actually
 * reaches the subscriber over their channels (FEED + SMS fallback). It delegates the idempotent follow to the
 * existing {@link SubscriptionService} (DRY — the one-edge-per-target invariant lives in one place) so this
 * cross-module path and the in-app follow path can never diverge.</p>
 *
 * <p><b>Idempotent</b> (DI4): a repeat subscription returns the existing live edge — never a duplicate (the
 * partial unique index on {@code (follower_profile_id, target_type, target_id)} is the hard backstop).</p>
 *
 * <p><b>WHY {@code AREA} (not a bespoke SMS-channel preference row):</b> the inclusion design already routes an
 * area announcement to its area followers and degrades PUSH→SMS for a citizen with no smartphone
 * (EI-5, {@code NotificationDispatchService}). Registering an {@code AREA} follow therefore <i>is</i> the
 * SMS-alert subscription for a feature-phone user — no separate channel-preference plumbing is needed (KISS).
 * The SMS default-off cost guard (PRD §13) is the recipient's per-channel concern, not this follow.</p>
 *
 * <p><b>🔒 Fence (D18):</b> no token collaborator on this path. <b>PII:</b> only public {@code UUID}s cross —
 * no MSISDN is read or logged here (the fan-out resolves the contact later, S-4).</p>
 */
@Service
public class AreaSubscriptionService implements AreaSubscriptionApi {

    private final SubscriptionService subscriptionService;

    /**
     * @param subscriptionService the existing idempotent follow service (reused for DRY — one follow path).
     */
    public AreaSubscriptionService(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public UUID subscribeArea(UUID subscriberProfilePublicId, UUID wardPublicId) {
        // Delegate to the canonical idempotent follow (AREA target). A repeat returns the existing edge; the
        // unique index is the hard backstop against a concurrent double-follow (DI4).
        Subscription edge = subscriptionService.follow(
                subscriberProfilePublicId, SubscriptionTargetType.AREA.name(), wardPublicId);
        return edge.getPublicId();
    }
}
