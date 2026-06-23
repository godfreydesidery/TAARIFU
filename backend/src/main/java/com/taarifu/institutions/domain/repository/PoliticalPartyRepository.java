package com.taarifu.institutions.domain.repository;

import com.taarifu.institutions.domain.model.PoliticalParty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link PoliticalParty} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: the persistence port for the party directory and admin CRUD. Public lookups are by
 * {@code publicId}; the {@code code} lookup supports idempotent admin upserts. Soft-deleted rows are
 * excluded automatically by the entity's {@code @SQLRestriction} (ARCHITECTURE.md §4.2).</p>
 */
public interface PoliticalPartyRepository extends JpaRepository<PoliticalParty, Long> {

    /**
     * @param publicId the party's public id.
     * @return the matching party, or empty if none/soft-deleted.
     */
    Optional<PoliticalParty> findByPublicId(UUID publicId);

    /**
     * @param code the official party code.
     * @return the matching party, or empty.
     */
    Optional<PoliticalParty> findByCode(String code);

    /**
     * Free-text directory search over party name/abbreviation (case-insensitive contains).
     *
     * <p>WHY a simple {@code LOWER(...) LIKE} and not full-text search here: the party set is tiny
     * (tens of rows) so a trigram/FTS index is unjustified complexity (KISS, CLAUDE.md §3); richer
     * Swahili-aware search is the communications {@code SearchPort}'s job for large corpora.</p>
     *
     * @param q        the search term; when blank the caller should use {@link #findAll(Pageable)}.
     * @param pageable paging/sorting.
     * @return a page of matching parties.
     */
    @Query("""
            SELECT p FROM PoliticalParty p
            WHERE LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(COALESCE(p.abbreviation, '')) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    Page<PoliticalParty> search(@Param("q") String q, Pageable pageable);
}
