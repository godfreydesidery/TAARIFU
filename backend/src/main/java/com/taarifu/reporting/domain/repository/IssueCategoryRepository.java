package com.taarifu.reporting.domain.repository;

import com.taarifu.reporting.domain.model.IssueCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link IssueCategory} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: lookups for the category picker (active categories) and Admin CRUD by
 * {@code publicId}/{@code code}. Soft-deleted rows are excluded by the entity's {@code @SQLRestriction}.</p>
 */
public interface IssueCategoryRepository extends JpaRepository<IssueCategory, Long> {

    /**
     * @param publicId the category's public id.
     * @return the matching category, or empty if none/soft-deleted.
     */
    Optional<IssueCategory> findByPublicId(UUID publicId);

    /**
     * @param code the stable machine code.
     * @return the matching category, or empty.
     */
    Optional<IssueCategory> findByCode(String code);

    /**
     * @param code the stable machine code.
     * @return {@code true} if a (non-deleted) category with this code exists — used to reject duplicate
     *         codes on create (the {@code code} is also DB-unique as a backstop).
     */
    boolean existsByCode(String code);

    /**
     * Lists the active categories for the picker (PRD §10 US-3.1 "category picker").
     *
     * @param pageable bounded paging/sorting.
     * @return a page of active categories.
     */
    Page<IssueCategory> findByActiveTrue(Pageable pageable);

    /**
     * Lists all categories (active and retired) for the Admin console (UC-B14).
     *
     * @param pageable bounded paging/sorting.
     * @return a page of all categories.
     */
    Page<IssueCategory> findAllByDeletedFalse(Pageable pageable);
}
