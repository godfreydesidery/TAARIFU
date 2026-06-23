package com.taarifu.responders.domain.repository;

import com.taarifu.responders.domain.model.Organisation;
import com.taarifu.responders.domain.model.enums.OrganisationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Organisation} (ARCHITECTURE.md §3.3, PRD §24).
 *
 * <p>Responsibility: lookups for the public provider directory and admin management. Public reads use
 * the {@code findPublic*} methods, which additionally constrain to publicly listable rows
 * ({@link OrganisationStatus#ACTIVE} + verified) so a PENDING or unverified body never leaks into the
 * citizen-facing directory (PRD §24.4). Soft-deleted rows are excluded by the entity's
 * {@code @SQLRestriction}.</p>
 */
public interface OrganisationRepository extends JpaRepository<Organisation, Long> {

    /**
     * @param publicId the organisation's public id.
     * @return the organisation, or empty if none/soft-deleted (admin view — any status).
     */
    Optional<Organisation> findByPublicId(UUID publicId);

    /**
     * Public-directory lookup: returns the organisation only if it is active and verified.
     *
     * @param publicId the organisation's public id.
     * @return the publicly listable organisation, or empty if missing/not-listable.
     */
    Optional<Organisation> findByPublicIdAndStatusAndVerifiedTrue(UUID publicId, OrganisationStatus status);

    /**
     * Lists the publicly listable organisations (active + verified), paged, for the directory.
     *
     * @param status   the required status (always {@link OrganisationStatus#ACTIVE} for public reads).
     * @param pageable paging/sorting.
     * @return a page of publicly listable organisations.
     */
    Page<Organisation> findByStatusAndVerifiedTrue(OrganisationStatus status, Pageable pageable);

    /**
     * Admin listing of all organisations (any status), paged.
     *
     * @param pageable paging/sorting.
     * @return a page of all (non-deleted) organisations.
     */
    Page<Organisation> findAllBy(Pageable pageable);
}
