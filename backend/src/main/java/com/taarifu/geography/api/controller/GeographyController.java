package com.taarifu.geography.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.pagination.PageMapper;
import com.taarifu.common.pagination.PageRequestFactory;
import com.taarifu.geography.api.dto.ConstituencyDto;
import com.taarifu.geography.api.dto.DistrictDto;
import com.taarifu.geography.api.dto.LocationDto;
import com.taarifu.geography.api.dto.RegionDto;
import com.taarifu.geography.application.service.GeographyQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
// NOTE: GPS resolution lives in LocationController (/locations/resolve); this controller
// handles region/district hierarchy reads only.

/**
 * Public, read-only REST surface for civic geography (PRD §11 M1, §22.6; FOUNDATION-SCOPE.md §4).
 *
 * <p>Responsibility: the thin HTTP layer for the geography slice — it validates input, delegates to
 * the application services, and wraps every result in the single {@link ApiResponse} envelope via
 * {@link ResponseFactory}. It contains <b>no business logic</b> and <b>no</b> {@code @Transactional}
 * (CLAUDE.md §8, ARCHITECTURE.md §3.3).</p>
 *
 * <p>WHY {@code @PreAuthorize("permitAll()")} on every method (rather than relying on URL config
 * alone): reference reads are intentionally public (PRD §11/§22.6), and stating it at the method
 * establishes the deny-by-default, method-security pattern that every later controller follows
 * (ARCHITECTURE.md §6.2) — so "public" is an explicit decision in code, not an omission.</p>
 *
 * <p>Endpoints are under {@code /api/v1} (the context path is configured in {@code application.yml});
 * all ids in paths are public {@code UUID}s, never internal numeric ids (ADR-0006).</p>
 */
@RestController
@RequestMapping(path = "/regions")
@Tag(name = "Geography", description = "Public civic-geography reference reads (Swahili-first).")
public class GeographyController {

    private final GeographyQueryService queryService;
    private final ResponseFactory responses;
    private final PageRequestFactory pageRequests;
    private final PageMapper pageMapper;

    /**
     * @param queryService hierarchy reads (regions, districts, single location).
     * @param responses    envelope builder.
     * @param pageRequests safe {@link Pageable} factory (size caps).
     * @param pageMapper   {@code Page}→{@code PageMeta} adapter.
     */
    public GeographyController(GeographyQueryService queryService,
                               ResponseFactory responses,
                               PageRequestFactory pageRequests,
                               PageMapper pageMapper) {
        this.queryService = queryService;
        this.responses = responses;
        this.pageRequests = pageRequests;
        this.pageMapper = pageMapper;
    }

    /**
     * Lists all regions (Mikoa), paged.
     *
     * @param page zero-based page index.
     * @param size page size (capped at 100).
     * @param sort sort expression {@code field,asc|desc}.
     * @return a paged envelope of {@link RegionDto}.
     */
    @GetMapping
    @PreAuthorize("permitAll()")
    @Operation(summary = "List regions (Mikoa)", description = "Public, paged list of all regions.")
    public ApiResponse<List<RegionDto>> listRegions(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<RegionDto> result = queryService.listRegions(pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    /**
     * Fetches a single region by public id (alias of the generic location lookup, scoped to regions).
     *
     * @param regionId the region's public id.
     * @return an envelope carrying the region as a {@link LocationDto}.
     */
    @GetMapping("/{regionId}")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Get a region by id")
    public ApiResponse<LocationDto> getRegion(@PathVariable UUID regionId) {
        return responses.ok(queryService.getLocation(regionId));
    }

    /**
     * Lists the districts (Wilaya) of a region, paged.
     *
     * @param regionId the parent region's public id.
     * @param page     zero-based page index.
     * @param size     page size (capped at 100).
     * @param sort     sort expression.
     * @return a paged envelope of {@link DistrictDto}.
     */
    @GetMapping("/{regionId}/districts")
    @PreAuthorize("permitAll()")
    @Operation(summary = "List a region's districts (Wilaya)")
    public ApiResponse<List<DistrictDto>> listDistricts(
            @PathVariable UUID regionId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<DistrictDto> result = queryService.listDistricts(regionId, pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }
}
