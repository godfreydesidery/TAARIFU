package com.taarifu.reporting.application.service;

import com.taarifu.common.security.CurrentUser;
import com.taarifu.common.security.ScopeGuard;
import com.taarifu.reporting.api.ReportMediaAccessApi;
import com.taarifu.reporting.domain.model.Report;
import com.taarifu.reporting.domain.model.enums.ReportVisibility;
import com.taarifu.reporting.domain.repository.ReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reporting's implementation of the {@link ReportMediaAccessApi} visibility port (ADR-0013 §4a; MF-2,
 * OWASP A01). It is the host-side authority the media serve path delegates to when deciding whether a
 * caller may obtain a pre-signed download for evidence bound to a report.
 *
 * <p>Responsibility: apply the report-visibility model (see {@link ReportMediaAccessApi}) using the report
 * the media is bound to, returning a bare boolean. It performs no I/O beyond the report lookup and the
 * live-scope check; it returns no PII (data minimisation, PRD §18). The whole method is <b>fail-closed</b>:
 * any path that does not affirmatively grant returns {@code false}.</p>
 *
 * <p><b>WHY a separate read-only {@code @Service} (not folded into {@link ReportQueryService}):</b> keeps
 * the cross-module visibility port a small single-responsibility class with its own transaction semantics
 * (mirrors {@link ReportSubjectAuthorQuery}), and avoids the admin-read service taking a dependency on the
 * {@link ScopeGuard} authz seam it does not otherwise need (SRP, KISS).</p>
 */
@Service
@Transactional(readOnly = true)
public class ReportMediaAccessService implements ReportMediaAccessApi {

    /**
     * Platform-wide staff roles whose mandate is not geography/category scoped: a moderator handles
     * sensitive/GBV/corruption queues (the moderation pipeline, PRD §18/§25), and an admin/root is the
     * operational super-user. For these, holding the role is sufficient to view a private report's evidence
     * — they do not pass through the area/category scope gate. (Responder/representative roles are scoped
     * and go through {@link ScopeGuard} instead, below.)
     */
    private static final Set<String> PLATFORM_STAFF_ROLES = Set.of("MODERATOR", "ADMIN", "ROOT");

    private final ReportRepository reportRepository;
    private final ScopeGuard scopeGuard;

    /**
     * @param reportRepository the report persistence port (resolve the bound report by public id).
     * @param scopeGuard       the live role × scope authz seam ({@code taarifuAuthz}); reads the caller's
     *                         current {@code RoleAssignment} area/category scope from the DB, never the token.
     */
    public ReportMediaAccessService(ReportRepository reportRepository, ScopeGuard scopeGuard) {
        this.reportRepository = reportRepository;
        this.scopeGuard = scopeGuard;
    }

    /** {@inheritDoc} */
    @Override
    public boolean canViewReportMedia(UUID reportPublicId, UUID callerAccountId) {
        if (reportPublicId == null || callerAccountId == null) {
            return false; // fail-closed: no report or no caller → no access.
        }
        Optional<Report> found = reportRepository.findByPublicIdWithCategory(reportPublicId);
        if (found.isEmpty()) {
            return false; // unknown/soft-deleted report → deny (treat as not viewable).
        }
        Report report = found.get();

        // PUBLIC report: its evidence is no more sensitive than the publicly discoverable report itself.
        if (report.getVisibility() == ReportVisibility.PUBLIC) {
            return true;
        }

        // PRIVATE (incl. forced-private sensitive/GBV/corruption): reporter OR authorized in-scope staff.
        // (a) The reporter of a non-anonymous report. An anonymous filing has reporterProfileId == null,
        //     so this branch can never match for any citizen — only staff (below) can ever view it.
        if (callerAccountId.equals(report.getReporterProfileId())) {
            return true;
        }
        // (b) An authorized staff principal in scope for THIS report.
        return isAuthorizedStaffViewer(report);
    }

    /**
     * Decides whether the current authenticated principal is an authorized <i>staff</i> viewer of a private
     * report's evidence.
     *
     * <p>Two staff classes, both re-resolved live (never from the token claim):</p>
     * <ul>
     *   <li><b>Platform-wide staff</b> ({@code MODERATOR}/{@code ADMIN}/{@code ROOT}) — role alone suffices;
     *       moderators own the sensitive/GBV queues and admins are operational super-users.</li>
     *   <li><b>Scoped staff</b> ({@code RESPONDER_AGENT}/{@code RESPONDER_ADMIN}/{@code REPRESENTATIVE})
     *       — must additionally pass the live area+category scope gate for this report's ward and category
     *       ({@link ScopeGuard}, R-1). A responder may only see evidence for cases inside their assigned
     *       area/category, exactly as their case-lifecycle actions are gated.</li>
     * </ul>
     *
     * <p>Deny-by-default: a caller with no staff role, or a scoped role whose live scope does not cover the
     * report, returns {@code false}.</p>
     *
     * @param report the (private) report whose evidence is being requested.
     * @return {@code true} if the current principal is an authorized staff viewer for this report.
     */
    private boolean isAuthorizedStaffViewer(Report report) {
        Optional<CurrentUser> current = CurrentUser.current();
        if (current.isEmpty()) {
            return false; // no authenticated principal in context → deny.
        }
        var roles = current.get().roles();
        if (roles == null) {
            return false;
        }
        // Platform-wide staff: role alone grants access to the sensitive/private evidence queues.
        if (roles.stream().anyMatch(PLATFORM_STAFF_ROLES::contains)) {
            return true;
        }
        // Scoped staff: must hold a scoped staff role AND pass the live area+category scope gate (R-1).
        boolean hasScopedStaffRole = roles.contains("RESPONDER_AGENT")
                || roles.contains("RESPONDER_ADMIN")
                || roles.contains("REPRESENTATIVE");
        if (!hasScopedStaffRole) {
            return false;
        }
        return scopeGuard.canActOnArea(report.getReporterWardId())
                && scopeGuard.canActOnCategory(report.getCategory().getPublicId());
    }
}
