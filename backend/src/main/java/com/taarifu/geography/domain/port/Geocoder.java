package com.taarifu.geography.domain.port;

import com.taarifu.geography.domain.model.Location;

import java.util.Optional;

/**
 * Outbound port resolving a GPS coordinate to the containing Ward (Kata) — PRD §9.0 EI-7,
 * ADR-0004, ARCHITECTURE.md §7.
 *
 * <p>Responsibility: abstracts "which ward is this lat/lng in?". The <b>in-house PostGIS
 * point-in-polygon</b> implementation against seeded ward boundaries is the <b>source of truth</b>
 * and is vendor-independent — civic routing must never hard-depend on a third-party geocoder
 * (PRD §9.0). External map/geocoding services, if ever added, are display hints only, behind this same
 * port.</p>
 *
 * <p>Degradation (EI-7): when no boundary contains the point (or geometry is unseeded), the resolver
 * returns empty and the API degrades to manual ward drill-down — the citizen path never fails because
 * a GPS point missed a polygon.</p>
 *
 * <p>WHY a port with a stub: per ADR-0004 every external/optional capability has an interface plus a
 * stub adapter, so the whole system boots and tests run with zero geometry seeded ({@code StubGeocoder}).</p>
 */
public interface Geocoder {

    /**
     * Resolves a coordinate to its containing ward.
     *
     * @param latitude  WGS84 latitude (SRID 4326).
     * @param longitude WGS84 longitude (SRID 4326).
     * @return the containing {@link Location} of type {@code WARD}, or empty if no boundary contains
     *         the point (caller degrades to manual drill-down — EI-7).
     */
    Optional<Location> resolveWard(double latitude, double longitude);
}
