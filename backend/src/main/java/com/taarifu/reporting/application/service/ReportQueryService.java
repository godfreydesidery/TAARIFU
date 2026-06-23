package com.taarifu.reporting.application.service;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.reporting.api.ReportQueryApi;
import com.taarifu.reporting.api.dto.AdminReportDetail;
import com.taarifu.reporting.api.dto.AdminReportPage;
import com.taarifu.reporting.api.dto.AdminReportQuery;
import com.taarifu.reporting.api.dto.AdminReportSummary;
import com.taarifu.reporting.api.dto.CaseEventDto;
import com.taarifu.reporting.api.dto.ReportScopeDto;
import com.taarifu.reporting.api.dto.ReportStatusCount;
import com.taarifu.reporting.application.mapper.ReportingMapper;
import com.taarifu.reporting.domain.model.CaseEvent;
import com.taarifu.reporting.domain.model.Report;
import com.taarifu.reporting.domain.model.enums.ReportStatus;
import com.taarifu.reporting.domain.repository.CaseEventRepository;
import com.taarifu.reporting.domain.repository.ReportRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reporting's implementation of the published {@link ReportQueryApi} — the synchronous cross-module read
 * seam used by {@code responders → reporting} (report-existence/scope validation, R-1) and
 * {@code admin → reporting} (the back-office report queue, case detail, and dashboard counts, M14)
 * (ADR-0013 §1, §4a; D21).
 *
 * <p>Responsibility: answer existence/scope questions and serve the admin console's PII-minimised reads
 * without any sibling importing reporting's internals. {@code @Transactional(readOnly = true)}; returns
 * only {@code void}/{@code boolean}, primitive counts, or PII-free published DTOs — no entity and no
 * reporter PII crosses the boundary (CLAUDE.md §8, PRD §18).</p>
 *
 * <p><b>Authorization:</b> this port performs no authorization. The admin controllers that call the admin
 * read methods are method-secured ({@code ADMIN}/{@code MODERATOR}); the responders callers gate on live
 * area/category scope (R-1). The port trusts the caller's gate (it is in-process, not a public surface).</p>
 */
@Service
@Transactional(readOnly = true)
public class ReportQueryService implements ReportQueryApi {

    /**
     * The terminal statuses — a closed/rejected/duplicate case is NOT an "open" case and can never be
     * SLA-breached. Single-sourced from {@link ReportStatus#isTerminal()} so the definition of "open"
     * here cannot drift from the state machine's (DRY).
     */
    private static final Set<ReportStatus> TERMINAL_STATUSES =
            EnumSet.copyOf(java.util.Arrays.stream(ReportStatus.values())
                    .filter(ReportStatus::isTerminal)
                    .toList());

    /** Admin queue ordering: newest-filed first (most recent intake at the top of the triage list). */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    /** Detail timeline ordering: newest event first (matches the citizen/owner timeline convention). */
    private static final Sort TIMELINE_NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    private final ReportRepository reportRepository;
    private final CaseEventRepository caseEventRepository;
    private final ReportingMapper mapper;
    private final ClockPort clock;

