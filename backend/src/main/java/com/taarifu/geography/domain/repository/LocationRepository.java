package com.taarifu.geography.domain.repository;

import com.taarifu.geography.domain.model.Location;
import com.taarifu.geography.domain.model.enums.LocationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
