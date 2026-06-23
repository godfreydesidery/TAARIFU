package com.taarifu.reporting.application.service;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.common.persistence.CodeGenerator;
import com.taarifu.reporting.api.dto.CaseEventDto;
import com.taarifu.reporting.api.dto.FileReportDto;
import com.taarifu.reporting.api.dto.PublicReportDto;
import com.taarifu.reporting.api.dto.ReportDto;
import com.taarifu.reporting.application.mapper.ReportingMapper;
import com.taarifu.reporting.domain.model.CaseEvent;
import com.taarifu.reporting.domain.model.IssueCategory;
import com.taarifu.reporting.domain.model.Report;
import com.taarifu.reporting.domain.model.enums.CaseEventType;
import com.taarifu.reporting.domain.model.enums.ReportPriority;
import com.taarifu.reporting.domain.model.enums.ReportStatus;
import com.taarifu.reporting.domain.model.enums.ReportVisibility;
import com.taarifu.reporting.domain.port.WardResolver;
import com.taarifu.reporting.domain.repository.CaseEventRepository;
import com.taarifu.reporting.domain.repository.IssueCategoryRepository;
import com.taarifu.reporting.domain.repository.ReportRepository;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * The core case-management application service — file, track, comment on, and confirm/dispute reports,
 * plus the public near-me reads (PRD §10 Epic M3, §12.1, §25.3, Appendix D; UC-D01/D05/D11-13).
 *
 * <p>Responsibility: owns the report lifecycle business rules and the transaction boundary, returning
 * DTOs (never entities). The pieces it single-sources:</p>
 * <ul>
 *   <li><b>Sensitive/anonymous intake (D-Q1, Appendix D.4):</b> a sensitive category permits anonymous
 *       filing (no reporter linkage) and forces PRIVATE visibility; a non-sensitive category rejects
 *       anonymity. The visibility a non-anonymous citizen requests is honoured unless the category
 *       forces PRIVATE.</li>
 *   <li><b>SLA (UC-D04):</b> {@code dueAt = filedAt + category.defaultSlaTtrMinutes}.</li>
 *   <li><b>Ticket code:</b> {@code TAR-YYYY-NNNNNN} from a DB sequence via {@link CodeGenerator} (race-safe,
 *       gap-tolerant) — never {@code max(id)+1}.</li>
 *   <li><b>State machine (§12.1):</b> all transitions go through {@link #transition} which consults
 *       {@link ReportStatus#canTransitionTo} and appends a {@code STATUS_CHANGE} {@link CaseEvent}; an
 *       illegal transition is a typed {@link ErrorCode#CONFLICT}.</li>
 *   <li><b>Ownership:</b> track/comment/confirm operate on the caller's <b>own</b> report only; a mismatch
 *       is a not-found (never reveal another citizen's report exists).</li>
 *   <li><b>Privacy on public reads:</b> only PUBLIC reports are returned, mapped to the PII-free
 *       {@link PublicReportDto}; the repository filters visibility as defence-in-depth (PRD §25.3).</li>
 * </ul>
 *
 * <p>Routing to a responder is <b>DEFERRED</b>: reports are created {@code NEW} and never auto-assigned
 * here (see {@code // TODO(wiring)}). The notifications fan-out (ack/status-change) is likewise a later
 * increment; this service records the timeline event that those notifications will key off.</p>
 */
@Service
@Transactional(readOnly = true)
public class ReportService {

    /** Sequence created by V23; drives the {@code TAR-YYYY-NNNNNN} ticket code. */
    private static final String REPORT_CODE_SEQUENCE = "report_code_seq";
    private static final String REPORT_CODE_PREFIX = "TAR";
    private static final int REPORT_CODE_PAD_WIDTH = 6;

    /** SRID 4326 (WGS84) factory for incident points, matching geography's geometry columns. */
    private static final GeometryFactory GEOMETRY_FACTORY =
            new GeometryFactory(new PrecisionModel(), 4326);

    private final ReportRepository reportRepository;
    private final IssueCategoryRepository categoryRepository;
    private final CaseEventRepository caseEventRepository;
    private final WardResolver wardResolver;
    private final CodeGenerator codeGenerator;
    private final ReportingMapper mapper;
    private final ClockPort clock;

    /**
     * @param reportRepository    report persistence port.
     * @param categoryRepository  category persistence port.
     * @param caseEventRepository timeline persistence port.
     * @param wardResolver        ward→constituency resolver port (delegates to geography).
     * @param codeGenerator       DB-sequence ticket-code generator.
     * @param mapper              entity→DTO mapper.
     * @param clock               injectable time source (testability).
     */
    public ReportService(ReportRepository reportRepository, IssueCategoryRepository categoryRepository,
                         CaseEventRepository caseEventRepository, WardResolver wardResolver,
                         CodeGenerator codeGenerator, ReportingMapper mapper, ClockPort clock) {
        this.reportRepository = reportRepository;
        this.categoryRepository = categoryRepository;
        this.caseEventRepository = caseEventRepository;
        this.wardResolver = wardResolver;
        this.codeGenerator = codeGenerator;
        this.mapper = mapper;
        this.clock = clock;
    }

    // ---------------------------------------------------------------------------------------------
    // File a report (UC-D01)
    // ---------------------------------------------------------------------------------------------

    /**
     * Files a new report (US-3.1, UC-D01). Resolves the ward, applies the sensitive/anonymous and
     * visibility rules, computes the SLA due date, issues the ticket code, persists the report at
     * {@code NEW}, and appends the initial {@code STATUS_CHANGE} timeline event.
     *
     * <p>The {@code @RequiresTier} gate on the controller enforces T2 for ordinary reports; the
     * <b>anonymity-with-only-T1</b> relaxation for sensitive categories is a category-driven policy that
     * this service applies (Appendix D.4). Because tier gating is on the controller, this service trusts
     * the caller meets the floor and focuses on the data rules.</p>
     *
     * @param reporterProfileId the authenticated caller's profile {@code publicId} (the would-be reporter).
     * @param request           the validated file request.
     * @return the filed {@link ReportDto} (owner view).
     * @throws ResourceNotFoundException if the category or ward does not exist.
     * @throws ApiException {@link ErrorCode#BAD_REQUEST} for an inactive category, a malformed visibility
     *                      token, or an anonymous request on a non-sensitive category.
     */
    @Transactional
    public ReportDto fileReport(UUID reporterProfileId, FileReportDto request) {
        IssueCategory category = categoryRepository.findByPublicId(request.categoryId())
                .orElseThrow(() -> new ResourceNotFoundException("reporting.category.notFound", request.categoryId()));
        if (!category.isActive()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "reporting.category.inactive", category.getCode());
        }

        // Anonymity is permitted ONLY for sensitive categories (D-Q1, Appendix D.4).
        boolean anonymous = request.anonymous();
        if (anonymous && !category.isSensitive()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "reporting.report.anonymousNotAllowed", category.getCode());
        }
        // For an anonymous filing, no reporter linkage is stored at all — the strongest protection.
        UUID effectiveReporterId = anonymous ? null : reporterProfileId;

        // Visibility: a sensitive category that forces PRIVATE overrides any client choice.
        ReportVisibility visibility = resolveVisibility(category, request.visibility());

        // Resolve the ward (validates it is a WARD) + the constituency in effect (snapshot).
        WardResolver.Resolution resolution = wardResolver.resolveWard(request.wardId());

        // Optional incident point (WGS84). Both coordinates must be present to build a point.
        Point geoPoint = buildPoint(request.latitude(), request.longitude());

        // SLA: dueAt = filedAt + category TTR (UC-D04).
        var dueAt = clock.now().plus(category.getDefaultSlaTtrMinutes(), ChronoUnit.MINUTES);

        String attachmentRefs = joinAttachmentRefs(request.attachmentRefs());

        Report report = new Report(effectiveReporterId, category, request.title(), request.description(),
                geoPoint, resolution.wardPublicId(), resolution.constituencyPublicId(), attachmentRefs,
                visibility, ReportPriority.NORMAL, dueAt);

        // Issue the human ticket code from the DB sequence before persist (race-safe, gap-tolerant).
        report.setCode(codeGenerator.nextCode(REPORT_CODE_PREFIX, REPORT_CODE_SEQUENCE, REPORT_CODE_PAD_WIDTH));
        reportRepository.save(report);

        // Initial timeline entry (public): the case opened at NEW.
        appendEvent(report, CaseEventType.STATUS_CHANGE, true, effectiveReporterId,
                "Ripoti imepokelewa (NEW)");

        // TODO(wiring): route to a responder OWNER (D21) and emit a "report received" notification +
        // outbox event once the responders/notifications modules are integrated. Report stays NEW for now.

        return mapper.toReportDto(report);
    }

    // ---------------------------------------------------------------------------------------------
    // Track own report + timeline (UC-D05)
    // ---------------------------------------------------------------------------------------------

    /**
     * Fetches one of the caller's <b>own</b> reports (US-3.2 tracking).
     *
     * <p>WHY ownership is checked as not-found (not forbidden): revealing "this report exists but isn't
     * yours" leaks the existence of another citizen's (possibly sensitive) report. A non-owner gets the
     * same {@code 404} as a non-existent id (PRD §18, §25.3).</p>
     *
     * @param reporterProfileId the caller's profile {@code publicId}.
     * @param reportPublicId    the report's public id.
     * @return the owner-view {@link ReportDto}.
     * @throws ResourceNotFoundException if the report does not exist or is not the caller's.
     */
    public ReportDto getMyReport(UUID reporterProfileId, UUID reportPublicId) {
        return mapper.toReportDto(requireOwnReport(reporterProfileId, reportPublicId));
    }

    /**
     * Lists the caller's own reports (US-3.2).
     *
     * @param reporterProfileId the caller's profile {@code publicId}.
     * @param pageable          bounded paging/sorting.
     * @return a page of the caller's {@link ReportDto}.
     */
    public Page<ReportDto> listMyReports(UUID reporterProfileId, Pageable pageable) {
        return reportRepository.findByReporterProfileId(reporterProfileId, pageable).map(mapper::toReportDto);
    }

    /**
     * Returns the <b>full</b> timeline (public + internal) of one of the caller's own reports. WHY the
     * owner sees internal events too: US-3.2 shows the reporter the case history; truly responder-private
     * internal notes are flagged {@code publicEvent=false} but the reporter, as a party to the case, may
     * see the activity. The strictly-public projection is used for the public endpoints instead.
     *
     * @param reporterProfileId the caller's profile {@code publicId}.
     * @param reportPublicId    the report's public id (must be the caller's).
     * @param pageable          bounded paging/sorting.
     * @return a page of {@link CaseEventDto} for the report.
     * @throws ResourceNotFoundException if the report does not exist or is not the caller's.
     */
    public Page<CaseEventDto> getMyReportTimeline(UUID reporterProfileId, UUID reportPublicId,
                                                  Pageable pageable) {
        requireOwnReport(reporterProfileId, reportPublicId);
        return caseEventRepository.findByReport_PublicId(reportPublicId, pageable).map(mapper::toCaseEventDto);
    }

    // ---------------------------------------------------------------------------------------------
    // Add info / comment (US-3.2)
    // ---------------------------------------------------------------------------------------------

    /**
     * Adds a citizen comment / extra info to the caller's own report (US-3.2). The comment is always a
     * public timeline event. If the case was {@code AWAITING_INFO}, the reply resumes work
     * ({@code → IN_PROGRESS}) per the §12.1 reply flow.
     *
     * @param reporterProfileId the caller's profile {@code publicId}.
     * @param reportPublicId    the report's public id (must be the caller's).
     * @param message           the comment/info text.
     * @return the appended {@link CaseEventDto}.
     * @throws ResourceNotFoundException if the report does not exist or is not the caller's.
     */
    @Transactional
    public CaseEventDto addComment(UUID reporterProfileId, UUID reportPublicId, String message) {
        Report report = requireOwnReport(reporterProfileId, reportPublicId);
        CaseEvent event = appendEvent(report, CaseEventType.COMMENT, true, reporterProfileId, message);
        // A reply while AWAITING_INFO resumes active work (§12.1 reply edge).
        if (report.getStatus() == ReportStatus.AWAITING_INFO) {
            transition(report, ReportStatus.IN_PROGRESS, reporterProfileId,
                    "Mtoa taarifa amejibu (AWAITING_INFO → IN_PROGRESS)");
        }
        return mapper.toCaseEventDto(event);
    }

    // ---------------------------------------------------------------------------------------------
    // Confirm / dispute resolution (US-3.5, UC-D11/12/13)
    // ---------------------------------------------------------------------------------------------

    /**
     * Records the caller's confirm/dispute decision on their own {@code RESOLVED} report (US-3.5):
     * confirm → {@code CLOSED}, dispute → {@code REOPENED}. Appends a comment with the optional reason and
     * a {@code STATUS_CHANGE} event.
     *
     * @param reporterProfileId the caller's profile {@code publicId}.
     * @param reportPublicId    the report's public id (must be the caller's).
     * @param confirmed         {@code true} to confirm (close), {@code false} to dispute (reopen).
     * @param reason            optional reason recorded on the timeline.
     * @return the updated owner-view {@link ReportDto}.
     * @throws ResourceNotFoundException if the report does not exist or is not the caller's.
     * @throws ApiException {@link ErrorCode#CONFLICT} if the report is not in {@code RESOLVED} (the only
     *                      state a citizen may confirm/dispute from).
     */
    @Transactional
    public ReportDto confirmResolution(UUID reporterProfileId, UUID reportPublicId, boolean confirmed,
                                       String reason) {
        Report report = requireOwnReport(reporterProfileId, reportPublicId);
        if (report.getStatus() != ReportStatus.RESOLVED) {
            throw new ApiException(ErrorCode.CONFLICT, "reporting.report.notResolved", report.getCode());
        }
        ReportStatus target = confirmed ? ReportStatus.CLOSED : ReportStatus.REOPENED;
        // Guard the transition through the state machine, then record the citizen's decision on the report.
        guardTransition(report.getStatus(), target, report.getCode());
        report.applyConfirmation(confirmed);
        if (reason != null && !reason.isBlank()) {
            appendEvent(report, CaseEventType.COMMENT, true, reporterProfileId, reason);
        }
        appendEvent(report, CaseEventType.STATUS_CHANGE, true, reporterProfileId,
                "%s → %s".formatted(ReportStatus.RESOLVED, target));
        return mapper.toReportDto(report);
    }

    // ---------------------------------------------------------------------------------------------
    // Public reads (US-3.7) — PUBLIC only, never reporter PII
    // ---------------------------------------------------------------------------------------------

    /**
     * Lists PUBLIC reports for the public near-me list/map (US-3.7). Never returns PRIVATE/sensitive
     * reports and never exposes reporter PII (PRD §25.3). An optional ward narrows the list.
     *
     * @param wardPublicId optional ward {@code publicId} to filter by, or {@code null} for all wards.
     * @param pageable     bounded paging/sorting.
     * @return a page of PII-free {@link PublicReportDto}.
     */
    public Page<PublicReportDto> listPublicReports(UUID wardPublicId, Pageable pageable) {
        Page<Report> page = wardPublicId != null
                ? reportRepository.findByReporterWardIdAndVisibility(wardPublicId, ReportVisibility.PUBLIC, pageable)
                : reportRepository.findByVisibility(ReportVisibility.PUBLIC, pageable);
        return page.map(mapper::toPublicReportDto);
    }

    /**
     * Fetches a single PUBLIC report for public viewing (US-3.7). A PRIVATE report is reported as
     * not-found to a public viewer — its existence is not revealed (PRD §25.3).
     *
     * @param reportPublicId the report's public id.
     * @return the PII-free {@link PublicReportDto}.
     * @throws ResourceNotFoundException if the report does not exist or is not PUBLIC.
     */
    public PublicReportDto getPublicReport(UUID reportPublicId) {
        Report report = reportRepository.findByPublicId(reportPublicId)
                .filter(r -> r.getVisibility() == ReportVisibility.PUBLIC)
                .orElseThrow(() -> new ResourceNotFoundException("reporting.report.notFound", reportPublicId));
        return mapper.toPublicReportDto(report);
    }

    /**
     * Returns the public timeline (public events only) of a PUBLIC report (US-3.2/US-3.7). Internal
     * responder notes are never included.
     *
     * @param reportPublicId the report's public id.
     * @param pageable       bounded paging/sorting.
     * @return a page of public {@link CaseEventDto}.
     * @throws ResourceNotFoundException if the report does not exist or is not PUBLIC.
     */
    public Page<CaseEventDto> getPublicReportTimeline(UUID reportPublicId, Pageable pageable) {
        reportRepository.findByPublicId(reportPublicId)
                .filter(r -> r.getVisibility() == ReportVisibility.PUBLIC)
                .orElseThrow(() -> new ResourceNotFoundException("reporting.report.notFound", reportPublicId));
        return caseEventRepository.findByReport_PublicIdAndPublicEventTrue(reportPublicId, pageable)
                .map(mapper::toCaseEventDto);
    }

    // ---------------------------------------------------------------------------------------------
    // State machine + helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Performs a guarded status transition and records a {@code STATUS_CHANGE} timeline event. The single
     * place a report's status moves, so the §12.1 rules are never bypassed.
     *
     * @param report         the report to transition.
     * @param target         the desired next status.
     * @param actorProfileId the acting profile {@code publicId}, or {@code null} for a system transition.
     * @param message        the human description recorded on the timeline.
     * @throws ApiException {@link ErrorCode#CONFLICT} if the transition is not allowed from the current state.
     */
    private void transition(Report report, ReportStatus target, UUID actorProfileId, String message) {
        guardTransition(report.getStatus(), target, report.getCode());
        report.setStatus(target);
        appendEvent(report, CaseEventType.STATUS_CHANGE, true, actorProfileId, message);
    }

    /**
     * Validates a transition is legal per the state machine; throws a typed conflict if not.
     *
     * @param from the current status.
     * @param to   the proposed next status.
     * @param code the report code for the localised message.
     * @throws ApiException {@link ErrorCode#CONFLICT} if {@code from} cannot move to {@code to}.
     */
    private void guardTransition(ReportStatus from, ReportStatus to, String code) {
        if (!from.canTransitionTo(to)) {
            throw new ApiException(ErrorCode.CONFLICT, "reporting.report.illegalTransition", code, from, to);
        }
    }

    /** Appends and persists a timeline event for a report. */
    private CaseEvent appendEvent(Report report, CaseEventType type, boolean publicEvent, UUID actorProfileId,
                                  String message) {
        return caseEventRepository.save(new CaseEvent(report, type, publicEvent, actorProfileId, message));
    }

    /**
     * Loads the caller's own report or throws not-found. Ownership mismatch is a {@code 404} (never reveal
     * another citizen's report). Anonymous reports (no reporter id) are never "owned" by an account, so
     * they are not reachable through this account-scoped path (tracked by code instead).
     */
    private Report requireOwnReport(UUID reporterProfileId, UUID reportPublicId) {
        return reportRepository.findByPublicIdWithCategory(reportPublicId)
                .filter(r -> reporterProfileId.equals(r.getReporterProfileId()))
                .orElseThrow(() -> new ResourceNotFoundException("reporting.report.notFound", reportPublicId));
    }

    /**
     * Resolves the effective visibility: a category that forces PRIVATE wins over any client choice; else
     * the client's choice if valid; else the category default (Appendix D.4, D-Q1).
     */
    private ReportVisibility resolveVisibility(IssueCategory category, String requested) {
        if (category.isForcePrivate()) {
            return ReportVisibility.PRIVATE;
        }
        if (requested == null || requested.isBlank()) {
            return category.getDefaultVisibility();
        }
        try {
            return ReportVisibility.valueOf(requested);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "reporting.report.badVisibility", requested);
        }
    }

    /** Builds a WGS84 point from lat/long, or {@code null} if either is absent. */
    private Point buildPoint(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            return null;
        }
        // JTS uses (x=longitude, y=latitude); SRID 4326 matches geography's geometry columns.
        Point point = GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude));
        point.setSRID(4326);
        return point;
    }

    /** Joins attachment refs into the delimited column form, or {@code null} if none. */
    private String joinAttachmentRefs(java.util.List<String> refs) {
        if (refs == null || refs.isEmpty()) {
            return null;
        }
        return String.join(",", refs);
    }
}
