package com.taarifu.geography.domain.repository;

import com.taarifu.geography.domain.model.Location;
import com.taarifu.geography.domain.model.LocationClosure;
import com.taarifu.geography.domain.model.enums.LocationType;
import com.taarifu.geography.domain.repository.projection.WardSummaryProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Location} administrative nodes (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: the persistence port for the geography read slice. All public lookups are by
 * {@code publicId} (never the internal {@code Long id}, ADR-0006). Soft-deleted rows are excluded
 * automatically by the entity's {@code @SQLRestriction} (ARCHITECTURE.md §4.2).</p>
 */
public interface LocationRepository extends JpaRepository<Location, Long> {

    /**
     * @param publicId the public id of the location.
     * @return the matching location, or empty if none/soft-deleted.
     */
    Optional<Location> findByPublicId(UUID publicId);

    /**
     * Resolves a location's <b>public id</b> by its official administrative {@code code} restricted to a given
     * level — backing {@code geography.api.WardCodeQueryApi} for the USSD "enter a ward code" step (A7,
     * ADR-0019, PRD §9.0/§14).
     *
     * <p>WHY a projection of the {@code publicId} only (not the whole {@link Location}): the cross-module ward
     * lookup needs the public id and nothing else, so returning a bare {@code UUID} keeps the entity from being
     * loaded and from ever leaking past the published port (boundary discipline, CLAUDE.md §8). The match is
     * <b>case-insensitive</b> on {@code code} (a feature-phone user may type any case) and pinned to
     * {@code type} so only a ward code resolves to a ward — a region/district code can never be mistaken for a
     * ward at the minimum pin granularity. Soft-deleted rows are excluded by the entity's {@code @SQLRestriction};
     * the {@code unique} index on {@code code} makes this an index-friendly single-row read.</p>
     *
     * @param code the official administrative code, already trimmed (compared case-insensitively).
     * @param type the required hierarchy level (e.g. {@link LocationType#WARD}).
     * @return the matching location's public id, or empty if none/soft-deleted.
     */
    @Query("SELECT l.publicId FROM Location l WHERE lower(l.code) = lower(:code) AND l.type = :type")
    Optional<UUID> findPublicIdByCodeAndType(@Param("code") String code, @Param("type") LocationType type);

    /**
     * Lists all locations at a given level, paged.
     *
     * @param type     the hierarchy level (e.g. {@link LocationType#REGION}).
     * @param pageable paging/sorting.
     * @return a page of locations at that level.
     */
    Page<Location> findByType(LocationType type, Pageable pageable);

    /**
     * Lists the immediate children of a location, optionally constrained to a child level, paged.
     *
     * <p>WHY filter by parent's {@code publicId} (a join) rather than passing the internal id:
     * controllers only ever hold the public id; pushing the resolution into the query keeps the
     * service simple and avoids an extra round-trip.</p>
     *
     * @param parentPublicId the parent location's public id.
     * @param pageable       paging/sorting.
     * @return a page of immediate children.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT c FROM Location c WHERE c.parent.publicId = :parentPublicId")
    Page<Location> findChildrenByParentPublicId(@Param("parentPublicId") UUID parentPublicId, Pageable pageable);

    /**
     * Lists the immediate children of a location restricted to one level (e.g. a region's districts).
     *
     * @param parentPublicId the parent location's public id.
     * @param childType      the required child level.
     * @param pageable       paging/sorting.
     * @return a page of immediate children of the given level.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT c FROM Location c WHERE c.parent.publicId = :parentPublicId AND c.type = :childType")
    Page<Location> findChildrenByParentPublicIdAndType(@Param("parentPublicId") UUID parentPublicId,
                                                       @Param("childType") LocationType childType,
                                                       Pageable pageable);

    /**
     * Lists every {@code WARD} under a district — its <b>transitive</b> ward descendants via the closure
     * table — projecting each with its Council/LGA and District ancestor names, paged.
     *
     * <p>WHY the closure table (not {@code parent}): a ward's direct parent is a {@code COUNCIL} (and an
     * optional {@code DIVISION} may sit between), so a district→wards listing is a transitive
     * ancestor/descendant query, not a one-hop children read. Joining {@link LocationClosure} answers
     * "all wards under this district" in one indexed query (ARCHITECTURE.md §4.3) and the same join carries
     * the council/district names, avoiding an N+1 per ward.</p>
     *
     * <p>The scope filter is the {@code dc} closure row pinning the ward's {@code DISTRICT} ancestor to the
     * requested {@code districtPublicId}; the {@code cc} {@code LEFT JOIN} resolves the {@code COUNCIL}
     * ancestor name (nullable to tolerate an incomplete seed chain, EI-14). Soft-deleted locations are
     * excluded by the entity's {@code @SQLRestriction} on every {@code Location} alias.</p>
     *
     * @param districtPublicId the district's public id.
     * @param pageable         paging/sorting (the service applies a stable default sort by ward name).
     * @return a page of {@link WardSummaryProjection} for the district's wards.
     */
    @Query("""
            SELECT w.publicId        AS wardPublicId,
                   w.code            AS code,
                   w.name            AS name,
                   cc.ancestor.name  AS councilName,
                   dc.ancestor.name  AS districtName
            FROM Location w
            JOIN LocationClosure dc
                ON dc.descendant = w AND dc.ancestor.type = com.taarifu.geography.domain.model.enums.LocationType.DISTRICT
            LEFT JOIN LocationClosure cc
                ON cc.descendant = w AND cc.ancestor.type = com.taarifu.geography.domain.model.enums.LocationType.COUNCIL
            WHERE w.type = com.taarifu.geography.domain.model.enums.LocationType.WARD
              AND dc.ancestor.publicId = :districtPublicId
            """)
    Page<WardSummaryProjection> findWardSummariesUnderDistrict(@Param("districtPublicId") UUID districtPublicId,
                                                              Pageable pageable);

    /**
     * Case-insensitive <b>name-prefix</b> search over wards, optionally scoped to a district, projecting
     * each match with its Council/LGA and District ancestor names, paged.
     *
     * <p>Powers the {@code GET /wards?q=&districtId=} ward picker when GPS is unavailable. The
     * {@code districtPublicId} is optional: when {@code null} the search spans every ward nationally; when
     * supplied it is constrained, via the closure table, to wards under that district. As in
     * {@link #findWardSummariesUnderDistrict}, the council/district names are resolved by joining
     * {@link LocationClosure} so the result is a single round-trip with no N+1.</p>
     *
     * <p>WHY prefix (not substring/contains): a {@code name LIKE :prefix%} predicate is index-friendly and
     * matches how a user types a ward name into a picker, while a leading-wildcard {@code %term%} would force
     * a full scan at national scale (PRD §15). The caller passes an already-lowercased {@code namePrefix}
     * with the trailing {@code %} appended, matched against {@code lower(w.name)}.</p>
     *
     * @param namePrefix       the lowercased ward-name prefix with a trailing {@code %} (e.g. {@code "meng%"}).
     * @param districtPublicId optional district scope; {@code null} searches all wards.
     * @param pageable         paging/sorting (the service applies a stable default sort by ward name).
     * @return a page of {@link WardSummaryProjection} for the matching wards.
     */
    @Query("""
            SELECT w.publicId        AS wardPublicId,
                   w.code            AS code,
                   w.name            AS name,
                   cc.ancestor.name  AS councilName,
                   dc.ancestor.name  AS districtName
            FROM Location w
            LEFT JOIN LocationClosure dc
                ON dc.descendant = w AND dc.ancestor.type = com.taarifu.geography.domain.model.enums.LocationType.DISTRICT
            LEFT JOIN LocationClosure cc
                ON cc.descendant = w AND cc.ancestor.type = com.taarifu.geography.domain.model.enums.LocationType.COUNCIL
            WHERE w.type = com.taarifu.geography.domain.model.enums.LocationType.WARD
              AND lower(w.name) LIKE :namePrefix ESCAPE '\\'
              AND (:districtPublicId IS NULL OR dc.ancestor.publicId = :districtPublicId)
            """)
    Page<WardSummaryProjection> searchWardSummaries(@Param("namePrefix") String namePrefix,
                                                    @Param("districtPublicId") UUID districtPublicId,
                                                    Pageable pageable);
}
