package com.taarifu.accountability.api.controller;

import com.taarifu.accountability.api.dto.AttendanceDto;
import com.taarifu.accountability.api.dto.AttendanceSummaryDto;
import com.taarifu.accountability.api.dto.ContributionDto;
import com.taarifu.accountability.api.dto.PromiseDto;
import com.taarifu.accountability.api.dto.PromiseStatusEntryDto;
import com.taarifu.accountability.api.dto.RatingReplyDto;
import com.taarifu.accountability.api.dto.RatingSummaryDto;
import com.taarifu.accountability.application.service.AccountabilityQueryService;
import com.taarifu.accountability.domain.model.enums.ContributionType;
import com.taarifu.accountability.domain.model.enums.PromiseStatus;
import com.taarifu.accountability.domain.model.enums.RatingSubjectType;
import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.pagination.PageMapper;
import com.taarifu.common.pagination.PageRequestFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Public, read-only REST surface for representative accountability data (PRD §10 Epic M6,
 * US-6.1/6.2/6.3).
 *
 * <p>Responsibility: the thin HTTP layer for the citizen-facing reads — a representative's contributions,
 * attendance (rows + summary), promises, and a subject's aggregate rating. It validates input, delegates
 * to {@link AccountabilityQueryService}, and wraps every result in the single {@link ApiResponse}
 * envelope. No business logic, no {@code @Transactional} (ARCHITECTURE.md §3.3).</p>
 *
 * <p>WHY {@code @PreAuthorize("permitAll()")}: accountability reads are public so any citizen (including
 * feature-phone/unauthenticated) can judge their representative (US-6.1). Stating it at the method keeps
 * "public" an explicit decision. NOTE: the matching URL allow-list entry must be added centrally in
 * {@code SecurityConfig} (see CENTRAL INTEGRATION NEEDS) — this module must not edit that shared file.</p>
 */
@RestController
@RequestMapping("/representatives")
@Tag(name = "Accountability",
        description = "Public representative accountability reads: contributions, attendance, promises, ratings.")
public class AccountabilityController {

    private final AccountabilityQueryService queryService;
    private final ResponseFactory responses;
    private final PageRequestFactory pageRequests;
    private final PageMapper pageMapper;

    /**
     * @param queryService public-read service.
     * @param responses    envelope builder.
     * @param pageRequests safe {@link Pageable} factory (size caps).
     * @param pageMapper   {@code Page}→{@code PageMeta} adapter.
     */
    public AccountabilityController(AccountabilityQueryService queryService,
                                    ResponseFactory responses,
                                    PageRequestFactory pageRequests,
                                    PageMapper pageMapper) {
        this.queryService = queryService;
        this.responses = responses;
        this.pageRequests = pageRequests;
        this.pageMapper = pageMapper;
    }

