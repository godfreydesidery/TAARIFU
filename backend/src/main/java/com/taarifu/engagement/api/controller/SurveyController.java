package com.taarifu.engagement.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.pagination.PageMapper;
import com.taarifu.common.pagination.PageRequestFactory;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.common.security.RequiresTier;
import com.taarifu.engagement.api.dto.CreateSurveyRequest;
import com.taarifu.engagement.api.dto.SurveyDto;
import com.taarifu.engagement.api.dto.SurveyResponseRequest;
import com.taarifu.engagement.application.service.SurveyService;
import com.taarifu.engagement.domain.model.enums.SurveyType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST surface for surveys/polls (PRD §12.2 M8; ARCHITECTURE.md §3.3, §6.2).
 *
 * <p>Responsibility: the thin HTTP layer for {@code /surveys}. Validates input, delegates to
 * {@link SurveyService}, wraps results in the single {@link ApiResponse} envelope. No business logic, no
 * {@code @Transactional} (CLAUDE.md §8).</p>
 *
 * <p><b>Authorization design (ARCHITECTURE §6.2):</b></p>
 * <ul>
 *   <li><b>List/view</b> — {@code permitAll()}: published surveys/polls are public (PRD §22.6; drafts
 *       filtered by the service).</li>
 *   <li><b>Create</b> — {@code isAuthenticated()}: authoring is for signed-in authorities/reps/orgs
 *       (US-8.1); finer role/quota scoping is a later wiring step.</li>
 *   <li><b>Respond</b> — gated {@code @RequiresTier("T3")}: the respond endpoint declares the
 *       <b>strictest</b> tier so a <i>binding poll</i> response satisfies the integrity fence (D18, PRD
 *       §23.5) by construction. WHY T3 (not T2) at the annotation: binding-ness is per-survey data, but
 *       the tier aspect needs a static minimum on the method; choosing the stricter T3 keeps binding polls
 *       safe, and the per-survey relaxation to T2 for non-binding surveys is a documented
 *       {@code // TODO(wiring)} refinement (it requires reading the survey before the tier aspect runs).
 *       One-per-person is enforced in the service; token balance is never read.</li>
 * </ul>
 */
@RestController
@RequestMapping("/surveys")
@Tag(name = "Engagement", description = "Petitions, surveys/polls, and public Q&A (Swahili-first).")
public class SurveyController {

    private final SurveyService surveyService;
    private final ResponseFactory responses;
    private final PageRequestFactory pageRequests;
    private final PageMapper pageMapper;

    /**
     * @param surveyService survey use-cases.
     * @param responses     envelope builder.
     * @param pageRequests  safe {@link Pageable} factory.
     * @param pageMapper    {@code Page}→{@code PageMeta} adapter.
     */
    public SurveyController(SurveyService surveyService,
                            ResponseFactory responses,
                            PageRequestFactory pageRequests,
                            PageMapper pageMapper) {
        this.surveyService = surveyService;
        this.responses = responses;
        this.pageRequests = pageRequests;
        this.pageMapper = pageMapper;
    }

    /**
     * Lists publicly-visible surveys/polls, paged.
     *
     * @param page zero-based page index.
     * @param size page size (capped at 100).
     * @param sort sort expression.
     * @return a paged envelope of {@link SurveyDto}.
     */
    @GetMapping
    @PreAuthorize("permitAll()")
    @Operation(summary = "List public surveys/polls", description = "Optionally filter by type.")
    public ApiResponse<List<SurveyDto>> list(
            @RequestParam(required = false) SurveyType type,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<SurveyDto> result = surveyService.listPublic(type, pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    /**
     * Fetches a single survey/poll by public id.
     *
     * @param surveyId the survey's public id.
     * @return an envelope carrying the {@link SurveyDto}.
     */
    @GetMapping("/{surveyId}")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Get a survey/poll by id")
    public ApiResponse<SurveyDto> get(@PathVariable UUID surveyId) {
        return responses.ok(surveyService.get(surveyId));
    }

    /**
     * Creates a survey/poll (DRAFT — UC-E06).
     *
     * @param request the survey fields.
     * @return {@code 201} + the created {@link SurveyDto}.
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a survey/poll (DRAFT)")
    public ApiResponse<SurveyDto> create(@Valid @RequestBody CreateSurveyRequest request) {
        SurveyDto dto = surveyService.create(
                request.title(), request.description(), request.type(), request.binding(),
                request.audienceScope(), request.questions(), request.startsAt(), request.endsAt(),
                request.anonymous(), CurrentUser.requirePublicId());
        return responses.ok(dto);
    }

    /**
     * Opens a survey/poll for responses (UC-E06) — the {@code DRAFT/SCHEDULED → OPEN} transition.
     *
     * <p><b>Authorization (author-or-staff):</b> {@code isAuthenticated()} proves the caller is signed in; the
     * data-dependent author-or-staff decision (the caller is the survey's creator, or holds MODERATOR/ADMIN/
     * ROOT) is enforced in {@link SurveyService#open} — a caller who is neither is {@code 403}. The caller is
     * taken from the security context, never the body.</p>
     *
     * @param surveyId the survey to open.
     * @return an envelope carrying the now-OPEN {@link SurveyDto}.
     */
    @PostMapping("/{surveyId}/opening")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Open a survey/poll (DRAFT/SCHEDULED → OPEN)",
            description = "Author-or-staff. Begins accepting responses.")
    public ApiResponse<SurveyDto> open(@PathVariable UUID surveyId) {
        SurveyDto dto = surveyService.open(surveyId, CurrentUser.requirePublicId());
        return responses.ok(dto);
    }

    /**
     * Responds to a survey/poll (UC-E07). T3-gated so a binding poll satisfies the integrity fence.
     *
     * @param surveyId the survey to respond to.
     * @param request  the answers payload (responder identity comes from the token).
     * @return an envelope carrying the {@link SurveyDto}.
     */
    @PostMapping("/{surveyId}/responses")
    @PreAuthorize("isAuthenticated()")
    @RequiresTier("T3")
    @Operation(summary = "Respond to a survey/poll (one-per-person; binding polls are T3)")
    public ApiResponse<SurveyDto> respond(@PathVariable UUID surveyId,
                                          @Valid @RequestBody SurveyResponseRequest request) {
        SurveyDto dto = surveyService.respond(
                surveyId, CurrentUser.requirePublicId(), request.answers());
        return responses.ok(dto);
    }
}
