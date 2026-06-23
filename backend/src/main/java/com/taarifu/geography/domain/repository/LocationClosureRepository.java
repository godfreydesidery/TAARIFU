package com.taarifu.geography.domain.repository;

import com.taarifu.geography.domain.model.Location;
import com.taarifu.geography.domain.model.LocationClosure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data repository over the {@link LocationClosure} closure table (ARCHITECTURE.md §4.3).
 *
 * <p>Responsibility: answers ancestor/descendant queries in one indexed lookup — the performance
 * reason the closure table exists (PRD §15). Used by the resolution service to derive a pinned
 * place's full administrative chain (Region→…→Ward) without recursive walking.</p>
 */
public interface LocationClosureRepository extends JpaRepository<LocationClosure, Long> {

    /**
     * Returns the full ancestor chain of a location, nearest first.
     *
     * <p>The closure stores {@code (ancestor, descendant, depth)}; selecting all rows whose
     * {@code descendant} is the target, ordered by descending {@code depth}, yields the ancestors from
     * the top of the hierarchy down — excluding the self-pair ({@code depth = 0}).</p>
     *
     * @param descendantPublicId the public id of the location whose ancestors are wanted.
     * @return the ancestor {@link Location}s ordered top-down (Region first), empty for a region.
     */
    @Query("""
            SELECT lc.ancestor FROM LocationClosure lc
            WHERE lc.descendant.publicId = :descendantPublicId AND lc.depth > 0
            ORDER BY lc.depth DESC
            """)
    List<Location> findAncestors(@Param("descendantPublicId") UUID descendantPublicId);

    /**
     * Returns all descendants of a location at any depth (e.g. every ward under a district).
     *
     * @param ancestorPublicId the public id of the ancestor location.
     * @return the descendant {@link Location}s (excluding the self-pair).
     */
    @Query("""
            SELECT lc.descendant FROM LocationClosure lc
            WHERE lc.ancestor.publicId = :ancestorPublicId AND lc.depth > 0
            ORDER BY lc.depth ASC
            """)
    List<Location> findDescendants(@Param("ancestorPublicId") UUID ancestorPublicId);
}
