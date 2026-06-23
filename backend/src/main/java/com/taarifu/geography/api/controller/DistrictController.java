package com.taarifu.geography.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.pagination.PageMapper;
import com.taarifu.common.pagination.PageRequestFactory;
import com.taarifu.geography.api.dto.WardSummaryDto;
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

/**
 * Public, read-only REST surface for a district's wards (PRD §9.0, §11 M1, §22.6).
 *
 * <p>Responsibility: the thin HTTP layer for {@code GET /districts/{districtPublicId}/wards} — the
 * <b>manual ward-picker</b> listing that mobile/web offer when GPS is unavailable (both clients flagged
 * that report forms, profile-locations, and find-my-rep currently take a ward UUID by hand). It validates
 * paging input, delegates to {@link GeographyQueryService}, and wraps the result in the single
 * {@link ApiResponse} envelope. No business logic, no {@code @Transactional} (CLAUDE.md §8).</p>
 *
 * <p>WHY {@code @PreAuthorize("permitAll()")}: civic-geography reference reads are intentionally public so
 * a Guest on a feature phone can pick a ward without an account (PRD §11/§22.6). Stating it at the method
 * keeps "public" an explicit, reviewed decision (deny-by-default everywhere else — ARCHITECTURE.md §6.2).
 * The URL also sits on the {@code /api/v1/districts/**} GET allow-list in {@code SecurityConfig}.</p>
 *
 * <p>WHY a dedicated {@code /districts} controller (not another method on {@code GeographyController}'s
 * {@code /regions}): the listing is rooted at a district, so REST-resource-wise it belongs under
 * {@code /districts}; keeping it here mirrors {@code ConstituencyController}/{@code LocationController}'s
 * one-controller-per-root-resource split (single responsibility).</p>
 */
@RestController
@RequestMapping(path = "/districts")
@Tag(name = "Geography", description = "Public civic-geography reference reads (Swahili-first).")
public class DistrictController {

    private final GeographyQueryService queryService;
    private final ResponseFactory responses;
    private final PageRequestFactory pageRequests;
    private final PageMapper pageMapper;

    /**
     * @param queryService district→wards listing reads.
     * @param responses    envelope builder.
     * @param pageRequests safe {@link Pageable} factory (size caps).
     * @param pageMapper   {@code Page}→{@code PageMeta} adapter.
     */
    public DistrictController(GeographyQueryService queryService,
                              ResponseFactory responses,
                              PageRequestFactory pageRequests,
                              PageMapper pageMapper) {
        this.queryService = queryService;
        this.responses = responses;
        this.pageRequests = pageRequests;
        this.pageMapper = pageMapper;
    }

    /**
     * Lists the wards (Kata) under a district (Wilaya), paged — the transitive ward descendants resolved
     * through the closure table, each with its parent council and district names.
     *
     * @param districtId the district's public id.
     * @param page       zero-based page index.
     * @param size       page size (capped at 100).
     * @param sort       sort expression {@code field,asc|desc}; defaults to ward name ascending.
     * @return a paged envelope of {@link WardSummaryDto}.
     */
    @GetMapping("/{districtId}/wards")
    @PreAuthorize("permitAll()")
    @Operation(summary = "List a district's wards (Kata)",
            description = "Manual ward picker: all wards under a district, paged. Public; GPS-free.")
    public ApiResponse<List<WardSummaryDto>> listWards(
            @PathVariable UUID districtId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        // Default to a stable, human-friendly order (ward name) when the client gives no sort, so the
        // picker list is deterministic across pages.
        Pageable pageable = pageRequests.of(page, size, sort == null ? "name,asc" : sort);
        Page<WardSummaryDto> result = queryService.listWardsInDistrict(districtId, pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }
}
