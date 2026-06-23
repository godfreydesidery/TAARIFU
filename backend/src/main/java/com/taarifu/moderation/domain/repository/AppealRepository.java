package com.taarifu.moderation.domain.repository;

import com.taarifu.moderation.domain.model.Appeal;
import com.taarifu.moderation.domain.model.enums.AppealStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Appeal} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: drives the appeal queue (UC-H03) and the one-live-appeal-per-action rule —
 * {@link #existsByActionPublicId} backs the pre-insert check (the DB UNIQUE constraint is the backstop),
 * and {@link #findByStatus} renders the open-appeals queue for an <i>independent</i> moderator.</p>
 */
public interface AppealRepository extends JpaRepository<Appeal, Long> {

    /**
     * @param publicId the appeal's public id.
     * @return the appeal, or empty.
     */
    Optional<Appeal> findByPublicId(UUID publicId);

    /**
     * @param actionPublicId the appealed action's public id.
     * @return {@code true} if a live appeal already exists for this action.
     */
    boolean existsByActionPublicId(UUID actionPublicId);

    /**
     * @param status   the appeal status to list (typically {@code OPEN}).
     * @param pageable page + sort.
     * @return the paged appeal queue.
     */
    Page<Appeal> findByStatus(AppealStatus status, Pageable pageable);
}
