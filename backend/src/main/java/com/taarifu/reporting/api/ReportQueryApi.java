package com.taarifu.reporting.api;

import com.taarifu.reporting.api.dto.AdminReportDetail;
import com.taarifu.reporting.api.dto.AdminReportPage;
import com.taarifu.reporting.api.dto.AdminReportQuery;
import com.taarifu.reporting.api.dto.AdminReportSummary;
import com.taarifu.reporting.api.dto.ReportScopeDto;
import com.taarifu.reporting.api.dto.ReportStatusCount;

import java.util.List;
import java.util.UUID;

/**
 * The reporting module's <b>public, in-process query port</b> for validating a report's existence
 * (ADR-0013 §1, §4a; D21) and for the back-office (admin console) <b>read</b> surface (M14). Siblings call
 * this synchronously ({@code responders → reporting}, {@code admin → reporting}) without importing
 * reporting's internals (ARCHITECTURE §3.2).
 *
 * <p>Responsibility: answer "does this report exist?" so a sibling never creates an assignment/relationship
 * against a non-existent report, "what is this report's administrative scope (ward + category)?" so a
 * sibling can run a horizontal-authorization check (R-1), and — for the admin console — the PII-minimised
 * owner-grade <b>queue</b>, <b>staff case detail</b>, and <b>headline counts</b> the dashboard needs. The
 * caller treats every result as opaque truth.</p>
 *
 * <p><b>Privacy (PRD §18 / PDPA):</b> the admin read methods return PII-minimised projections only — never
 * the reporter's profile id/phone/{@code idNo} and never the precise geo-point (ward-grained). The admin
 * controllers that call them are method-secured to {@code ADMIN}/{@code MODERATOR}; this port carries no
 * authorization of its own (it trusts the caller's gate), so callers MUST guard before invoking.</p>
 */
public interface ReportQueryApi {

    /**
     * Asserts the report exists (and is not soft-deleted), throwing if not.
     *
     * @param reportPublicId the report's public id.
     * @throws com.taarifu.common.error.ApiException with {@code NOT_FOUND} if no such report exists.
     */
    void requireExists(UUID reportPublicId);

    /**
     * @param reportPublicId the report's public id.
     * @return {@code true} if a non-deleted report with that id exists.
     */
    boolean exists(UUID reportPublicId);

    /**
     * Resolves the report's administrative scope — its ward (Kata) and issue category public ids — for a
     * sibling's horizontal-authorization check (R-1, ADR-0013 §1).
     *
     * <p>WHY this exists (R-1): the responders module's case-lifecycle endpoints (assign/start/resolve/
     * escalate) were role-gated only; an agent could drive <b>any</b> report's lifecycle regardless of their
     * {@code RoleAssignment} area/category scope. The responders side now resolves the report's ward +
     * category through this port and gates on {@code @taarifuAuthz.canActOnArea/canActOnCategory} (live
     * scope, never token). The port returns only the two PII-free ids ({@link ReportScopeDto}) — data
     * minimisation, no report content crosses the boundary.</p>
     *
     * @param reportPublicId the report's public id.
     * @return the report's ward + category public ids.
     * @throws com.taarifu.common.error.ApiException with {@code NOT_FOUND} if no such report exists.
     */
    ReportScopeDto scopeOf(UUID reportPublicId);

    // ------------------------------ Admin console read surface (M14) ------------------------------

    /**
     * Returns the owner-grade, PII-minimised report queue for the admin console, filtered and paginated
     * (M14; PRD §10 US-3.4, §24.3).
     *
     * <p>The {@code filter} dimensions are all optional (a {@code null} field is no constraint); an unknown
     * status name in the filter yields an empty match rather than an error. Rows are
     * {@link AdminReportSummary} projections — no reporter PII, no precise geo-point, no description body
     * (data minimisation, PRD §18). The {@code slaBreached} flag on each row and the queue's breach filter
     * are computed against "now" server-side. Ordering is newest-filed first.</p>
     *
     * <p>The caller (the admin controller) MUST be method-secured to {@code ADMIN}/{@code MODERATOR} — this
     * port performs no authorization of its own.</p>
     *
     * @param filter the optional filter dimensions (status/category/ward/SLA-breach); never {@code null}
     *               (pass an all-{@code null} {@link AdminReportQuery} for "no filter").
     * @param page   zero-based page index (the caller caps/normalises before calling).
     * @param size   page size (the caller caps before calling).
     * @return a transport-neutral page of queue rows (never {@code null}; empty content out of range).
     */
    AdminReportPage adminQuery(AdminReportQuery filter, int page, int size);

    /**
     * Returns the staff case-detail view of one report — including the <b>full</b> case timeline (public +
     * internal responder notes, US-3.4) — for the admin console (M14).
     *
     * <p>Returns no reporter PII and no precise geo-point (ward-grained); the internal timeline is included
     * because the caller is an authorised operator (the controller is {@code ADMIN}/{@code MODERATOR}
     * method-secured). The {@code slaBreached} flag is computed server-side against "now".</p>
     *
     * @param reportPublicId the report's public id.
     * @return the staff case detail.
     * @throws com.taarifu.common.error.ApiException with {@code NOT_FOUND} if no such report exists.
     */
    AdminReportDetail adminDetail(UUID reportPublicId);

    /**
     * Returns the count of (non-deleted) reports grouped by lifecycle status, for the admin dashboard
     * (M14, UC-H06). A status with no reports is omitted (the dashboard treats an absent status as zero).
     *
     * @return one {@link ReportStatusCount} per status that has at least one report (never {@code null}).
     */
    List<ReportStatusCount> reportCountsByStatus();

    /**
     * Returns the number of <b>open</b> cases — reports in a non-terminal status (i.e. excluding
     * {@code CLOSED}/{@code REJECTED}/{@code DUPLICATE}) — for the admin dashboard's "open cases" tile
     * (M14, UC-H06).
     *
     * @return the open-case count ({@code >= 0}).
     */
    long openCaseCount();

    /**
     * Returns the number of <b>SLA-breached</b> cases — still-active reports whose {@code dueAt} has passed
     * relative to "now" — for the admin dashboard (M14). Computed server-side so the dashboard and the
     * queue's {@code slaBreached} filter agree.
     *
     * @return the SLA-breached open-case count ({@code >= 0}).
     */
    long slaBreachedCount();
}
