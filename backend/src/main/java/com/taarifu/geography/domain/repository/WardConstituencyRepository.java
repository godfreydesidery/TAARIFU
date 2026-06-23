package com.taarifu.geography.domain.repository;

import com.taarifu.geography.domain.model.Location;
import com.taarifu.geography.domain.model.WardConstituency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository over the effective-dated {@link WardConstituency} bridge
 * (PRD §9.0, EI-14, ARCHITECTURE.md §4.3).
 *
 * <p>Responsibility: resolves "which constituency does this ward belong to, as of date D" — the core
 * electoral-mapping query behind "find my MP". Because mappings are effective-dated, resolution is
 * always date-scoped so historical records resolve to the constituency that existed at their time
 * (the integrity guarantee — re-delimitation never rewrites history).</p>
 */
public interface WardConstituencyRepository extends JpaRepository<WardConstituency, Long> {

    /**
     * Resolves the mapping effective on a given date for a ward identified by its public id.
     *
     * <p>WHY date-scoped, not "the latest row": a report or signature filed in the past must resolve
     * to the constituency in effect <i>then</i>. Passing {@code date = today} gives the current
     * mapping; passing an event's date gives the historically correct one (PRD §9.0).</p>
     *
     * @param wardPublicId the ward's public id.
     * @param date         the date at which to resolve the mapping.
     * @return the effective mapping, or empty if the ward has no mapping covering that date.
     */
    @Query("""
            SELECT wc FROM WardConstituency wc
            WHERE wc.ward.publicId = :wardPublicId
              AND wc.effectiveFrom <= :date
              AND (wc.effectiveTo IS NULL OR wc.effectiveTo > :date)
            """)
    Optional<WardConstituency> findEffectiveMapping(@Param("wardPublicId") UUID wardPublicId,
                                                    @Param("date") LocalDate date);

    /**
     * Lists the wards currently mapped to a constituency (the "current wards" of a constituency).
     *
     * @param constituencyPublicId the constituency's public id.
     * @param date                 the date at which to resolve membership (typically today).
     * @return the {@link Location} ward rows currently in the constituency.
     */
    @Query("""
            SELECT wc.ward FROM WardConstituency wc
            WHERE wc.constituency.publicId = :constituencyPublicId
              AND wc.effectiveFrom <= :date
              AND (wc.effectiveTo IS NULL OR wc.effectiveTo > :date)
            ORDER BY wc.ward.name ASC
            """)
    List<Location> findCurrentWards(@Param("constituencyPublicId") UUID constituencyPublicId,
                                    @Param("date") LocalDate date);
}
