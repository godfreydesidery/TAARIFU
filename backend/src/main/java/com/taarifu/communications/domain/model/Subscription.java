package com.taarifu.communications.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.communications.domain.model.enums.SubscriptionTargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

/**
 * A follow edge: a citizen profile follows an area, representative, or category
 * (PRD §9.1 "Follow: Profile *-* (Area|Representative|Category|Project)", M4, UC-G05).
 *
 * <p>Responsibility: records "this profile wants the feed/announcements for this target". The
 * personalised feed is assembled from a profile's set of subscriptions plus its area-matched
 * announcements (PRD §12 UC-G04). Both the follower and the target are referenced by public
 * {@code UUID} — the follower is an {@code identity} profile and the target is owned by geography /
 * institutions / reporting — so this module never FK-joins another module's tables (ARCHITECTURE §3.2,
 * the parallel-build isolation rule).</p>
 *
 * <p>WHY a unique constraint on {@code (follower, targetType, targetId)} scoped to live rows: a profile
 * follows a given target at most once — a duplicate follow is meaningless and would double-count feed
 * fan-out. JPA cannot express the "live rows only" partial uniqueness, so the Flyway migration owns a
 * partial unique index ({@code WHERE deleted = false}); the table-level constraint here documents the
 * intent and covers the non-soft-deleted common case. Unfollow is a <b>soft-delete</b> so the follow
 * history stays auditable (PRD §9) and a re-follow inserts a fresh row.</p>
 */
@Entity
@Table(name = "subscription",
        uniqueConstraints = {
                @UniqueConstraint(name = "ux_subscription_follow",
                        columnNames = {"follower_profile_id", "target_type", "target_id"})
        },
        indexes = {
                @Index(name = "ix_subscription_follower", columnList = "follower_profile_id"),
                @Index(name = "ix_subscription_target", columnList = "target_type, target_id")
        })
@SQLRestriction("deleted = false")
public class Subscription extends BaseEntity {

    /** Public id of the following profile ({@code identity}). Bare {@code UUID}, never a FK. */
    @Column(name = "follower_profile_id", nullable = false)
    private UUID followerProfileId;

    /** What kind of thing is followed (area/representative/category). */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private SubscriptionTargetType targetType;

    /**
     * Public id of the followed target (a geography area, a representative, or a category). Bare
     * {@code UUID} reference to the owning module — resolved/validated there, never FK-joined here.
     */
    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    /** JPA requires a no-arg constructor; not for application use. */
    protected Subscription() {
    }

    /**
     * Creates a follow edge.
     *
     * @param followerProfileId the following profile's public id.
     * @param targetType        the kind of target.
     * @param targetId          the target's public id.
     * @return the populated, transient subscription.
     */
    public static Subscription follow(UUID followerProfileId, SubscriptionTargetType targetType,
                                      UUID targetId) {
        Subscription s = new Subscription();
        s.followerProfileId = followerProfileId;
        s.targetType = targetType;
        s.targetId = targetId;
        return s;
    }

    /** @return the following profile's public id. */
    public UUID getFollowerProfileId() {
        return followerProfileId;
    }

    /** @return the kind of followed target. */
    public SubscriptionTargetType getTargetType() {
        return targetType;
    }

    /** @return the followed target's public id. */
    public UUID getTargetId() {
        return targetId;
    }
}
