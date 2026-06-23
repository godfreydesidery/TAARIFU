package com.taarifu.responders.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.pagination.PageMapper;
import com.taarifu.common.pagination.PageRequestFactory;
import com.taarifu.responders.api.dto.OrganisationDto;
import com.taarifu.responders.api.dto.ResponderDto;
import com.taarifu.responders.application.service.ResponderDirectoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Public, read-only REST surface for the <b>provider directory</b> — "who handles what" (PRD §24.1,
 * §24.3).
 *
 * <p>Responsibility: the thin HTTP layer for citizens to browse responders/organisations. It validates
 * input, delegates to {@link ResponderDirectoryService}, and wraps results in the single
 * {@link ApiResponse} envelope. No business logic, no {@code @Transactional} (CLAUDE.md §8).</p>
 *
 * <p>WHY {@code @PreAuthorize("permitAll()")}: §24.1 states the provider directory is public (citizens
 * can browse who handles what). Stating it at the method keeps "public" an explicit decision in code
 * under the deny-by-default method-security model (ARCHITECTURE.md §6.2). The service guarantees only
 * active+verified providers are ever returned, so making this public exposes no PENDING/unverified body.
 * NOTE: these paths ({@code /responders/**}, {@code /organisations/**}) must also be allow-listed for
 * unauthenticated GET in the central {@code SecurityConfig} — see CENTRAL INTEGRATION NEEDS in the PR.</p>
 *
 * <p>Endpoints are under {@code /api/v1} (context-path); ids are public {@code UUID}s.</p>
 */
@RestController
@Tag(name = "Provider Directory",
        description = "Public, read-only directory of responders and organisations (who handles what).")
public class ResponderDirectoryController {

    private final ResponderDirectoryService directory;
    private final ResponseFactory responses;
    private final PageRequestFactory pageRequests;
    private final PageMapper pageMapper;

    /**
     * @param directory    public directory reads.
     * @param responses    envelope builder.
     * @param pageRequests safe {@link Pageable} factory (size caps).
     * @param pageMapper   {@code Page}→{@code PageMeta} adapter.
     */
    public ResponderDirectoryController(ResponderDirectoryService directory,
                                        ResponseFactory responses,
                                        PageRequestFactory pageRequests,
                                        PageMapper pageMapper) {
        this.directory = directory;
        this.responses = responses;
        this.pageRequests = pageRequests;
        this.pageMapper = pageMapper;
    }

    /**
     * Lists publicly listable responders, optionally filtered by handled category, paged.
     *
     * @param categoryId optional reporting-category id ("who handles X?"); omit for all.
     * @param page       zero-based page index.
     * @param size       page size (capped at 100).
     * @param sort       sort expression {@code field,asc|desc}.
     * @return a paged envelope of {@link ResponderDto}.
     */
    @GetMapping("/responders")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Browse responders (who handles what)",
            description = "Public, paged directory of active+verified responders; filter by category.")
    public ApiResponse<List<ResponderDto>> listResponders(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<ResponderDto> result = directory.listPublicResponders(categoryId, pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    /**
     * Fetches a single publicly listable responder by public id.
     *
     * @param responderId the responder's public id.
     * @return an envelope carrying the {@link ResponderDto}.
     */
    @GetMapping("/responders/{responderId}")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Get a responder by id (public directory)")
    public ApiResponse<ResponderDto> getResponder(@PathVariable UUID responderId) {
        return responses.ok(directory.getPublicResponder(responderId));
    }

    /**
     * Lists publicly listable organisations, paged.
     *
     * @param page zero-based page index.
     * @param size page size (capped at 100).
     * @param sort sort expression.
     * @return a paged envelope of {@link OrganisationDto}.
     */
    @GetMapping("/organisations")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Browse responder organisations",
            description = "Public, paged directory of active+verified organisations.")
    public ApiResponse<List<OrganisationDto>> listOrganisations(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<OrganisationDto> result = directory.listPublicOrganisations(pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    /**
     * Fetches a single publicly listable organisation by public id.
     *
     * @param organisationId the organisation's public id.
     * @return an envelope carrying the {@link OrganisationDto}.
     */
    @GetMapping("/organisations/{organisationId}")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Get an organisation by id (public directory)")
    public ApiResponse<OrganisationDto> getOrganisation(@PathVariable UUID organisationId) {
        return responses.ok(directory.getPublicOrganisation(organisationId));
    }
}
