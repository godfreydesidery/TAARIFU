package com.taarifu.institutions.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.pagination.PageMapper;
import com.taarifu.common.pagination.PageRequestFactory;
import com.taarifu.institutions.api.dto.PoliticalPartyDto;
import com.taarifu.institutions.application.service.InstitutionsQueryService;
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
 * Public, read-only REST surface for the political-party directory (PRD §9.1, §22.6; UC-C07).
 *
 * <p>Responsibility: the thin HTTP layer for party reads — list/search and fetch-by-id — wrapped in the
 * single {@link ApiResponse} envelope. No business logic, no {@code @Transactional} (CLAUDE.md §8). Public
 * reference reads; path must be added to the security public-GET allow-list (see CENTRAL INTEGRATION
 * NEEDS).</p>
 */
@RestController
@RequestMapping(path = "/parties")
@Tag(name = "Institutions", description = "Public party & parliament reference directory (Swahili-first).")
public class PartyController {

    private final InstitutionsQueryService queryService;
    private final ResponseFactory responses;
    private final PageRequestFactory pageRequests;
    private final PageMapper pageMapper;

    /**
     * @param queryService institutions read service.
     * @param responses    envelope builder.
     * @param pageRequests safe {@link Pageable} factory.
     * @param pageMapper   {@code Page}→{@code PageMeta} adapter.
     */
    public PartyController(InstitutionsQueryService queryService,
                           ResponseFactory responses,
                           PageRequestFactory pageRequests,
                           PageMapper pageMapper) {
        this.queryService = queryService;
        this.responses = responses;
        this.pageRequests = pageRequests;
        this.pageMapper = pageMapper;
    }

    /**
     * Lists/searches political parties, paged.
     *
     * @param q    optional free-text term over name/abbreviation.
     * @param page zero-based page index.
     * @param size page size (capped at 100).
     * @param sort sort expression.
     * @return a paged envelope of {@link PoliticalPartyDto}.
     */
    @GetMapping
    @PreAuthorize("permitAll()")
    @Operation(summary = "List/search political parties")
    public ApiResponse<List<PoliticalPartyDto>> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<PoliticalPartyDto> result = queryService.listParties(q, pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    /**
     * Fetches a single party by public id.
     *
     * @param partyId the party's public id.
     * @return an envelope carrying the {@link PoliticalPartyDto}.
     */
    @GetMapping("/{partyId}")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Get a political party by id")
    public ApiResponse<PoliticalPartyDto> get(@PathVariable UUID partyId) {
        return responses.ok(queryService.getParty(partyId));
    }
}
