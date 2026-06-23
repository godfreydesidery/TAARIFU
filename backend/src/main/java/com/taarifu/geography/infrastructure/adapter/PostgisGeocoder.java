package com.taarifu.geography.infrastructure.adapter;

import com.taarifu.geography.domain.model.Location;
import com.taarifu.geography.domain.model.enums.LocationType;
import com.taarifu.geography.domain.port.Geocoder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Primary {@link Geocoder} adapter: in-house <b>PostGIS point-in-polygon</b> against seeded ward
 * boundaries — the source of truth for GPS→ward (PRD §9.0 EI-7, ADR-0004, ARCHITECTURE.md §7).
 *
 * <p>Responsibility: given a coordinate, finds the {@code WARD} {@link Location} whose {@code boundary}
 * polygon contains the point, using a native PostGIS {@code ST_Contains(boundary, ST_SetSRID(ST_Point
 * (lng,lat),4326))} query. Returns empty when no boundary contains the point, so the caller degrades
 * to manual drill-down (EI-7).</p>
 *
 * <p>WHY native SQL (not JPQL): point-in-polygon is a PostGIS spatial predicate with no JPQL
 * equivalent; per ADR-0004 the vendor/tech-specific query is quarantined in this {@code infrastructure
 * .adapter} class behind the {@link Geocoder} port, so the domain stays geometry-library-agnostic.</p>
 *
 * <p>WHY {@code @ConditionalOnProperty} with default-on: selecting the active adapter by config lets a
 * dev/test profile swap in {@link StubGeocoder} with zero geometry seeded (ADR-0004), while production
 * uses this PostGIS implementation by default.</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.geography.geocoder", havingValue = "postgis", matchIfMissing = true)
public class PostgisGeocoder implements Geocoder {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Resolves the containing ward via a PostGIS spatial query.
     *
     * @param latitude  WGS84 latitude.
     * @param longitude WGS84 longitude.
     * @return the containing ward, or empty if no boundary contains the point.
     */
    @Override
    public Optional<Location> resolveWard(double latitude, double longitude) {
        // Parameters are bound (never string-concatenated) → injection-safe. Note PostGIS point order
        // is (longitude, latitude). Only WARD rows carry boundaries, but we filter by type defensively.
        @SuppressWarnings("unchecked")
        var results = entityManager.createNativeQuery(
                        """
                        SELECT * FROM location
                        WHERE type = :wardType
                          AND boundary IS NOT NULL
                          AND deleted = false
                          AND ST_Contains(boundary, ST_SetSRID(ST_Point(:lng, :lat), 4326))
                        LIMIT 1
                        """, Location.class)
                .setParameter("wardType", LocationType.WARD.name())
                .setParameter("lng", longitude)
                .setParameter("lat", latitude)
                .getResultList();
        return results.isEmpty() ? Optional.empty() : Optional.of((Location) results.get(0));
    }
}
