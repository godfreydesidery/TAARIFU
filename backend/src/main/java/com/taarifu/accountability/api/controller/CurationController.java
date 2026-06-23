package com.taarifu.accountability.api.controller;

import com.taarifu.accountability.api.dto.AttendanceDto;
import com.taarifu.accountability.api.dto.ContributionDto;
import com.taarifu.accountability.api.dto.CreateAttendanceDto;
import com.taarifu.accountability.api.dto.CreateContributionDto;
import com.taarifu.accountability.api.dto.CreatePromiseDto;
import com.taarifu.accountability.api.dto.PromiseDto;
import com.taarifu.accountability.api.dto.UpdatePromiseStatusDto;
import com.taarifu.accountability.application.service.CurationService;
import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Curated-authoring REST surface for accountability data — admin/authorised-author only (PRD §10 Epic M6;
 * D-Q4; EI-11).
 *
 * <p>Responsibility: lets curators create/maintain contributions, attendance, and promises. Per D-Q4
 * these are platform/partner-curated, so every endpoint is gated to {@code ROLE_ADMIN} via method
 * security ({@code @PreAuthorize("hasRole('ADMIN')")}) — the authorised-author roles widen this at the
 * wiring step. It owns no business logic or transaction (delegates to {@link CurationService}).</p>
 *
 * <p>WHY a separate controller from {@link RatingController}: curation is ordinary admin authoring;
 * ratings are a binding civic action behind the integrity fence (tier + one-per-person + no-self, never a
 * token balance). Keeping the two apart prevents the fence and the authoring path from being confused.</p>
 */
@RestController
@RequestMapping("/accountability")
@Tag(name = "Accountability (curation)",
        description = "Admin/authorised-author authoring of curated accountability data (D-Q4).")
public class CurationController {

    private final CurationService curationService;
    private final ResponseFactory responses;

    /**
     * @param curationService curated-authoring service.
     * @param responses       envelope builder.
     */
    public CurationController(CurationService curationService, ResponseFactory responses) {
        this.curationService = curationService;
        this.responses = responses;
    }

    /**
     * Creates a curated representative contribution.
     *
     * @param request the validated create request.
     * @return {@code 201} + the created {@link ContributionDto}.
     */
    @PostMapping("/contributions")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a contribution (curated)",
            description = "ROLE_ADMIN only (D-Q4). Carries provenance (sourceUrl) — Taarifu is not the source of truth.")
    public ResponseEntity<ApiResponse<ContributionDto>> createContribution(
            @Valid @RequestBody CreateContributionDto request) {
        ContributionDto created = curationService.createContribution(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(responses.ok(created));
    }

    /**
     * Records an attendance row for a (representative, session).
     *
     * @param request the validated create request.
     * @return {@code 201} + the created {@link AttendanceDto}.
     */
    @PostMapping("/attendance")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Record attendance (curated)",
            description = "ROLE_ADMIN only. One authoritative row per (representative, session) — repeats are 409.")
    public ResponseEntity<ApiResponse<AttendanceDto>> recordAttendance(
            @Valid @RequestBody CreateAttendanceDto request) {
        AttendanceDto created = curationService.recordAttendance(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(responses.ok(created));
    }

    /**
     * Creates a curated promise.
     *
     * @param request the validated create request.
     * @return {@code 201} + the created {@link PromiseDto}.
     */
    @PostMapping("/promises")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a promise (curated)", description = "ROLE_ADMIN only (D-Q4).")
    public ResponseEntity<ApiResponse<PromiseDto>> createPromise(
            @Valid @RequestBody CreatePromiseDto request) {
        PromiseDto created = curationService.createPromise(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(responses.ok(created));
    }

    /**
     * Advances a promise's tracked status (curated, evidence-backed).
     *
     * @param promiseId the promise's public id.
     * @param request   the validated status-update request.
     * @return {@code 200} + the updated {@link PromiseDto}.
     */
    @PatchMapping("/promises/{promiseId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update a promise's status (curated)",
            description = "ROLE_ADMIN only. An authored, evidence-backed judgement (neutrality requires provenance).")
    public ResponseEntity<ApiResponse<PromiseDto>> updatePromiseStatus(
            @PathVariable UUID promiseId,
            @Valid @RequestBody UpdatePromiseStatusDto request) {
        PromiseDto updated = curationService.updatePromiseStatus(promiseId, request);
        return ResponseEntity.ok(responses.ok(updated));
    }
}
