package com.taarifu.search.application.service;

import com.taarifu.common.security.CurrentUser;
import com.taarifu.search.application.mapper.SearchMapper;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import com.taarifu.search.domain.repository.SearchDocumentRepository;
import com.taarifu.search.domain.repository.SearchResultProjection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SearchQueryService} — the server-side <b>visibility gate</b> in code (ADR-0017 §4;
 * PRD §18).
 *
 * <p>Responsibility: prove the load-bearing security decision <b>without</b> Docker — that a guest/citizen
 * query passes {@code includeStaff = false} (so {@code STAFF} rows are filtered out of the result set), a
 * staff query passes {@code includeStaff = true}, and a blank query short-circuits to an empty page without
 * ever hitting the corpus. Each test would fail if its guard were removed (CLAUDE.md §10 — test the
 * invariant, not the happy path).</p>
 */
@ExtendWith(MockitoExtension.class)
class SearchQueryServiceTest {

    @Mock
    private SearchDocumentRepository repository;

    private final SearchMapper mapper = new SearchMapper();

    private SearchQueryService service() {
        return new SearchQueryService(repository, mapper);
    }

    private final Pageable pageable = PageRequest.of(0, 20);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Authenticates a principal with the given roles into the security context (the test "logs in"). */
    private void authenticateWithRoles(List<String> roles) {
        CurrentUser principal = new CurrentUser(UUID.randomUUID(), roles, "T1");
        var auth = new UsernamePasswordAuthenticationToken(principal.publicId(), null, List.of());
        auth.setDetails(principal);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void guestSearch_passesIncludeStaffFalse_soStaffRowsAreFilteredOut() {
        // No authentication in context → a guest.
        when(repository.search(eq("maji"), any(), any(), any(), eq(false), any()))
                .thenReturn(Page.empty(pageable));

        service().search("maji", null, null, null, pageable);

        // The load-bearing assertion: a guest's query is gated to PUBLIC-only (includeStaff = false).
        verify(repository).search(eq("maji"), any(), any(), any(), eq(false), any());
    }

    @Test
    void citizenSearch_passesIncludeStaffFalse_evenWhenAuthenticated() {
        authenticateWithRoles(List.of("CITIZEN"));
        when(repository.search(eq("barabara"), any(), any(), any(), eq(false), any()))
                .thenReturn(Page.empty(pageable));

        service().search("barabara", null, null, null, pageable);

        // An ordinary citizen is NOT staff → still PUBLIC-only. (If the role check leaked, this would be true.)
        verify(repository).search(eq("barabara"), any(), any(), any(), eq(false), any());
    }

    @Test
    void staffSearch_passesIncludeStaffTrue_soStaffVisibilityRowsAreReturned() {
        authenticateWithRoles(List.of("MODERATOR"));
        when(repository.search(eq("maji"), any(), any(), any(), eq(true), any()))
                .thenReturn(Page.empty(pageable));

        service().search("maji", null, null, null, pageable);

        // The staff gate opens the STAFF tier (includeStaff = true).
        verify(repository).search(eq("maji"), any(), any(), any(), eq(true), any());
    }

    @Test
    void blankQuery_returnsEmptyPage_withoutQueryingTheCorpus() {
        Page<?> result = service().search("   ", null, null, null, pageable);

        assertThat(result.getContent()).isEmpty();
        // A blank query must NEVER enumerate the corpus (PRD §15/§18) — the repository is never called.
        verify(repository, never()).search(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    void typeFilter_isPassedAsEnumName_andResultsAreMapped() {
        authenticateWithRoles(List.of("ADMIN"));
        SearchResultProjection hit = projection(SearchEntityType.REPRESENTATIVE, "Diwani Kata ya Mji");
        when(repository.search(eq("diwani"), eq("REPRESENTATIVE"), any(), any(), eq(true), any()))
                .thenReturn(new PageImpl<>(List.<SearchResultProjection>of(hit), pageable, 1));

        var page = service().search("diwani", SearchEntityType.REPRESENTATIVE, null, null, pageable);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).entityType()).isEqualTo(SearchEntityType.REPRESENTATIVE);
        assertThat(page.getContent().get(0).title()).isEqualTo("Diwani Kata ya Mji");
    }

    /** Builds a stub projection row with the given type/title and arbitrary other fields. */
    private SearchResultProjection projection(SearchEntityType type, String title) {
        return new SearchResultProjection() {
            @Override public String getEntityType() { return type.name(); }
            @Override public UUID getEntityPublicId() { return UUID.randomUUID(); }
            @Override public String getTitle() { return title; }
            @Override public String getSnippetSw() { return "kuhusu maji"; }
            @Override public String getSnippetEn() { return "about water"; }
            @Override public UUID getAreaId() { return null; }
            @Override public UUID getCategoryId() { return null; }
            @Override public double getRank() { return 0.5; }
        };
    }
}
