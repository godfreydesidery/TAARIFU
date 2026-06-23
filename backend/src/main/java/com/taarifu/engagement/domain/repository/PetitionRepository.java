package com.taarifu.engagement.domain.repository;

import com.taarifu.engagement.domain.model.Petition;
import com.taarifu.engagement.domain.model.enums.PetitionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Petition} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: petition lookups by {@code publicId} and public listing by status. Soft-deleted
 * rows are excluded by the entity's {@code @SQLRestriction}. The status-scoped list lets the public
 * endpoint show only non-DRAFT petitions (PRD §22.6 — drafts and moderation-held items excluded).</p>
 */
public interface PetitionRepository extends JpaRepository<Petition, Long> {

    /**
     * @param publicId the petition's public id.
     * @return the matching petition, or empty if none/soft-deleted.
     */
    Optional<Petition> findByPublicId(UUID publicId);

    /**
     * Lists petitions whose status is in the given set (used to expose only publicly-visible states).
     *
     * @param statuses the allowed statuses (e.g. all but DRAFT for the public list).
     * @param pageable bounded paging/sorting.
     * @return a page of petitions.
     */
    Page<Petition> findByStatusIn(Collection<PetitionStatus> statuses, Pageable pageable);
}
