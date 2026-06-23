package com.taarifu.geography.application.mapper;

import com.taarifu.geography.api.dto.ConstituencyDto;
import com.taarifu.geography.api.dto.CouncilDto;
import com.taarifu.geography.api.dto.DistrictDto;
import com.taarifu.geography.api.dto.LocationDto;
import com.taarifu.geography.api.dto.RegionDto;
import com.taarifu.geography.api.dto.VillageDto;
import com.taarifu.geography.api.dto.WardDto;
import com.taarifu.geography.domain.model.Constituency;
import com.taarifu.geography.domain.model.Location;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps geography entities to their boundary DTOs (ARCHITECTURE.md §3.3, CLAUDE.md §8).
 *
 * <p>Responsibility: the single translation layer from {@link Location}/{@link Constituency} entities
 * to the {@code api.dto} records, ensuring <b>entities never leave the module</b> (CLAUDE.md §8) and
 * that only the {@code publicId} is exposed, never the internal {@code Long id} (ADR-0006).</p>
 *
 * <p>WHY a hand-written {@code @Component} mapper (not MapStruct here): the mappings are trivial,
 * level-discriminating, and benefit from explicit, documented null-handling (e.g. a region has no
 * parent). Keeping it hand-written avoids an annotation-processor dependency in the foundation slice
 * while staying within the architecture's mapper-layer contract; later modules may adopt MapStruct
 * (ARCHITECTURE.md §2). All access to {@code parent} is null-safe.</p>
 */
@Component
public class GeographyMapper {

    /**
     * @param location any location.
     * @return the generic {@link LocationDto}; {@code null} in/out is handled by the caller.
     */
    public LocationDto toLocationDto(Location location) {
        return new LocationDto(
                location.getPublicId(),
                location.getCode(),
                location.getName(),
                location.getType().name(),
                location.getStatus().name(),
                location.getParent() != null ? location.getParent().getPublicId() : null);
    }

    /** @param location a {@code REGION} location. @return the region DTO. */
    public RegionDto toRegionDto(Location location) {
        return new RegionDto(location.getPublicId(), location.getCode(), location.getName());
    }

    /** @param location a {@code DISTRICT} location. @return the district DTO with its region id. */
    public DistrictDto toDistrictDto(Location location) {
        return new DistrictDto(location.getPublicId(), location.getCode(), location.getName(),
                location.getParent() != null ? location.getParent().getPublicId() : null);
    }

    /** @param location a {@code COUNCIL} location. @return the council DTO with its district id. */
    public CouncilDto toCouncilDto(Location location) {
        return new CouncilDto(location.getPublicId(), location.getCode(), location.getName(),
                location.getParent() != null ? location.getParent().getPublicId() : null);
    }

    /** @param location a {@code WARD} location. @return the ward DTO with its parent id. */
    public WardDto toWardDto(Location location) {
        return new WardDto(location.getPublicId(), location.getCode(), location.getName(),
                location.getParent() != null ? location.getParent().getPublicId() : null);
    }

    /** @param location a {@code VILLAGE}/{@code MTAA} location. @return the village/mtaa DTO. */
    public VillageDto toVillageDto(Location location) {
        return new VillageDto(location.getPublicId(), location.getCode(), location.getName(),
                location.getType().name(),
                location.getParent() != null ? location.getParent().getPublicId() : null);
    }

    /**
     * Maps a constituency plus its resolved current wards.
     *
     * @param constituency the constituency entity.
     * @param currentWards the wards currently mapped to it (from the effective-dated bridge).
     * @return the constituency DTO including its current wards.
     */
    public ConstituencyDto toConstituencyDto(Constituency constituency, List<Location> currentWards) {
        List<WardDto> wardDtos = currentWards.stream().map(this::toWardDto).toList();
        return new ConstituencyDto(
                constituency.getPublicId(),
                constituency.getCode(),
                constituency.getName(),
                constituency.getDistrict() != null ? constituency.getDistrict().getPublicId() : null,
                wardDtos);
    }
}
