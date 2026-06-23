package com.taarifu.accountability.domain.repository;

import com.taarifu.accountability.domain.model.Promise;
import com.taarifu.accountability.domain.model.enums.PromiseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Promise} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: powers the public US-6.3 reads — a representative's promises, optionally filtered by
 * status, paged. Soft-deleted rows are excluded by the entity's {@code @SQLRestriction}.</p>
 */
public interface PromiseRepository extends JpaRepository<Promise, Long> {

    /**
     * @param publicId the promise's public id.
     * @return the promise, or empty.
     */
    Optional<Promise> findByPublicId(UUID publicId);

    /**
     * @param representativeId the subject representative's public id.
     * @param pageable         paging/sorting.
     * @return the representative's promises (any status), paged.
     */
    Page<Promise> findByRepresentativeId(UUID representativeId, Pageable pageable);

    /**
     * @param representativeId the subject representative's public id.
     * @param status           the status to filter by.
     * @param pageable         paging/sorting.
     * @return the representative's promises with that status, paged.
     */
    Page<Promise> findByRepresentativeIdAndStatus(
            UUID representativeId, PromiseStatus status, Pageable pageable);
}
