package com.taarifu.institutions.domain.repository;

import com.taarifu.institutions.domain.model.Representative;
import com.taarifu.institutions.domain.model.enums.RepresentativeStatus;
import com.taarifu.institutions.domain.model.enums.RepresentativeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Representative} — the persistence port behind "find my
 * representatives", the directory, search, and the one-sitting-MP invariant (PRD §9.1, §22.6,
 * ARCHITECTURE.md §3.3).
 *
 * <p>All public lookups are by {@code publicId} (never the internal id, ADR-0006). Soft-deleted rows are
 * excluded by the entity's {@code @SQLRestriction}. "Find my rep" queries deliberately resolve through
 * the geography entities' {@code publicId} (a join) because callers only ever hold public ids.</p>
 */
public interface RepresentativeRepository extends JpaRepository<Representative, Long> {

    /**
     * @param publicId the representative's public id.
     * @return the matching representative, or empty if none/soft-deleted.
     */
    Optional<Representative> findByPublicId(UUID publicId);

    /**
     * Lightweight existence guard by public id — backs {@link
     * com.taarifu.institutions.api.RepresentativeQueryApi#exists(UUID)} so a cross-module caller
     * (accountability curated-authoring) can reject a reference to a non-existent representative without
     * loading the aggregate. Soft-deleted rows are excluded by the entity's {@code @SQLRestriction}, so a
     * retired/removed representative reads as absent here, exactly as intended.
     *
     * @param publicId the representative's public id.
     * @return {@code true} if a live (non-soft-deleted) representative with that public id exists.
     */
    boolean existsByPublicId(UUID publicId);

    /**
     * Finds the representatives mapped to a constituency in a given status (typically the single
     * {@link RepresentativeStatus#SITTING} MP for "find my rep").
     *
     * <p>WHY a list, not an {@code Optional}: although the DB invariant guarantees at most one
     * {@code SITTING} constituency-rep, the query is also reused for {@code FORMER} history (many rows),
     * so it returns a list and the caller picks per its need. Ordered newest-elected first.</p>
     *
     * @param constituencyPublicId the constituency's public id.
     * @param status               the lifecycle status to filter by.
     * @return matching representatives, newest-elected first.
     */
    @Query("""
            SELECT r FROM Representative r
            WHERE r.constituency.publicId = :constituencyPublicId
              AND r.status = :status
            ORDER BY r.electedAt DESC NULLS LAST
            """)
    List<Representative> findByConstituencyAndStatus(@Param("constituencyPublicId") UUID constituencyPublicId,
                                                     @Param("status") RepresentativeStatus status);

    /**
     * Finds the representatives tied to a ward in a given status (the Councillor (Diwani) and ward
     * executive officer for "find my rep").
     *
     * @param wardPublicId the ward's public id.
     * @param status       the lifecycle status to filter by.
     * @return matching representatives, newest-elected first.
     */
    @Query("""
            SELECT r FROM Representative r
            WHERE r.ward.publicId = :wardPublicId
              AND r.status = :status
            ORDER BY r.electedAt DESC NULLS LAST
            """)
    List<Representative> findByWardAndStatus(@Param("wardPublicId") UUID wardPublicId,
                                             @Param("status") RepresentativeStatus status);

    /**
     * Existence guard for the load-bearing invariant: is there already a {@link
     * RepresentativeStatus#SITTING} representative on this constituency (excluding a given record being
     * updated)?
     *
     * <p>WHY this <i>and</i> a DB partial-unique index: the index is the ultimate guarantee (handles
     * races), but this pre-check lets the admin service raise a clean, localised {@code CONFLICT} with a
     * helpful message instead of surfacing a raw constraint violation (PRD §17 — typed errors). The
     * {@code excludeId} lets an update of the same row pass.</p>
     *
     * @param constituencyId the internal constituency id.
     * @param excludeId      the representative id to exclude (its own id on update), or a sentinel like
     *                       {@code -1} on create.
     * @return {@code true} if another SITTING constituency-rep already occupies the seat.
     */
    @Query("""
            SELECT COUNT(r) > 0 FROM Representative r
            WHERE r.constituency.id = :constituencyId
              AND r.status = com.taarifu.institutions.domain.model.enums.RepresentativeStatus.SITTING
              AND r.id <> :excludeId
            """)
    boolean existsSittingByConstituency(@Param("constituencyId") Long constituencyId,
                                        @Param("excludeId") Long excludeId);

    /**
     * Paged directory listing optionally filtered by type and/or status.
     *
     * <p>WHY {@code COALESCE}-style nullable filters in one query (rather than a query-per-combination):
     * keeps the directory endpoint simple while supporting "all MPs", "all sitting reps", etc. A
     * {@code null} filter means "any". {@code FORMER} reps remain listable (badged historical, PRD §22.6).</p>
     *
     * @param type     optional type filter, or {@code null} for any.
     * @param status   optional status filter, or {@code null} for any.
     * @param pageable paging/sorting.
     * @return a page of representatives.
     */
    @Query("""
            SELECT r FROM Representative r
            WHERE (:type IS NULL OR r.type = :type)
              AND (:status IS NULL OR r.status = :status)
            """)
    Page<Representative> findDirectory(@Param("type") RepresentativeType type,
                                       @Param("status") RepresentativeStatus status,
                                       Pageable pageable);

    /**
     * Free-text directory search over a representative's biography (the only free-text field on the
     * entity). Name/party live on linked entities; richer cross-entity Swahili-aware search is the
     * communications {@code SearchPort}'s job (ARCHITECTURE.md §7). Case-insensitive contains.
     *
     * @param q        the search term.
     * @param pageable paging/sorting.
     * @return a page of matching representatives.
     */
    @Query("""
            SELECT r FROM Representative r
            WHERE LOWER(COALESCE(r.bio, '')) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    Page<Representative> search(@Param("q") String q, Pageable pageable);
}
