package com.taarifu.institutions.domain.repository;

import com.taarifu.institutions.domain.model.Parliament;
import com.taarifu.institutions.domain.model.enums.Legislature;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Parliament} terms (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: the persistence port for the parliament directory and admin CRUD. Supports the
 * "current term per legislature" read that anchors "the sitting Parliament". Soft-deleted rows are
 * excluded by the entity's {@code @SQLRestriction}.</p>
 */
public interface ParliamentRepository extends JpaRepository<Parliament, Long> {

    /**
     * @param publicId the parliament term's public id.
     * @return the matching term, or empty.
     */
    Optional<Parliament> findByPublicId(UUID publicId);

    /**
     * Lists parliament terms of a legislature, paged.
     *
     * @param legislature the legislature (Union / Zanzibar HoR).
     * @param pageable    paging/sorting.
     * @return a page of terms in that legislature.
     */
    Page<Parliament> findByLegislature(Legislature legislature, Pageable pageable);

    /**
     * Resolves the currently-sitting term of a legislature.
     *
     * <p>WHY by {@code current = true} (not "latest start date"): the current term is an administrative
     * declaration the DB partial-unique index guarantees is singular per legislature (see migration).</p>
     *
     * @param legislature the legislature.
     * @return the current term, or empty if none is flagged current.
     */
    Optional<Parliament> findByLegislatureAndCurrentTrue(Legislature legislature);
}
