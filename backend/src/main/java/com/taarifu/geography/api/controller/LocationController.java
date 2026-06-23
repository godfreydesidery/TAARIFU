package com.taarifu.geography.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.geography.api.dto.LocationDto;
import com.taarifu.geography.api.dto.LocationResolutionDto;
import com.taarifu.geography.application.service.GeographyQueryService;
import com.taarifu.geography.application.service.LocationResolutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Public, read-only REST surface for generic location lookup and GPS resolution
 * (PRD §11 M1, §9.0; FOUNDATION-SCOPE.md §4).
 *
 * <p>Responsibility: the thin HTTP layer for {@code /locations} reads — fetch a location by id and the
 * first-class "resolve a pin to both geographies" endpoint. Delegates to the application services and
 * wraps results in the single {@link ApiResponse} envelope. No business logic, no {@code @Transactional}
 * (CLAUDE.md §8).</p>
 *
 * <p>WHY {@code /locations/resolve} is public and unauthenticated: "find my representative" must work
 * for Guests on a feature phone (PRD §22.2/§22.6) — it is the platform's front door. The
 * {@code @PreAuthorize("permitAll()")} makes that an explicit, reviewed decision (ARCHITECTURE.md §6.2).</p>
 */
@RestController
@RequestMapping(path = "/locations")
@Tag(name = "Geography", description = "Public civic-geography reference reads (Swahili-first).")
public class LocationController {

    private final GeographyQueryService queryService;
    private final LocationResolutionService resolutionService;
    private final ResponseFactory responses;

    /**
     * @param queryService      generic location lookup.
     * @param resolutionService GPS→ward + both-geography derivation.
     * @param responses         envelope builder.
     */
    public LocationController(GeographyQueryService queryService,
                             LocationResolutionService resolutionService,
                             ResponseFactory responses) {
        this.queryService = queryService;
        this.resolutionService = resolutionService;
        this.responses = responses;
    }

    /**
     * Fetches any administrative location by public id, including its parent reference.
     *
     * @param locationId the location's public id.
     * @return an envelope carrying the {@link LocationDto}.
     */
    @GetMapping("/{locationId}")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Get a location by id", description = "Any level: region…hamlet.")
    public ApiResponse<LocationDto> getLocation(@PathVariable UUID locationId) {
        return responses.ok(queryService.getLocation(locationId));
    }

    /**
     * Resolves a GPS coordinate to its ward plus the derived administrative chain and constituency.
     *
     * @param lat WGS84 latitude.
     * @param lng WGS84 longitude.
     * @return an envelope carrying the {@link LocationResolutionDto}; {@code resolved = false} when the
     *         point matched no ward boundary (client uses manual drill-down — EI-7).
     */
    @GetMapping("/resolve")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Resolve GPS to ward + admin chain + constituency",
            description = "Pin-a-place → derive both geographies. Public; degrades to manual drill-down.")
    public ApiResponse<LocationResolutionDto> resolve(
            @RequestParam double lat,
            @RequestParam double lng) {
        return responses.ok(resolutionService.resolveByGps(lat, lng));
    }
}
