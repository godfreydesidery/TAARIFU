package com.taarifu.institutions.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.pagination.PageMapper;
import com.taarifu.common.pagination.PageRequestFactory;
import com.taarifu.institutions.api.dto.MyRepresentativesDto;
import com.taarifu.institutions.api.dto.RepresentativeDto;
import com.taarifu.institutions.api.dto.RepresentativeSummaryDto;
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
 * Public, read-only REST surface for representatives — "find my representatives", profile, and the
 * directory/search (PRD §22.6 first-class flow; UC-C01, UC-C02, UC-C06).
 *
 * <p>Responsibility: the thin HTTP layer; it validates input, delegates to {@link
 * InstitutionsQueryService}, and wraps every result in the single {@link ApiResponse} envelope. No
 * business logic, no {@code @Transactional} (CLAUDE.md §8, ARCHITECTURE.md §3.3).</p>
 *
 * <p>WHY {@code @PreAuthorize("permitAll()")} on every method: "find my representative" and the public
 * directory must work for <b>Guests on a feature phone</b> — it is the platform's front door (PRD §22.6).
 * Stating it at the method makes "public" an explicit, reviewed decision, consistent with geography
 * (ARCHITECTURE.md §6.2). The path must also be registered in the security filter's public-GET allow-list
 * — see this module's CENTRAL INTEGRATION NEEDS.</p>
 */
@RestController
@RequestMapping(path = "/representatives")
@Tag(name = "Representatives", description = "Public representative directory, profile, and find-my-rep (Swahili-first).")
public class RepresentativeController {

    private final InstitutionsQueryService queryService;
    private final ResponseFactory responses;
    private final PageRequestFactory pageRequests;
    private final PageMapper pageMapper;

    /**
     * @param queryService institutions read service.
     * @param responses    envelope builder.
     * @param pageRequests safe {@link Pageable} factory (size caps).
     * @param pageMapper   {@code Page}→{@code PageMeta} adapter.
     */
    public RepresentativeController(InstitutionsQueryService queryService,
                                    ResponseFactory responses,
                                    PageRequestFactory pageRequests,
                                    PageMapper pageMapper) {
        this.queryService = queryService;
        this.responses = responses;
        this.pageRequests = pageRequests;
        this.pageMapper = pageMapper;
    }

    /**
     * Lists/searches representatives, paged. Optional filters: {@code type} (MP/COUNCILLOR/WARD_EXEC),
     * {@code status} (SITTING/FORMER/PENDING_VERIFICATION), {@code q} (free text over bio).
     *
     * <p>{@code FORMER} reps remain listable (badged historical, PRD §22.6).</p>
     *
     * @param type   optional type filter, or {@code null} for any.
     * @param status optional status filter, or {@code null} for any.
     * @param q      optional free-text search term.
     * @param page   zero-based page index.
     * @param size   page size (capped at 100).
     * @param sort   sort expression {@code field,asc|desc}.
     * @return a paged envelope of {@link RepresentativeSummaryDto}.
     */
    @GetMapping
    @PreAuthorize("permitAll()")
    @Operation(summary = "List/search representatives",
            description = "Public, paged directory; filter by type/status, search by free text. FORMER reps included (badged historical).")
    public ApiResponse<List<RepresentativeSummaryDto>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<RepresentativeSummaryDto> result = queryService.listRepresentatives(type, status, q, pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    /**
     * Fetches a single representative's full public profile (UC-C02).
     *
     * @param representativeId the representative's public id.
     * @return an envelope carrying the {@link RepresentativeDto}.
     */
    @GetMapping("/{representativeId}")
    @PreAuthorize("permitAll()")
    @Operation(summary = "View a representative profile")
    public ApiResponse<RepresentativeDto> get(@PathVariable UUID representativeId) {
        return responses.ok(queryService.getRepresentative(representativeId));
    }

    /**
     * Finds the representatives for a ward — the MP (via the ward's current constituency), the Councillor
     * (Diwani), and any ward/village executive officer (UC-C01).
     *
     * <p>WHY by ward id (not by GPS here): GPS→ward resolution is geography's {@code /locations/resolve}
     * endpoint; the citizen resolves their ward there (or picks it), then this endpoint returns their
     * reps. Keeping the two steps separate keeps each call lean for 2G (PRD §15) and lets a signed-in
     * citizen reuse their saved {@code isPrimary}/{@code isElectoral} ward without re-resolving GPS.</p>
     *
     * @param wardId the ward's public id (minimum pin granularity, PRD §9.0).
     * @return an envelope carrying the {@link MyRepresentativesDto} bundle.
     */
    @GetMapping("/by-ward/{wardId}")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Find my representatives (by ward)",
            description = "Returns MP (via Ward→Constituency) + Councillor (Diwani) + ward executive. Public; never hard-fails on a missing rep.")
    public ApiResponse<MyRepresentativesDto> findByWard(@PathVariable UUID wardId) {
        return responses.ok(queryService.findRepresentativesByWard(wardId));
    }
}
