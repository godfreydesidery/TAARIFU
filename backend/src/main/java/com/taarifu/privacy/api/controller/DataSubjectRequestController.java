package com.taarifu.privacy.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.pagination.PageMapper;
import com.taarifu.common.pagination.PageRequestFactory;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.privacy.api.dto.DsrDto;
import com.taarifu.privacy.api.dto.SubjectDataExport;
import com.taarifu.privacy.application.service.DataSubjectRequestService;
import com.taarifu.privacy.application.service.SubjectDataExportService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The data-subject-rights endpoints — ACCESS (export) and ERASURE self-service + the ADMIN/ROOT oversight
 * workflow (PRD §18 PDPA, §25.1, UC-A17/UC-S09; ADR-0016 §3/§4/§5/§7).
 *
 * <p>Method security (deny-by-default, defence in depth):</p>
 * <ul>
 *   <li><b>Self-service</b> ({@code isAuthenticated()}): a citizen opens/tracks their own ACCESS/ERASURE
 *       request and downloads their own export. The subject is always bound from the authenticated principal
 *       ({@code CurrentUser.requirePublicId()}) — never a path/body id, so no acting-on-others.</li>
 *   <li><b>Oversight</b> ({@code hasRole('ADMIN')}; ROOT inherits via the RoleHierarchy): the operator queue,
 *       acknowledge, legal-hold, complete, and an operator-driven export on a tracked DSR.</li>
 * </ul>
 *
 * <p>No business logic/transaction here — {@link DataSubjectRequestService} owns the SLA lifecycle, the
 * active-role constraint, and the atomic {@code ERASURE_REQUESTED} outbox publish; {@link SubjectDataExportService}
 * composes the export. Every action is audited in the services (references/codes only — no PII).</p>
 */
@RestController
@RequestMapping("/privacy/dsr")
public class DataSubjectRequestController {

    private final DataSubjectRequestService dsrService;
    private final SubjectDataExportService exportService;
    private final ResponseFactory responses;
    private final PageRequestFactory pageRequests;
    private final PageMapper pageMapper;

    /**
     * @param dsrService    the DSR intake + workflow service.
     * @param exportService the ACCESS export aggregator.
     * @param responses     the single envelope builder.
     * @param pageRequests  bounded {@code Pageable} factory (size-capped).
     * @param pageMapper    {@code Page} → envelope {@code meta} mapper.
     */
    public DataSubjectRequestController(DataSubjectRequestService dsrService,
                                        SubjectDataExportService exportService,
                                        ResponseFactory responses,
                                        PageRequestFactory pageRequests,
                                        PageMapper pageMapper) {
        this.dsrService = dsrService;
        this.exportService = exportService;
        this.responses = responses;
        this.pageRequests = pageRequests;
        this.pageMapper = pageMapper;
    }

    // ----------------------------------- Self-service (the subject) -----------------------------------

    /**
     * Opens an ACCESS (export) request for the authenticated caller (idempotent on an open ACCESS request).
     *
     * @return {@code 200} + the tracked request.
     */
    @PostMapping("/access")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<DsrDto>> requestAccess() {
        return ResponseEntity.ok(responses.ok(dsrService.requestAccess(CurrentUser.requirePublicId())));
    }

    /**
     * Generates and returns the authenticated caller's own data export (PDPA right of access).
     *
     * @return {@code 200} + the composed, minimised export.
     */
    @GetMapping("/access/export")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<SubjectDataExport>> myExport() {
        UUID me = CurrentUser.requirePublicId();
        // Self-service: subject == actor.
        return ResponseEntity.ok(responses.ok(exportService.export(me, me)));
    }

    /**
     * Opens an ERASURE request for the authenticated caller (idempotent; blocked for active staff/rep roles —
     * note ᵇ). Publishes the {@code ERASURE_REQUESTED} fan-out event atomically.
     *
     * @return {@code 200} + the tracked request.
     */
    @PostMapping("/erasure")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<DsrDto>> requestErasure() {
        return ResponseEntity.ok(responses.ok(dsrService.requestErasure(CurrentUser.requirePublicId())));
    }

    /**
     * Lists the authenticated caller's own open requests (self-service status tracking).
     *
     * @return {@code 200} + the caller's open requests.
     */
    @GetMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<DsrDto>>> myRequests() {
        return ResponseEntity.ok(responses.ok(dsrService.myRequests(CurrentUser.requirePublicId())));
    }

    // -------------------------------------- Oversight (ADMIN/ROOT) --------------------------------------

    /**
     * The operator queue of open requests, oldest-due first. ADMIN/ROOT only.
     *
     * @param page zero-based page index.
     * @param size page size (capped server-side).
     * @return {@code 200} + the paged queue.
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<DsrDto>>> queue(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Page<DsrDto> result = dsrService.queue(pageRequests.of(page, size, "dueAt,asc"));
        return ResponseEntity.ok(responses.paged(result.getContent(), pageMapper.toMeta(result)));
    }

    /**
     * Acknowledges a request to the subject (≤72h obligation — §25.1). ADMIN/ROOT only.
     *
     * @param publicId the request to acknowledge.
     * @return {@code 200} + the updated request.
     */
    @PostMapping("/{publicId}/acknowledge")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<DsrDto>> acknowledge(@PathVariable UUID publicId) {
        return ResponseEntity.ok(responses.ok(
                dsrService.acknowledge(CurrentUser.requirePublicId(), publicId)));
    }

    /**
     * Places a request under legal hold (suspends erasure until released — §25.1). ADMIN/ROOT only.
     *
     * @param publicId   the request to hold.
     * @param reasonCode the machine hold reason (e.g. {@code UNDER_INVESTIGATION}); never PII.
     * @return {@code 200} + the updated request.
     */
    @PostMapping("/{publicId}/hold")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<DsrDto>> hold(@PathVariable UUID publicId,
                                                    @RequestParam(required = false) String reasonCode) {
        return ResponseEntity.ok(responses.ok(
                dsrService.placeOnHold(CurrentUser.requirePublicId(), publicId, reasonCode)));
    }

    /**
     * Marks a request fully fulfilled/closed. ADMIN/ROOT only.
     *
     * @param publicId the request to complete.
     * @return {@code 200} + the updated request.
     */
    @PostMapping("/{publicId}/complete")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<DsrDto>> complete(@PathVariable UUID publicId) {
        return ResponseEntity.ok(responses.ok(
                dsrService.complete(CurrentUser.requirePublicId(), publicId)));
    }
}
