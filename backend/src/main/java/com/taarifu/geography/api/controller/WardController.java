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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Public, read-only REST surface for ward (Kata) search (PRD §9.0, §11 M1, §22.6).
 *
 * <p>Responsibility: the thin HTTP layer for {@code GET /wards?q=&districtId=} — the typed
 * <b>manual ward-picker</b> search mobile/web use when GPS is unavailable (report forms,
 * profile-locations, and find-my-rep currently take a ward UUID by hand). It validates paging input,
 * delegates to {@link GeographyQueryService}, and wraps the result in the single {@link ApiResponse}
 * envelope. No business logic, no {@code @Transactional} (CLAUDE.md §8).</p>
 *
 * <p>WHY {@code @PreAuthorize("permitAll()")}: like the rest of the civic-geography reference surface, the
 * picker must work for a Guest on a feature phone with no account (PRD §11/§22.6); the URL is on the
 * {@code /api/v1/wards/**} GET allow-list in {@code SecurityConfig}. Method-level {@code permitAll()} keeps
 * "public" explicit (ARCHITECTURE.md §6.2).</p>
 */
@RestController
@RequestMapping(path = "/wards")
@Tag(name = "Geography", description = "Public civic-geography reference reads (Swahili-first).")
public class WardController {

    private final GeographyQueryService queryService;
    private final ResponseFactory responses;
    private final PageRequestFactory pageRequests;
    private final PageMapper pageMapper;

    /**
     * @param queryService ward-search reads.
     * @param responses    envelope builder.
     * @param pageRequests safe {@link Pageable} factory (size caps).
     * @param pageMapper   {@code Page}→{@code PageMeta} adapter.
     */
    public WardController(GeographyQueryService queryService,
                          ResponseFactory responses,
                          PageRequestFactory pageRequests,
                          PageMapper pageMapper) {
        this.queryService = queryService;
        this.responses = responses;
        this.pageRequests = pageRequests;
        this.pageMapper = pageMapper;
    }

    /**
     * Searches wards (Kata) by name prefix, optionally scoped to a district, paged — each match carrying
     * its parent council and district names so the picker can disambiguate same-named wards.
     *
     * <p>A blank/absent {@code q} returns an empty page (a picker does not pull the whole national ward
     * table on an empty box, PRD §15). {@code districtId} narrows the search to one district when supplied.</p>
     *
     * @param q          the ward-name prefix the user typed (case-insensitive; {@code %}/{@code _} matched
     *                   literally); blank/absent yields an empty page.
     * @param districtId optional district public id to scope the search; absent searches all wards.
     * @param page       zero-based page index.
     * @param size       page size (capped at 100).
     * @param sort       sort expression {@code field,asc|desc}; defaults to ward name ascending.
     * @return a paged envelope of matching {@link WardSummaryDto}.
     */
    @GetMapping
    @PreAuthorize("permitAll()")
    @Operation(summary = "Search wards (Kata) by name",
            description = "Manual ward picker: name-prefix search, optionally district-scoped. Public; GPS-free.")
    public ApiResponse<List<WardSummaryDto>> searchWards(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID districtId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = pageRequests.of(page, size, sort == null ? "name,asc" : sort);
        Page<WardSummaryDto> result = queryService.searchWards(q, districtId, pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }
}
