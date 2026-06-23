package com.taarifu.geography.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.geography.api.dto.ConstituencyDto;
import com.taarifu.geography.application.service.GeographyQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Public, read-only REST surface for constituencies (Majimbo) — PRD §11 M1, §9.0;
 * FOUNDATION-SCOPE.md §4.
 *
 * <p>Responsibility: the thin HTTP layer for {@code /constituencies} reads. Fetches a constituency plus
 * its <b>current</b> member wards (resolved through the effective-dated bridge), wrapped in the single
 * {@link ApiResponse} envelope. No business logic, no {@code @Transactional} (CLAUDE.md §8).</p>
 */
@RestController
@RequestMapping(path = "/constituencies")
@Tag(name = "Geography", description = "Public civic-geography reference reads (Swahili-first).")
public class ConstituencyController {

    private final GeographyQueryService queryService;
    private final ResponseFactory responses;

    /**
     * @param queryService constituency-with-current-wards reads.
     * @param responses    envelope builder.
     */
    public ConstituencyController(GeographyQueryService queryService, ResponseFactory responses) {
        this.queryService = queryService;
        this.responses = responses;
    }

    /**
     * Fetches a constituency and its current member wards.
     *
     * @param constituencyId the constituency's public id.
     * @return an envelope carrying the {@link ConstituencyDto} including current wards.
     */
    @GetMapping("/{constituencyId}")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Get a constituency (Jimbo) + current wards")
    public ApiResponse<ConstituencyDto> getConstituency(@PathVariable UUID constituencyId) {
        return responses.ok(queryService.getConstituency(constituencyId));
    }
}
