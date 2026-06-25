package com.taarifu.search.application.service;

import com.taarifu.common.security.CurrentUser;
import com.taarifu.search.api.dto.SearchResultDto;
import com.taarifu.search.application.mapper.SearchMapper;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import com.taarifu.search.domain.repository.SearchDocumentRepository;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The read side of discovery — runs the ranked, filtered, <b>visibility-gated</b> full-text query behind
 * {@code GET /search} (ADR-0017 §4).
 *
 * <p>Responsibility: resolve the caller's visibility tier from the security context, run the FTS query with the
 * optional type/area/category filters, and map each hit to a locale-resolved {@link SearchResultDto}. It owns a
 * read-only transaction and returns DTOs, never entities.</p>
 *
 * <p><b>The security decision (server-side, the load-bearing guard):</b> the endpoint is {@code permitAll()} so
 * a guest may search the public civic graph — but {@code STAFF}-visibility rows (private/sensitive/in-flight)
 * are included <b>only</b> when the authenticated caller actually holds a staff role ({@code MODERATOR}/
 * {@code ADMIN}/{@code ROOT}). A guest or ordinary citizen therefore never sees a non-public row: it is
 * filtered out of the result set, not 403'd per row (which would be an enumeration oracle — PRD §18). Because
 * the gate is a query predicate driven by the live role set, removing the endpoint's {@code @PreAuthorize}
 * would NOT widen what a guest can discover — the visibility floor is enforced here, not at the URL.</p>
 */
@Service
@Transactional(readOnly = true)
public class SearchQueryService {

    /** Staff roles that may see {@code STAFF}-visibility rows in discovery (ADR-0017 §4; the staff hierarchy). */
    private static final Set<String> STAFF_ROLES = Set.of("MODERATOR", "ADMIN", "ROOT");

    private final SearchDocumentRepository repository;
    private final SearchMapper mapper;

    /**
     * @param repository the {@code search_document} FTS query port.
     * @param mapper     projection→DTO mapper (locale-resolved snippet).
     */
    public SearchQueryService(SearchDocumentRepository repository, SearchMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /**
     * Runs a discovery search.
     *
     * @param query      the raw user search text; a blank/absent query yields an empty page (a search needs a
     *                   term — we never return the whole corpus on an empty query).
     * @param entityType optional type filter, or {@code null} for all kinds.
     * @param areaId     optional area public id filter, or {@code null}.
     * @param categoryId optional category public id filter, or {@code null}.
     * @param pageable   bounded paging from {@code PageRequestFactory}; ordering is fixed to relevance.
     * @return a page of ranked {@link SearchResultDto}, most relevant first; empty when the query is blank.
     */
    public Page<SearchResultDto> search(String query, SearchEntityType entityType,
                                        UUID areaId, UUID categoryId, Pageable pageable) {
        if (!StringUtils.hasText(query)) {
            // No term → no results (deny-by-default for the corpus): a search is keyword-driven, and an empty
            // query must never enumerate every indexed row (PRD §15/§18).
            return Page.empty(pageable);
        }
        boolean includeStaff = callerIsStaff();
        String typeName = entityType != null ? entityType.name() : null;

        Page<SearchResultDto> page = repository
                .search(query.trim(), typeName, areaId, categoryId, includeStaff, pageable)
                .map(projection -> mapper.toResult(projection, currentLocale()));
        return page;
    }

    /**
     * Resolves whether the current authenticated principal holds a staff role — the gate for seeing
     * {@code STAFF}-visibility rows. A guest (no principal) is never staff.
     *
     * @return {@code true} if the caller holds {@code MODERATOR}/{@code ADMIN}/{@code ROOT}.
     */
    private boolean callerIsStaff() {
        List<String> roles = CurrentUser.current().map(CurrentUser::roles).orElse(List.of());
        for (String role : roles) {
            if (STAFF_ROLES.contains(role)) {
                return true;
            }
        }
        return false;
    }

    /** @return the request's resolved locale (SW default), driving the snippet language. */
    private Locale currentLocale() {
        return LocaleContextHolder.getLocale();
    }
}
