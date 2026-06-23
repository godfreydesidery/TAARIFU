package com.taarifu.geography.application.service;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.geography.api.dto.ConstituencyDto;
import com.taarifu.geography.api.dto.LocationResolutionDto;
import com.taarifu.geography.application.mapper.GeographyMapper;
import com.taarifu.geography.domain.model.Location;
import com.taarifu.geography.domain.model.WardConstituency;
import com.taarifu.geography.domain.repository.LocationClosureRepository;
import com.taarifu.geography.domain.repository.WardConstituencyRepository;
import com.taarifu.geography.domain.port.Geocoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Resolves a pinned place (GPS or a chosen ward) into <b>both</b> the administrative chain and the
 * electoral constituency (PRD §9.0, §11 M1 "find my representative").
 *
 * <p>Responsibility: the use-case behind {@code GET /locations/resolve}. Given a coordinate, it uses
 * the {@link Geocoder} port (in-house PostGIS, source of truth) to find the containing ward, then the
 * {@link LocationClosureRepository} to derive the ward's full ancestor chain, and the effective-dated
 * {@link WardConstituencyRepository} to find the constituency in effect today. The citizen never types
 * a constituency — the system derives it (PRD §9.0).</p>
 *
 * <p>WHY it degrades instead of failing (EI-7): if the geocoder returns no ward (GPS missed every
 * boundary, or geometry is unseeded), the service returns {@code resolved = false} so the client falls
 * back to manual ward drill-down; if a ward resolves but has no current constituency mapping, the
 * admin chain is still returned with a {@code null} constituency. Civic routing never hard-depends on
 * geometry or a third party.</p>
 */
@Service
@Transactional(readOnly = true)
public class LocationResolutionService {

    private final Geocoder geocoder;
    private final LocationClosureRepository closureRepository;
    private final WardConstituencyRepository wardConstituencyRepository;
    private final GeographyMapper mapper;
    private final ClockPort clock;

    /**
     * @param geocoder                   GPS→ward port (PostGIS primary, stub fallback).
     * @param closureRepository          closure-table port for the ancestor chain.
     * @param wardConstituencyRepository effective-dated electoral mapping port.
     * @param mapper                     entity→DTO mapper.
     * @param clock                      injectable "today" for effective-date resolution.
     */
    public LocationResolutionService(Geocoder geocoder,
                                     LocationClosureRepository closureRepository,
                                     WardConstituencyRepository wardConstituencyRepository,
                                     GeographyMapper mapper,
                                     ClockPort clock) {
        this.geocoder = geocoder;
        this.closureRepository = closureRepository;
        this.wardConstituencyRepository = wardConstituencyRepository;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * Resolves a GPS coordinate to a ward and both derived geographies.
     *
     * @param latitude  WGS84 latitude.
     * @param longitude WGS84 longitude.
     * @return the resolution result; {@code resolved = false} when no ward contains the point (the
     *         client then uses manual drill-down — EI-7).
     */
    public LocationResolutionDto resolveByGps(double latitude, double longitude) {
        Optional<Location> ward = geocoder.resolveWard(latitude, longitude);
        if (ward.isEmpty()) {
            // EI-7 degradation: unresolved → client falls back to manual ward selection.
            return new LocationResolutionDto(false, null, List.of(), null);
        }
        return resolveForWard(ward.get());
    }

    /**
     * Builds the full resolution payload for an already-known ward (shared by GPS and manual paths).
     *
     * @param ward the resolved ward location.
     * @return the resolution result with admin chain and current constituency.
     */
    private LocationResolutionDto resolveForWard(Location ward) {
        // Administrative chain: the ward's ancestors top-down (Region→…→Council/Division).
        List<Location> ancestors = closureRepository.findAncestors(ward.getPublicId());
        var adminChain = ancestors.stream().map(mapper::toLocationDto).toList();

        // Electoral mapping: the constituency in effect today (effective-dated bridge).
        LocalDate today = LocalDate.ofInstant(clock.now(), ZoneOffset.UTC);
        ConstituencyDto constituencyDto = wardConstituencyRepository
                .findEffectiveMapping(ward.getPublicId(), today)
                .map(WardConstituency::getConstituency)
                .map(constituency -> mapper.toConstituencyDto(constituency, List.of()))
                .orElse(null);

        return new LocationResolutionDto(true, mapper.toWardDto(ward), adminChain, constituencyDto);
    }
}