    /**
     * @param reportRepository    report persistence port (existence checks, admin queue, counts).
     * @param caseEventRepository case-timeline persistence port (full timeline for the staff detail).
     * @param mapper              entity→DTO translation (admin projections).
     * @param clock               time source for SLA-breach evaluation (testable; never {@code now()} inline).
     */
    public ReportQueryService(ReportRepository reportRepository,
                              CaseEventRepository caseEventRepository,
                              ReportingMapper mapper,
                              ClockPort clock) {
        this.reportRepository = reportRepository;
        this.caseEventRepository = caseEventRepository;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** {@inheritDoc} */
    @Override
    public void requireExists(UUID reportPublicId) {
        if (!exists(reportPublicId)) {
            throw new ResourceNotFoundException("reporting.report.notFound", reportPublicId);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean exists(UUID reportPublicId) {
        return reportPublicId != null && reportRepository.findByPublicId(reportPublicId).isPresent();
    }

    /** {@inheritDoc} */
    @Override
    public ReportScopeDto scopeOf(UUID reportPublicId) {
        Report report = (reportPublicId == null ? Optional.<Report>empty()
                : reportRepository.findByPublicId(reportPublicId))
                .orElseThrow(() -> new ResourceNotFoundException("reporting.report.notFound", reportPublicId));
        // The category is a required FK; the ward is a required column — both are always present on a
        // persisted report. Expose only the two ids for the responders area/category authz check (R-1).
        return new ReportScopeDto(report.getReporterWardId(), report.getCategory().getPublicId());
    }

    // ------------------------------ Admin console read surface (M14) ------------------------------

    /** {@inheritDoc} */
    @Override
    public AdminReportPage adminQuery(AdminReportQuery filter, int page, int size) {
        AdminReportQuery safeFilter = filter == null
                ? new AdminReportQuery(null, null, null, null)
                : filter;
        // A blank/unknown status name must yield an empty match (not a 500): if the client filtered on a
        // status that does not parse, short-circuit to an empty page rather than running the query.
        Optional<ReportStatus> statusFilter = parseStatus(safeFilter.status());
        if (safeFilter.status() != null && !safeFilter.status().isBlank() && statusFilter.isEmpty()) {
            return new AdminReportPage(List.of(), Math.max(page, 0), normalisedSize(size), 0L);
        }

        Instant now = clock.now();
        Pageable pageable = PageRequest.of(Math.max(page, 0), normalisedSize(size), NEWEST_FIRST);
        Page<Report> result = reportRepository.adminSearch(
                statusFilter.orElse(null),
                safeFilter.categoryId(),
                safeFilter.wardId(),
                safeFilter.slaBreached(),
                TERMINAL_STATUSES,
                now,
                pageable);

        List<AdminReportSummary> rows = result.getContent().stream()
                .map(r -> mapper.toAdminReportSummary(r, isBreached(r, now)))
                .toList();
        return new AdminReportPage(rows, result.getNumber(), result.getSize(), result.getTotalElements());
    }

    /** {@inheritDoc} */
    @Override
    public AdminReportDetail adminDetail(UUID reportPublicId) {
        Report report = (reportPublicId == null ? Optional.<Report>empty()
                : reportRepository.findByPublicIdWithCategory(reportPublicId))
                .orElseThrow(() -> new ResourceNotFoundException("reporting.report.notFound", reportPublicId));

        Instant now = clock.now();
        // Full timeline (public + internal) — the caller is an authorised operator (US-3.4). Cap the page
        // so a pathological case with thousands of events can't blow the response (the console paginates
        // the timeline separately if it ever needs to; the detail returns the most recent window).
        Pageable timelinePage = PageRequest.of(0, 200, TIMELINE_NEWEST_FIRST);
        List<CaseEventDto> timeline = caseEventRepository
                .findByReport_PublicId(report.getPublicId(), timelinePage)
                .map(this::toEventDto)
                .getContent();

        return mapper.toAdminReportDetail(report, isBreached(report, now), timeline);
    }

    /** {@inheritDoc} */
    @Override
    public List<ReportStatusCount> reportCountsByStatus() {
        return reportRepository.countByStatus().stream()
                .map(row -> new ReportStatusCount(row.getStatus().name(), row.getCount()))
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public long openCaseCount() {
        return reportRepository.countByStatusNotIn(TERMINAL_STATUSES);
    }

    /** {@inheritDoc} */
    @Override
    public long slaBreachedCount() {
        return reportRepository.countSlaBreached(clock.now(), TERMINAL_STATUSES);
    }

    /** Maps a timeline entity to its DTO (kept as a method ref target for the stream above). */
    private CaseEventDto toEventDto(CaseEvent event) {
        return mapper.toCaseEventDto(event);
    }

    /**
     * @return {@code true} if the report is still active (non-terminal) and its {@code dueAt} is strictly
     *         before {@code now} — the single definition of "SLA-breached" shared by the queue rows, the
     *         detail, and the dashboard count (so they never disagree).
     */
    private boolean isBreached(Report report, Instant now) {
        return report.getDueAt() != null
                && report.getDueAt().isBefore(now)
                && !report.getStatus().isTerminal();
    }

    /** Parses a status name leniently: blank/unknown → empty (the caller decides what an unknown means). */
    private Optional<ReportStatus> parseStatus(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ReportStatus.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /** Clamps the page size to {@code [1, 100]} as a defence-in-depth bound even though the caller caps. */
    private int normalisedSize(int size) {
        if (size < 1) {
            return 20;
        }
        return Math.min(size, 100);
    }
}
