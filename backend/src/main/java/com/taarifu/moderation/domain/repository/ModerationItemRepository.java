package com.taarifu.moderation.domain.repository;

import com.taarifu.moderation.domain.model.ModerationItem;
import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.moderation.domain.model.enums.ModerationItemStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link ModerationItem} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: drives the prioritised moderator queue (UC-H01) and the
 * one-live-item-per-subject collapse — {@link #findBySubjectTypeAndSubjectId} finds the existing open
 * item to attach a new flag to (rather than opening a duplicate), and {@link #findByStatus} (paged,
 * sortable by {@code severity}/{@code slaDueAt}) renders the queue for moderators.</p>
 */
public interface ModerationItemRepository extends JpaRepository<ModerationItem, Long> {

    /**
     * @param publicId the item's public id.
     * @return the item, or empty.
     */
    Optional<ModerationItem> findByPublicId(UUID publicId);

    /**
     * @param subjectType the kind of content.
     * @param subjectId   the content's public id.
     * @return the live item for this subject, or empty (the {@code @SQLRestriction} hides tombstoned rows,
     *         so at most one live row exists per the UNIQUE constraint).
     */
    Optional<ModerationItem> findBySubjectTypeAndSubjectId(FlagSubjectType subjectType, UUID subjectId);

    /**
     * @param status   the queue status to list (typically {@code PENDING}/{@code IN_REVIEW}).
     * @param pageable page + sort (e.g. {@code severity,desc} then {@code slaDueAt,asc}).
     * @return the paged queue.
     */
    Page<ModerationItem> findByStatus(ModerationItemStatus status, Pageable pageable);
}
