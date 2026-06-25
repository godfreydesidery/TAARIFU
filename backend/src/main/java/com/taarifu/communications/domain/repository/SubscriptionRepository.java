package com.taarifu.communications.domain.repository;

import com.taarifu.communications.domain.model.Subscription;
import com.taarifu.communications.domain.model.enums.SubscriptionTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Spring Data repository for {@link Subscription} follow edges (ARCHITECTURE §3.3).
 *
 * <p>Responsibility: the persistence port for follows. The feed assembly reads a profile's followed
 * area/category target ids; subscription management reads/lists a profile's follows. Soft-deleted
 * (unfollowed) rows are excluded automatically by the entity's {@code @SQLRestriction}.</p>
 */
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    /**
     * @param publicId the subscription's public id.
     * @return the matching subscription, or empty if none/soft-deleted.
     */
    Optional<Subscription> findByPublicId(UUID publicId);

    /**
     * Finds an existing live follow edge for idempotent follow/unfollow.
     *
     * @param followerProfileId the follower profile's public id.
     * @param targetType        the kind of target.
     * @param targetId          the target's public id.
     * @return the existing edge, or empty.
     */
    Optional<Subscription> findByFollowerProfileIdAndTargetTypeAndTargetId(
            UUID followerProfileId, SubscriptionTargetType targetType, UUID targetId);

    /**
     * Lists a profile's follows, paged — the subscription-management view.
     *
     * @param followerProfileId the follower profile's public id.
     * @param pageable          paging/sorting.
     * @return a page of the profile's follows.
     */
    Page<Subscription> findByFollowerProfileId(UUID followerProfileId, Pageable pageable);

    /**
     * Returns the target ids a profile follows of a given kind — used to build the feed query inputs.
     *
     * @param followerProfileId the follower profile's public id.
     * @param targetType        the kind of target (AREA / CATEGORY / REPRESENTATIVE).
     * @return the set of followed target public ids (possibly empty).
     */
    @Query("""
            SELECT s.targetId FROM Subscription s
            WHERE s.followerProfileId = :followerProfileId AND s.targetType = :targetType
            """)
    Set<UUID> findTargetIds(@Param("followerProfileId") UUID followerProfileId,
                            @Param("targetType") SubscriptionTargetType targetType);

    /**
     * Returns the profile ids that follow a given target — the fan-out recipient set for an announcement
     * targeting that area/category/representative.
     *
     * @param targetType the kind of target.
     * @param targetIds  the target public ids the announcement reaches.
     * @return the set of follower profile public ids (possibly empty).
     */
    @Query("""
            SELECT DISTINCT s.followerProfileId FROM Subscription s
            WHERE s.targetType = :targetType AND s.targetId IN :targetIds
            """)
    List<UUID> findFollowerProfileIds(@Param("targetType") SubscriptionTargetType targetType,
                                      @Param("targetIds") Set<UUID> targetIds);

    /**
     * Returns the distinct profile ids that follow at least one target of a given kind — the digest's
     * recipient set ("everyone who follows any area"). Paged so the {@code @Scheduled} digest job streams
     * the population in bounded slices and never loads the whole follower base into one heap (a citywide
     * fan-out could be large; the job iterates pages, ARCHITECTURE §8 / PRD §15 cost-awareness).
     *
     * @param targetType the kind of follow that makes a profile a digest candidate (AREA for the area digest).
     * @param pageable   the bounded slice (page size + page number) the digest job iterates.
     * @return a page of distinct follower profile public ids.
     */
    @Query(value = """
            SELECT DISTINCT s.followerProfileId FROM Subscription s
            WHERE s.targetType = :targetType
            """,
            // Explicit DISTINCT count: a derived count over a SELECT DISTINCT projection is ambiguous, so the
            // count of *distinct followers* is stated here to keep the paged query correct (Spring Data JPA).
            countQuery = """
            SELECT COUNT(DISTINCT s.followerProfileId) FROM Subscription s
            WHERE s.targetType = :targetType
            """)
    Page<UUID> findDistinctFollowerProfileIds(@Param("targetType") SubscriptionTargetType targetType,
                                              Pageable pageable);

    /**
     * Returns the area target ids a profile follows — the digest's per-recipient "which areas to summarise"
     * input. Equivalent to {@link #findTargetIds} for {@link SubscriptionTargetType#AREA}; named explicitly
     * here so the digest reads intention-revealingly.
     *
     * @param followerProfileId the recipient profile's public id.
     * @return the set of followed AREA target public ids (possibly empty).
     */
    @Query("""
            SELECT s.targetId FROM Subscription s
            WHERE s.followerProfileId = :followerProfileId
              AND s.targetType = com.taarifu.communications.domain.model.enums.SubscriptionTargetType.AREA
            """)
    Set<UUID> findFollowedAreaIds(@Param("followerProfileId") UUID followerProfileId);
}
