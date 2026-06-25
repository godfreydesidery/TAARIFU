package com.taarifu.search.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.pagination.PageMapper;
import com.taarifu.common.pagination.PageRequestFactory;
import com.taarifu.search.api.dto.SearchResultDto;
import com.taarifu.search.application.service.SearchQueryService;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The cross-entity discovery endpoint — one keyword search over representatives, organisations/responders,
 * announcements, issue-categories, and public reports (ADR-0017 §4; PRD discovery).
 *
 * <p>Responsibility: the thin REST surface over {@link SearchQueryService}. It validates the paging inputs,
 * delegates, and wraps the ranked page in the single {@link ApiResponse} envelope — no business logic, no
 * {@code @Transactional} (CLAUDE.md §8, ARCHITECTURE §3.3).</p>
 *
 * <p><b>Authorization</b> ({@code permitAll()} + server-side visibility gate): discovery of the <b>public</b>
 * civic graph is open to any reader, including unauthenticated guests (like {@code /representatives/**},
 * {@code /announcements/*}). The private/sensitive tier is NOT exposed by an authentication check at the URL —
 * it is gated <b>server-side</b> in {@link SearchQueryService}: {@code STAFF}-visibility rows are added to the
 * result set only when the authenticated caller actually holds a staff role, so a guest/citizen simply never
 * sees them (filtered out, never 403'd per row — anti-enumeration, PRD §18). The {@code /search/**} GET pattern
 * must be centrally allow-listed in {@code SecurityConfig.PUBLIC_GET_PATTERNS} so a guest can reach it — this
 * module must not edit {@code common.security} (see CENTRAL INTEGRATION NEEDS). Until then the
 * {@code @PreAuthorize("permitAll()")} permits the handler, but the URL filter still requires the central
 * pattern registration to be reachable unauthenticated.</p>
 */
@RestController
@RequestMapping("/search")
@Tag(name = "Search", description = "Cross-entity discovery over reps, organisations, announcements, "
        + "categories, and public reports (Postgres full-text).")
public class SearchController {

    private final SearchQueryService searchQueryService;
    private final ResponseFactory responses;
    private final PageRequestFactory pageRequests;
    private final PageMapper pageMapper;

    /**
     * @param searchQueryService the ranked, visibility-gated FTS query service.
     * @param responses          envelope builder.
     * @param pageRequests       safe {@link org.springframework.data.domain.Pageable} factory (size capped).
     * @param pageMapper         {@code Page}→{@code PageMeta} adapter.
     */
    public SearchController(SearchQueryService searchQueryService,
                           ResponseFactory responses,
                           PageRequestFactory pageRequests,
                           PageMapper pageMapper) {
        this.searchQueryService = searchQueryService;
        this.responses = responses;
        this.pageRequests = pageRequests;
        this.pageMapper = pageMapper;
    }

    /**
     * Runs a cross-entity discovery search, ranked by relevance.
     *
     * @param q          the user's free-text query (Swahili or English); a blank query returns an empty page
     *                   (a search is keyword-driven — the whole corpus is never enumerated).
     * @param type       optional {@link SearchEntityType} filter (e.g. only {@code REPRESENTATIVE}), or all.
     * @param areaId     optional Ward-or-coarser area public id filter (PRD §9.0), or {@code null}.
     * @param categoryId optional issue-category public id filter, or {@code null}.
     * @param page       zero-based page index.
     * @param size       page size (capped at 100 by the factory).
     * @return a paged envelope of ranked {@link SearchResultDto}, most relevant first.
     */
    @GetMapping
    @PreAuthorize("permitAll()")
    @Operation(summary = "Cross-entity discovery search",
            description = "Public read. Matches keywords across reps/orgs/announcements/categories/public "
                    + "reports; filter by type/area/category. Private/sensitive rows are returned only to "
                    + "staff (server-side visibility gate); blank query → empty page.")
    public ApiResponse<List<SearchResultDto>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) SearchEntityType type,
            @RequestParam(required = false) UUID areaId,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        // Sort is fixed to relevance (ts_rank) inside the query, so no client sort param is accepted.
        var pageable = pageRequests.of(page, size, null);
        Page<SearchResultDto> result = searchQueryService.search(q, type, areaId, categoryId, pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }
}
