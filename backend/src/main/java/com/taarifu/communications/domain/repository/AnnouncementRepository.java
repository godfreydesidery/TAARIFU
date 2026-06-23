package com.taarifu.communications.domain.repository;

import com.taarifu.communications.domain.model.Announcement;
import com.taarifu.communications.domain.model.enums.AnnouncementStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Spring Data repository for {@link Announcement} (ARCHITECTURE §3.3).
 *
 * <p>Responsibility: the persistence port for announcements. All public lookups are by {@code publicId}
 * (never the internal {@code Long id}, ADR-0006); soft-deleted rows are excluded automatically by the
 * entity's {@code @SQLRestriction}. The feed query is the performance-critical read (PRD §15) and is
 * expressed against the indexed {@code (status, publish_at)} columns.</p>
 */
public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    /**
     * @param publicId the announcement's public id.
     * @return the matching announcement, or empty if none/soft-deleted.
     */
    Optional<Announcement> findByPublicId(UUID publicId);

    /**
     * Lists an author's own announcements (any status), paged — the author's management view.
     *
     * @param authorProfileId the author profile's public id.
     * @param pageable        paging/sorting.
     * @return a page of the author's announcements.
     */
    Page<Announcement> findByAuthorProfileId(UUID authorProfileId, Pageable pageable);

    /**
     * The personalised-feed query: live ({@code PUBLISHED}), non-expired announcements whose audience
     * matches the caller's followed areas/category — newest first.
     *
     * <p>WHY this shape: the feed is assembled from the caller's subscription set. An announcement is
     * in-feed if it targets one of the caller's followed areas <b>or</b> its tagged category is followed,
     * is {@code PUBLISHED}, has reached its {@code publishAt}, and has not passed its {@code expireAt}
     * (PRD §12 UC-G04, §22.6 "published, non-expired"). An empty {@code areaIds} and {@code null}
     * {@code categoryIds} yields no rows (deny-by-default — a citizen following nothing gets the
     * announcement-less feed, which the service supplements with their own-area items).</p>
     *
     * @param areaIds     the caller's followed area public ids (matched against the audience areas).
     * @param categoryIds the caller's followed category public ids (matched against the tag).
     * @param now         the current instant (for the publish/expiry window).
     * @param pageable    paging/sorting (the service orders by {@code publishAt} desc).
     * @return a page of in-feed announcements.
     */
    @Query("""
            SELECT DISTINCT a FROM Announcement a
            LEFT JOIN a.audienceAreaIds area
            WHERE a.status = com.taarifu.communications.domain.model.enums.AnnouncementStatus.PUBLISHED
              AND (a.publishAt IS NULL OR a.publishAt <= :now)
              AND (a.expireAt IS NULL OR a.expireAt > :now)
              AND (area IN :areaIds OR a.categoryId IN :categoryIds)
            """)
    Page<Announcement> findFeed(@Param("areaIds") Set<UUID> areaIds,
                                @Param("categoryIds") Set<UUID> categoryIds,
                                @Param("now") Instant now,
                                Pageable pageable);

    /**
     * Finds announcements due for a lifecycle transition the scheduler runs (e.g. {@code SCHEDULED}
     * whose {@code publishAt} has passed, or {@code PUBLISHED} whose {@code expireAt} has passed).
     *
     * @param status the current status to sweep.
     * @param cutoff the boundary instant.
     * @return announcements in {@code status} with {@code publishAt}/{@code expireAt} at/before cutoff.
     */
    @Query("""
            SELECT a FROM Announcement a
            WHERE a.status = :status
              AND ((:status = com.taarifu.communications.domain.model.enums.AnnouncementStatus.SCHEDULED
                       AND a.publishAt IS NOT NULL AND a.publishAt <= :cutoff)
                OR (:status = com.taarifu.communications.domain.model.enums.AnnouncementStatus.PUBLISHED
                       AND a.expireAt IS NOT NULL AND a.expireAt <= :cutoff))
            """)
    java.util.List<Announcement> findDueForTransition(@Param("status") AnnouncementStatus status,
                                                      @Param("cutoff") Instant cutoff);
}
