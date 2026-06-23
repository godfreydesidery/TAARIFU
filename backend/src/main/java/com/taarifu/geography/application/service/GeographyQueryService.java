package com.taarifu.geography.application.service;

import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.geography.api.dto.ConstituencyDto;
import com.taarifu.geography.api.dto.DistrictDto;
import com.taarifu.geography.api.dto.LocationDto;
import com.taarifu.geography.api.dto.RegionDto;
import com.taarifu.geography.application.mapper.GeographyMapper;
import com.taarifu.geography.domain.model.Constituency;
import com.taarifu.geography.domain.model.Location;
import com.taarifu.geography.domain.model.WardConstituency;
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

    /**
     * Resolves a ward (by public id) to the entity pair another module needs to <b>pin</b> a location:
     * the ward {@link Location} and the {@link Constituency} in effect today (via the effective-dated
     * bridge), or {@code null} constituency if the ward has no current mapping.
     *
     * <p>WHY this is geography's public API (not a cross-module repository reach-around): the
     * {@code identity} module's {@code ProfileLocation} FK-references these geography entities (the
     * documented persistence exception, ARCHITECTURE §4.3), but it must resolve them through geography's
     * own service so the boundary holds at the application layer (CLAUDE.md §8). The minimum pin
     * granularity is a Ward (PRD §9.0); a non-ward public id is rejected as not-found.</p>
     *
     * @param wardPublicId the ward's public id.
     * @return the ward + effective constituency entity pair.
     * @throws ResourceNotFoundException if no ward with that id exists (or it is not a WARD).
     */
    @Transactional(readOnly = true)
    public WardPin resolveWardPin(UUID wardPublicId) {
        Location ward = requireLocation(wardPublicId);
        if (ward.getType() != LocationType.WARD) {
            throw new ResourceNotFoundException("geography.ward.notFound", wardPublicId);
        }
        LocalDate today = LocalDate.ofInstant(clock.now(), java.time.ZoneOffset.UTC);
        Constituency constituency = wardConstituencyRepository.findEffectiveMapping(wardPublicId, today)
                .map(WardConstituency::getConstituency)
                .orElse(null);
        return new WardPin(ward, constituency);
    }

    /**
     * Resolves a constituency (by public id) to its <b>entity</b>, for a sibling module that legitimately
     * FK-references geography (ARCHITECTURE §3.2, §4.3) — e.g. {@code institutions} attaching a
     * constituency to a {@code Representative}.
     *
     * <p>WHY this is geography's public API (and the caller must NOT inject {@code ConstituencyRepository}):
     * the cross-module FK resolution must pass through geography's own application service so the closed
     * module boundary holds at the application layer (CLAUDE.md §8; the same discipline as
     * {@link #resolveWardPin}). The returned entity is a foundation-module type the caller may FK; it never
     * exposes geography's repositories/internals to the caller.</p>
     *
     * @param constituencyPublicId the constituency's public id.
     * @return the {@link Constituency} entity.
     * @throws ResourceNotFoundException if no constituency with that id exists/soft-deleted.
     */
    @Transactional(readOnly = true)
    public Constituency resolveConstituency(UUID constituencyPublicId) {
        return constituencyRepository.findByPublicId(constituencyPublicId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("geography.constituency.notFound", constituencyPublicId));
    }

    /**
     * Resolves a <b>ward</b> (by public id) to its {@link Location} entity for a sibling's FK use, enforcing
     * the minimum-pin-granularity rule (the location must be a {@code WARD}, PRD §9.0). Mirrors
     * {@link #resolveConstituency} — the sanctioned application-layer FK-resolution seam.
     *
     * @param wardPublicId the ward's public id.
     * @return the ward {@link Location} entity.
     * @throws ResourceNotFoundException if no ward with that id exists or it is not a {@code WARD}.
     */
    @Transactional(readOnly = true)
    public Location resolveWard(UUID wardPublicId) {
        Location ward = requireLocation(wardPublicId);
        if (ward.getType() != LocationType.WARD) {
            throw new ResourceNotFoundException("geography.ward.notFound", wardPublicId);
        }
        return ward;
    }

    /** Loads a location by public id or throws a localised not-found. */
    private Location requireLocation(UUID publicId) {
        return locationRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("geography.location.notFound", publicId));
    }

    /**
     * A ward and its effective constituency, as entity references for FK use by another module's pin.
     *
     * @param ward         the ward {@link Location} (minimum pin granularity, PRD §9.0).
     * @param constituency the constituency in effect today, or {@code null} if unmapped.
     */
    public record WardPin(Location ward, Constituency constituency) {
    }
}
