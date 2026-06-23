package com.taarifu.accountability.domain.repository;

import com.taarifu.accountability.domain.model.RepresentativeContribution;
import com.taarifu.accountability.domain.model.enums.ContributionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link RepresentativeContribution} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: powers the public US-6.1 reads — a representative's contributions, optionally
 * filtered by type, paged. Soft-deleted rows are excluded by the entity's {@code @SQLRestriction}.</p>
 */
public interface RepresentativeContributionRepository
        extends JpaRepository<RepresentativeContribution, Long> {

    /**
     * @param publicId the contribution's public id.
     * @return the contribution, or empty.
     */
    Optional<RepresentativeContribution> findByPublicId(UUID publicId);

    /**
     * @param representativeId the subject representative's public id.
     * @param pageable         paging/sorting.
     * @return the representative's contributions (any type), paged.
     */
    Page<RepresentativeContribution> findByRepresentativeId(UUID representativeId, Pageable pageable);

    /**
     * @param representativeId the subject representative's public id.
     * @param type             the contribution type to filter by.
     * @param pageable         paging/sorting.
     * @return the representative's contributions of that type, paged.
     */
    Page<RepresentativeContribution> findByRepresentativeIdAndType(
            UUID representativeId, ContributionType type, Pageable pageable);
}
