package com.taarifu.institutions.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.pagination.PageMapper;
import com.taarifu.common.pagination.PageRequestFactory;
import com.taarifu.institutions.api.dto.ParliamentDto;
import com.taarifu.institutions.api.dto.ParliamentRoleDto;
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
 * Public, read-only REST surface for the parliament directory and parliament-role catalogue
 * (PRD §9.1, §22.6; UC-C07).
 *
 * <p>Responsibility: the thin HTTP layer for parliament reads — list terms (optionally by legislature),
 * fetch a term, and list parliament roles — wrapped in the single {@link ApiResponse} envelope. No
 * business logic, no {@code @Transactional}. Public reference reads; path must be added to the security
 * public-GET allow-list (see CENTRAL INTEGRATION NEEDS).</p>
 */
@RestController
@RequestMapping(path = "/parliaments")
@Tag(name = "Institutions", description = "Public party & parliament reference directory (Swahili-first).")
public class ParliamentController {

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
    public ParliamentController(InstitutionsQueryService queryService,
                                ResponseFactory responses,
                                PageRequestFactory pageRequests,
                                PageMapper pageMapper) {
        this.queryService = queryService;
        this.responses = responses;
        this.pageRequests = pageRequests;
        this.pageMapper = pageMapper;
    }

    /**
     * Lists parliament terms, optionally filtered by legislature, paged.
     *
     * @param legislature optional legislature filter (UNION_PARLIAMENT/ZANZIBAR_HOR).
     * @param page        zero-based page index.
     * @param size        page size (capped at 100).
     * @param sort        sort expression.
     * @return a paged envelope of {@link ParliamentDto}.
     */
    @GetMapping
    @PreAuthorize("permitAll()")
    @Operation(summary = "List parliament terms")
    public ApiResponse<List<ParliamentDto>> list(
            @RequestParam(required = false) String legislature,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<ParliamentDto> result = queryService.listParliaments(legislature, pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    /**
     * Fetches a single parliament term by public id.
     *
     * @param parliamentId the term's public id.
     * @return an envelope carrying the {@link ParliamentDto}.
     */
    @GetMapping("/{parliamentId}")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Get a parliament term by id")
    public ApiResponse<ParliamentDto> get(@PathVariable UUID parliamentId) {
        return responses.ok(queryService.getParliament(parliamentId));
    }

    /**
     * Lists the parliament-role catalogue (Speaker, Minister, …), paged.
     *
     * @param page zero-based page index.
     * @param size page size (capped at 100).
     * @param sort sort expression.
     * @return a paged envelope of {@link ParliamentRoleDto}.
     */
    @GetMapping("/roles")
    @PreAuthorize("permitAll()")
    @Operation(summary = "List parliament roles")
    public ApiResponse<List<ParliamentRoleDto>> listRoles(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<ParliamentRoleDto> result = queryService.listParliamentRoles(pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }
}
