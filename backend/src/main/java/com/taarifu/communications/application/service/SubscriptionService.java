package com.taarifu.communications.application.service;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.communications.domain.model.Subscription;
import com.taarifu.communications.domain.model.enums.SubscriptionTargetType;
import com.taarifu.communications.domain.repository.SubscriptionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Manages a citizen's follows (subscriptions) — PRD §9.1, UC-G05, M4.
 *
 * <p>Responsibility: the use-case orchestration for follow/unfollow/list. It owns the transaction
 * boundary, validates the target-type input, and enforces <b>idempotent</b> follow (re-following an
 * already-followed target returns the existing edge, never a duplicate) and idempotent unfollow
 * (soft-delete; unfollowing a not-followed target is a no-op success). The follower is always the
 * authenticated caller — a citizen manages only their own follows (no cross-user follow management).</p>
 *
 * <p>WHY unfollow is a soft-delete (not a hard delete): the follow history stays auditable (PRD §9) and a
 * later re-follow inserts a fresh row, so the unique-edge invariant holds across the live set only (the
 * partial unique index scopes to {@code deleted = false}).</p>
 */
@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;

    /**
     * @param subscriptionRepository follow-edge persistence.
     */
    public SubscriptionService(SubscriptionRepository subscriptionRepository) {
        this.subscriptionRepository = subscriptionRepository;
    }

    /**
     * Follows a target idempotently for the caller.
     *
     * @param callerProfileId the authenticated caller's profile public id.
     * @param targetTypeName  the target-type enum name (validated).
     * @param targetId        the followed target's public id.
     * @return the (existing or newly created) follow edge.
     * @throws ApiException {@link ErrorCode#VALIDATION_FAILED} if the target type is unknown.
     */
    @Transactional
    public Subscription follow(UUID callerProfileId, String targetTypeName, UUID targetId) {
        SubscriptionTargetType targetType = parseTargetType(targetTypeName);
        Optional<Subscription> existing = subscriptionRepository
                .findByFollowerProfileIdAndTargetTypeAndTargetId(callerProfileId, targetType, targetId);
        // Idempotent: re-follow returns the existing live edge (the unique index is the hard backstop).
        return existing.orElseGet(() -> subscriptionRepository.save(
                Subscription.follow(callerProfileId, targetType, targetId)));
    }

    /**
     * Unfollows a target idempotently for the caller (soft-delete).
     *
     * @param callerProfileId the authenticated caller's profile public id.
     * @param subscriptionPublicId the follow edge's public id.
     * @throws ApiException {@link ErrorCode#NOT_FOUND} if no such live edge,
     *                      {@link ErrorCode#FORBIDDEN} if it is not the caller's own follow.
     */
    @Transactional
    public void unfollow(UUID callerProfileId, UUID subscriptionPublicId) {
        Subscription s = subscriptionRepository.findByPublicId(subscriptionPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        // A citizen may only unfollow their own follows (ownership check — never another user's edge).
        if (!s.getFollowerProfileId().equals(callerProfileId)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        s.markDeleted(callerProfileId);
        subscriptionRepository.save(s);
    }

    /**
     * Lists the caller's follows, paged.
     *
     * @param callerProfileId the authenticated caller's profile public id.
     * @param pageable        paging/sorting.
     * @return a page of the caller's follow edges.
     */
    @Transactional(readOnly = true)
    public Page<Subscription> listMyFollows(UUID callerProfileId, Pageable pageable) {
        return subscriptionRepository.findByFollowerProfileId(callerProfileId, pageable);
    }

    /** Parses the target-type name; an unknown value is a localised validation failure. */
    private SubscriptionTargetType parseTargetType(String name) {
        try {
            return SubscriptionTargetType.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
