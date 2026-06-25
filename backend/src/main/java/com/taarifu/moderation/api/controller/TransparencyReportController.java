package com.taarifu.moderation.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.moderation.api.dto.TransparencyReportDto;
import com.taarifu.moderation.application.service.TransparencyReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Read-only REST surface for the moderation <b>transparency report</b> (PRD §18, §25 transparency reporting,
 * M-Phase 3; ADR-0018).
 *
 * <p>Responsibility: the thin HTTP layer for the aggregate, PII-free transparency artefact — action mix,
 * appeal outcomes, flag volume by reason, and the auto-vs-manual split over a window — delegating to
 * {@link TransparencyReportService} and wrapping the result in the single {@link ApiResponse} envelope. No
 * business logic, no {@code @Transactional} (CLAUDE.md §8).</p>
 *
 * <p><b>Authorization (deny-by-default — ARCHITECTURE.md §6.2):</b> gated to staff read roles
 * ({@code ADMIN}/{@code ROOT}/{@code MODERATOR}); a citizen token is denied at the method layer. The
 * <i>public</i> M-Phase 3 publication is a downstream export of this same PII-free aggregate, not a separate
 * query — there is one source of these numbers.</p>
 *
 * <p><b>No PII surfaced (§25.3, §18):</b> every payload field is an aggregate count keyed on a code/enum;
 * nothing here resolves to a person, a precise location, a report/comment body, or a moderator identity.</p>
 */
@RestController
@RequestMapping(path = "/moderation/transparency")
@Tag(name = "Moderation — Transparency", description = "PII-free moderation transparency report (§25, M-Phase 3).")
public class TransparencyReportController {

    private final TransparencyReportService reportService;
    private final ResponseFactory responses;

    /**
     * @param reportService transparency aggregation reads.
     * @param responses     envelope builder.
     */
    public TransparencyReportController(TransparencyReportService reportService, ResponseFactory responses) {
        this.reportService = reportService;
        this.responses = responses;
    }

    /**
     * Returns the transparency report over the window (§25).
     *
     * @param from optional inclusive window start (ISO-8601 UTC); defaults to 30 days before {@code to}.
     * @param to   optional exclusive window end (ISO-8601 UTC); defaults to now.
     * @return an envelope carrying the PII-free {@link TransparencyReportDto}.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','ROOT','MODERATOR')")
    @Operation(summary = "Moderation transparency report",
            description = "Aggregate, PII-free action mix / appeal outcomes / flags-by-reason / auto-vs-manual split.")
    public ApiResponse<TransparencyReportDto> report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return responses.ok(reportService.report(from, to));
    }
}
