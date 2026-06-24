package com.taarifu.reporting.application.service;

import com.taarifu.common.security.CurrentUser;
import com.taarifu.common.security.ScopeGuard;
import com.taarifu.reporting.domain.model.IssueCategory;
import com.taarifu.reporting.domain.model.Report;
import com.taarifu.reporting.domain.model.enums.ReportVisibility;
import com.taarifu.reporting.domain.repository.ReportRepository;
import com.taarifu.reporting.test.ReportingTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReportMediaAccessService} — the MF-2 report-visibility gate the media serve path
 * delegates to (IDOR / OWASP A01; PRD §25.3, §18).
 *
 * <p>Responsibility: proves the load-bearing visibility model with no Docker/DB — a PUBLIC report's
 * evidence is open to any authenticated caller; a PRIVATE (incl. forced-private sensitive) report's
 * evidence is viewable only by the reporter or an authorized in-scope staff principal; an anonymous
 * sensitive report's evidence is viewable by <b>no</b> citizen (only in-scope staff); and every fail-closed
 * path (unknown report, null inputs, out-of-scope responder, no staff role) returns {@code false}. Each
 * guard test is written so it would fail if the guard were removed.</p>
 */
@ExtendWith(MockitoExtension.class)
class ReportMediaAccessServiceTest {

    @Mock private ReportRepository reportRepository;
    @Mock private ScopeGuard scopeGuard;

    private ReportMediaAccessService service;

    private final UUID reporter = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ReportMediaAccessService(reportRepository, scopeGuard);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Places an authenticated principal with the given roles in the security context. */
    private void authenticateAs(UUID principal, String... roles) {
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, "n/a", List.of());
        ((UsernamePasswordAuthenticationToken) auth)
                .setDetails(new CurrentUser(principal, List.of(roles), "T1"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void stub(Report report) {
        when(reportRepository.findByPublicIdWithCategory(report.getPublicId()))
                .thenReturn(Optional.of(report));
    }

    // --------------------------------------------------------------------------------------------- PUBLIC

    @Test
    void publicReport_isViewableByAnyAuthenticatedCaller() {
        UUID stranger = UUID.randomUUID();
        authenticateAs(stranger, "CITIZEN");
        Report report = ReportingTestFixtures.report(reporter,
                ReportingTestFixtures.publicCategory("WAT"), ReportVisibility.PUBLIC);
        stub(report);

        assertThat(service.canViewReportMedia(report.getPublicId(), stranger)).isTrue();
        // A public report needs no scope check.
        verify(scopeGuard, never()).canActOnArea(any());
    }

    // -------------------------------------------------------------------------------------- PRIVATE: owner

    @Test
    void privateReport_isViewableByItsReporter() {
        authenticateAs(reporter, "CITIZEN");
        Report report = ReportingTestFixtures.report(reporter,
                ReportingTestFixtures.publicCategory("WAT"), ReportVisibility.PRIVATE);
        stub(report);

        assertThat(service.canViewReportMedia(report.getPublicId(), reporter)).isTrue();
    }

    @Test
    void privateReport_isNotViewableByAnUnrelatedCitizen() {
        // THE MF-2 IDOR GUARD: a stranger with no role/scope must be denied a private report's evidence.
        UUID stranger = UUID.randomUUID();
        authenticateAs(stranger, "CITIZEN");
        Report report = ReportingTestFixtures.report(reporter,
                ReportingTestFixtures.publicCategory("WAT"), ReportVisibility.PRIVATE);
        stub(report);

        assertThat(service.canViewReportMedia(report.getPublicId(), stranger)).isFalse();
    }

    // ---------------------------------------------------------------------------- PRIVATE: anonymous sensitive

    @Test
    void anonymousSensitiveReport_isViewableByNoCitizen() {
        // An anonymous filing has reporterProfileId == null — no citizen can ever match the reporter branch,
        // so a citizen caller (even by coincidence of id) is denied. Only in-scope staff (below) may view.
        UUID anyCitizen = UUID.randomUUID();
        authenticateAs(anyCitizen, "CITIZEN");
        Report report = ReportingTestFixtures.report(null,
                ReportingTestFixtures.sensitiveForcedPrivateCategory("COR"), ReportVisibility.PRIVATE);
        stub(report);

        assertThat(service.canViewReportMedia(report.getPublicId(), anyCitizen)).isFalse();
    }

    // -------------------------------------------------------------------------------- PRIVATE: platform staff

    @Test
    void privateReport_isViewableByModerator_withoutScopeCheck() {
        UUID moderator = UUID.randomUUID();
        authenticateAs(moderator, "MODERATOR");
        Report report = ReportingTestFixtures.report(reporter,
                ReportingTestFixtures.sensitiveForcedPrivateCategory("GBV"), ReportVisibility.PRIVATE);
        stub(report);

        assertThat(service.canViewReportMedia(report.getPublicId(), moderator)).isTrue();
        // Platform staff (moderator/admin) bypass the area/category scope gate.
        verify(scopeGuard, never()).canActOnArea(any());
    }

    // ---------------------------------------------------------------------------------- PRIVATE: scoped staff

    @Test
    void privateReport_isViewableByInScopeResponder() {
        UUID responder = UUID.randomUUID();
        authenticateAs(responder, "RESPONDER_AGENT");
        Report report = ReportingTestFixtures.report(reporter,
                ReportingTestFixtures.publicCategory("WAT"), ReportVisibility.PRIVATE);
        stub(report);
        when(scopeGuard.canActOnArea(report.getReporterWardId())).thenReturn(true);
        when(scopeGuard.canActOnCategory(report.getCategory().getPublicId())).thenReturn(true);

        assertThat(service.canViewReportMedia(report.getPublicId(), responder)).isTrue();
    }

    @Test
    void privateReport_isNotViewableByOutOfScopeResponder() {
        // A responder whose live scope does NOT cover the report's ward/category is denied (R-1 consistency).
        UUID responder = UUID.randomUUID();
        authenticateAs(responder, "RESPONDER_AGENT");
        Report report = ReportingTestFixtures.report(reporter,
                ReportingTestFixtures.publicCategory("WAT"), ReportVisibility.PRIVATE);
        stub(report);
        lenient().when(scopeGuard.canActOnArea(any())).thenReturn(false);
        lenient().when(scopeGuard.canActOnCategory(any())).thenReturn(false);

        assertThat(service.canViewReportMedia(report.getPublicId(), responder)).isFalse();
    }

    // ------------------------------------------------------------------------------------------- fail-closed

    @Test
    void unknownReport_isDenied() {
        UUID caller = UUID.randomUUID();
        authenticateAs(caller, "CITIZEN");
        UUID unknown = UUID.randomUUID();
        when(reportRepository.findByPublicIdWithCategory(unknown)).thenReturn(Optional.empty());

        assertThat(service.canViewReportMedia(unknown, caller)).isFalse();
    }

    @Test
    void nullReportId_isDenied_withoutAnyLookup() {
        assertThat(service.canViewReportMedia(null, UUID.randomUUID())).isFalse();
        verify(reportRepository, never()).findByPublicIdWithCategory(any());
    }

    @Test
    void nullCaller_isDenied_withoutAnyLookup() {
        assertThat(service.canViewReportMedia(UUID.randomUUID(), null)).isFalse();
        verify(reportRepository, never()).findByPublicIdWithCategory(any());
    }
}