    /**
     * Lists a representative's contributions, optionally filtered by type.
     *
     * @param representativeId the subject representative's public id.
     * @param type             the contribution type to filter by, or {@code null} for all.
     * @param page             zero-based page index.
     * @param size             page size (capped at 100).
     * @param sort             sort expression {@code field,asc|desc}.
     * @return a paged envelope of {@link ContributionDto}.
     */
    @GetMapping("/{representativeId}/contributions")
    @PreAuthorize("permitAll()")
    @Operation(summary = "List a representative's contributions",
            description = "Public, paged; optional type filter (SPEECH/MOTION/BILL/QUESTION/VOTE/COMMITTEE).")
    public ApiResponse<List<ContributionDto>> listContributions(
            @PathVariable UUID representativeId,
            @RequestParam(required = false) ContributionType type,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<ContributionDto> result = queryService.listContributions(representativeId, type, pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    /**
     * Lists a representative's per-session attendance rows.
     *
     * @param representativeId the subject representative's public id.
     * @param page             zero-based page index.
     * @param size             page size (capped at 100).
     * @param sort             sort expression.
     * @return a paged envelope of {@link AttendanceDto}.
     */
    @GetMapping("/{representativeId}/attendance")
    @PreAuthorize("permitAll()")
    @Operation(summary = "List a representative's attendance rows", description = "Public, paged.")
    public ApiResponse<List<AttendanceDto>> listAttendance(
            @PathVariable UUID representativeId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<AttendanceDto> result = queryService.listAttendance(representativeId, pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    /**
     * Returns a representative's computed attendance summary (present/total/rate).
     *
     * @param representativeId the subject representative's public id.
     * @return an envelope carrying the {@link AttendanceSummaryDto}.
     */
    @GetMapping("/{representativeId}/attendance/summary")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Get a representative's attendance summary",
            description = "Public; present/total/rate computed from append-only rows.")
    public ApiResponse<AttendanceSummaryDto> attendanceSummary(@PathVariable UUID representativeId) {
        return responses.ok(queryService.attendanceSummary(representativeId));
    }

    /**
     * Lists a representative's promises, optionally filtered by status.
     *
     * @param representativeId the subject representative's public id.
     * @param status           the status to filter by, or {@code null} for all.
     * @param page             zero-based page index.
     * @param size             page size (capped at 100).
     * @param sort             sort expression.
     * @return a paged envelope of {@link PromiseDto}.
     */
    @GetMapping("/{representativeId}/promises")
    @PreAuthorize("permitAll()")
    @Operation(summary = "List a representative's promises",
            description = "Public, paged; optional status filter (MADE/IN_PROGRESS/KEPT/BROKEN).")
    public ApiResponse<List<PromiseDto>> listPromises(
            @PathVariable UUID representativeId,
            @RequestParam(required = false) PromiseStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<PromiseDto> result = queryService.listPromises(representativeId, status, pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    /**
     * Lists a promise's citizen-visible status timeline (US-6.3 — the dated provenance of how the promise
     * moved: made → in-progress → kept/broken).
     *
     * <p>Defaults to oldest→newest ({@code createdAt,asc}) so the timeline reads chronologically; a client may
     * override with {@code sort}. The {@code representativeId} in the path scopes the URL to a representative's
     * promise (RESTful nesting); the timeline itself is keyed by the promise id.</p>
     *
     * @param representativeId the owning representative's public id (URL scope).
     * @param promiseId        the promise's public id.
     * @param page             zero-based page index.
     * @param size             page size (capped at 100).
     * @param sort             sort expression; defaults to {@code createdAt,asc}.
     * @return a paged envelope of {@link PromiseStatusEntryDto}.
     */
    @GetMapping("/{representativeId}/promises/{promiseId}/timeline")
    @PreAuthorize("permitAll()")
    @Operation(summary = "List a promise's status timeline",
            description = "Public, paged; the append-only dated provenance of a promise's status moves (US-6.3).")
    public ApiResponse<List<PromiseStatusEntryDto>> promiseTimeline(
            @PathVariable UUID representativeId,
            @PathVariable UUID promiseId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "createdAt,asc") String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<PromiseStatusEntryDto> result = queryService.promiseTimeline(promiseId, pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    /**
     * Returns the representative's right-of-reply to a single rating, if one exists (the D-rated-fairness rule,
     * US-6.2) — so a client showing a moderated rating comment can show the representative's reply with it.
     *
     * @param ratingId the rating's public id.
     * @return an envelope carrying the {@link RatingReplyDto}, or {@code data=null} if the rating has no reply.
     */
    @GetMapping("/ratings/{ratingId}/reply")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Get a rating's right-of-reply",
            description = "Public; the rated representative's reply to a rating, shown with the rating (US-6.2). "
                    + "Returns null data when there is no reply.")
    public ApiResponse<RatingReplyDto> ratingReply(@PathVariable UUID ratingId) {
        return responses.ok(queryService.ratingReply(ratingId).orElse(null));
    }

    /**
     * Returns a representative's aggregate rating (count + average) — the only public face of ratings.
     *
     * @param representativeId the subject representative's public id.
     * @return an envelope carrying the {@link RatingSummaryDto}; no token balance contributes (§23 fence).
     */
    @GetMapping("/{representativeId}/rating/summary")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Get a representative's aggregate rating",
            description = "Public; computed from one-per-person ratings only. Tokens never move the score (§23).")
    public ApiResponse<RatingSummaryDto> ratingSummary(@PathVariable UUID representativeId) {
        return responses.ok(queryService.ratingSummary(RatingSubjectType.REPRESENTATIVE, representativeId));
    }
}
