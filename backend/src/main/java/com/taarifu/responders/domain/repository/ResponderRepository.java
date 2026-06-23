package com.taarifu.responders.domain.repository;

import com.taarifu.responders.domain.model.Responder;
import com.taarifu.responders.domain.model.enums.ResponderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Responder} capabilities (ARCHITECTURE.md §3.3, PRD §24).
 *
 * <p>Responsibility: directory browse ("who handles what") and admin management. Public directory
 * reads constrain to {@link ResponderStatus#ACTIVE} responders whose owning organisation is publicly
 * listable (active + verified), so a citizen never sees an unverified/pending provider (PRD §24.4).
 * The {@code findByHandledCategory} query supports the "who handles this category?" directory filter,
 * keyed by the reporting-module category id this module stores by reference.</p>
 */
public interface ResponderRepository extends JpaRepository<Responder, Long> {

    /**
     * @param publicId the responder's public id.
     * @return the responder, or empty if none/soft-deleted (admin view — any status).
     */
    Optional<Responder> findByPublicId(UUID publicId);

    /**
     * Lists all responders of an organisation, paged (admin view).
     *
     * @param organisationPublicId the owning organisation's public id.
     * @param pageable             paging/sorting.
     * @return a page of responders under that organisation.
     */
    @Query("SELECT r FROM Responder r WHERE r.organisation.publicId = :organisationPublicId")
    Page<Responder> findByOrganisationPublicId(@Param("organisationPublicId") UUID organisationPublicId,
                                               Pageable pageable);

    /**
     * Public directory: lists ACTIVE responders whose organisation is active + verified, paged.
     *
     * <p>WHY the join-condition on the organisation: a verified organisation gate is the §24.4 rule for
     * public visibility; pushing it into the query guarantees no unverified provider is ever returned to
     * the citizen-facing directory regardless of the caller.</p>
     *
     * @param pageable paging/sorting.
     * @return a page of publicly listable responders.
     */
    @Query("""
            SELECT r FROM Responder r
            WHERE r.status = com.taarifu.responders.domain.model.enums.ResponderStatus.ACTIVE
              AND r.organisation.status = com.taarifu.responders.domain.model.enums.OrganisationStatus.ACTIVE
              AND r.organisation.verified = true
            """)
    Page<Responder> findPubliclyListable(Pageable pageable);

    /**
     * Public directory filter: ACTIVE, publicly listable responders that handle a given category id.
     *
     * @param categoryPublicId the reporting-module category id (stored here by reference).
     * @param pageable         paging/sorting.
     * @return a page of publicly listable responders handling that category.
     */
    @Query("""
            SELECT r FROM Responder r
            JOIN r.handledCategoryIds c
            WHERE c = :categoryPublicId
              AND r.status = com.taarifu.responders.domain.model.enums.ResponderStatus.ACTIVE
              AND r.organisation.status = com.taarifu.responders.domain.model.enums.OrganisationStatus.ACTIVE
              AND r.organisation.verified = true
            """)
    Page<Responder> findPubliclyListableByCategory(@Param("categoryPublicId") UUID categoryPublicId,
                                                   Pageable pageable);
}
