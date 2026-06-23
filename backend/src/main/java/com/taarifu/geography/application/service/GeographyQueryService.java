package com.taarifu.geography.application.service;

import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.geography.api.dto.ConstituencyDto;
import com.taarifu.geography.api.dto.DistrictDto;
import com.taarifu.geography.api.dto.LocationDto;
import com.taarifu.geography.api.dto.RegionDto;
import com.taarifu.geography.application.mapper.GeographyMapper;
import com.taarifu.geography.domain.model.Constituency;
import com.taarifu.geography.domain.model.Location;
import com.taarifu.geography.domain.model.enums.LocationType;
import com.taarifu.geography.domain.repository.ConstituencyRepository;
import com.taarifu.geography.domain.repository.LocationRepository;
import com.taarifu.geography.domain.repository.WardConstituencyRepository;
import com.taarifu.common.domain.port.ClockPort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read-only application service for the geography hierarchy and electoral mapping
 * (FOUNDATION-SCOPE.md §4, ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: orchestrates hierarchy lookups (regions, a region's districts, a location +
 * its parentage) and constituency-with-current-wards reads, returning <b>DTOs</b> (never entities).
 * It owns the transaction boundary; methods are read-only ({@code @Transactional(readOnly = true)}).</p>
 *
 * <p>WHY a service (not query logic in the controller): controllers stay thin and free of business
 * logic (CLAUDE.md §8); the service is the seam where the effective-dated bridge, mapping, and
 * not-found semantics live, and where transactions are demarcated.</p>
 *
 * <p>All public lookups are by {@code publicId}; a miss throws {@link ResourceNotFoundException} with a
 * resource-specific i18n key so the citizen gets a Swahili-first message (ADR-0010).</p>
 */
@Service
@Transactional(readOnly = true)
public class GeographyQueryService {

    private final LocationRepository locationRepository;
    private final ConstituencyRepository constituencyRepository;
    private final WardConstituencyRepository wardConstituencyRepository;
    private final GeographyMapper mapper;
    private final ClockPort clock;

    /**
     * @param locationRepository         location persistence port.
     * @param constituencyRepository     constituency persistence port.
     * @param wardConstituencyRepository effective-dated bridge port.
     * @param mapper                     entity→DTO mapper.
     * @param clock                      injectable "today" for effective-date resolution (testability).
     */
    public GeographyQueryService(LocationRepository locationRepository,
                                 ConstituencyRepository constituencyRepository,
                                 WardConstituencyRepository wardConstituencyRepository,
                                 GeographyMapper mapper,
                                 ClockPort clock) {
        this.locationRepository = locationRepository;
        this.constituencyRepository = constituencyRepository;
        this.wardConstituencyRepository = wardConstituencyRepository;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * Lists all regions, paged.
     *
     * @param pageable bounded paging/sorting from {@code PageRequestFactory}.
     * @return a page of {@link RegionDto}.
     */
    public Page<RegionDto> listRegions(Pageable pageable) {
        return locationRepository.findByType(LocationType.REGION, pageable).map(mapper::toRegionDto);
    }

    /**
     * Lists the districts of a region, paged.
     *
     * @param regionPublicId the region's public id.
     * @param pageable       paging/sorting.
     * @return a page of {@link DistrictDto}.
     * @throws ResourceNotFoundException if no region with that id exists.
     */
    public Page<DistrictDto> listDistricts(UUID regionPublicId, Pageable pageable) {
        Location region = requireLocation(regionPublicId);
        if (region.getType() != LocationType.REGION) {
            throw new ResourceNotFoundException("geography.region.notFound", regionPublicId);
        }
        return locationRepository
                .findChildrenByParentPublicIdAndType(regionPublicId, LocationType.DISTRICT, pageable)
                .map(mapper::toDistrictDto);
    }

    /**
     * Fetches a single location by public id, with its parent reference resolved into the DTO.
     *
     * @param publicId the location's public id.
     * @return the {@link LocationDto}.
     * @throws ResourceNotFoundException if not found/soft-deleted.
     */
    public LocationDto getLocation(UUID publicId) {
        return mapper.toLocationDto(requireLocation(publicId));
    }

    /**
     * Fetches a constituency and its <b>current</b> member wards (resolved as of today through the
     * effective-dated bridge).
     *
     * @param publicId the constituency's public id.
     * @return the {@link ConstituencyDto} including current wards.
     * @throws ResourceNotFoundException if not found/soft-deleted.
     */
    public ConstituencyDto getConstituency(UUID publicId) {
        Constituency constituency = constituencyRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("geography.constituency.notFound", publicId));
        LocalDate today = LocalDate.ofInstant(clock.now(), java.time.ZoneOffset.UTC);
        var currentWards = wardConstituencyRepository.findCurrentWards(publicId, today);
        return mapper.toConstituencyDto(constituency, currentWards);
    }

    /** Loads a location by public id or throws a localised not-found. */
    private Location requireLocation(UUID publicId) {
        return locationRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("geography.location.notFound", publicId));
    }
}
