package com.taarifu.engagement.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.pagination.PageMapper;
import com.taarifu.common.pagination.PageRequestFactory;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.common.security.RequiresTier;
import com.taarifu.engagement.api.dto.AnswerQuestionRequest;
import com.taarifu.engagement.api.dto.AskQuestionRequest;
import com.taarifu.engagement.api.dto.QuestionDto;
import com.taarifu.engagement.application.service.QuestionService;
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
 * REST surface for public Q&A to representatives (PRD §12.2 M10; ARCHITECTURE.md §3.3, §6.2).
 *
 * <p>Responsibility: the thin HTTP layer for {@code /questions}. Validates input, delegates to
 * {@link QuestionService}, wraps results in the single {@link ApiResponse} envelope. No business logic,
 * no {@code @Transactional} (CLAUDE.md §8).</p>
 *
 * <p><b>Authorization design (ARCHITECTURE §6.2):</b></p>
 * <ul>
 *   <li><b>List/view</b> — {@code permitAll()}: answered/open questions are public (PRD §22.6; the service
 *       hides DECLINED/MODERATED).</li>
 *   <li><b>Ask</b> — {@code @RequiresTier("T2")}: asking is a T2 action (PRD §7.3). The service applies the
 *       no-self-question conflict guard (D16). Asking is not binding, so it is not one-per-person and never
 *       balance-gated.</li>
 * </ul>
 */
@RestController
@RequestMapping("/questions")
@Tag(name = "Engagement", description = "Petitions, surveys/polls, and public Q&A (Swahili-first).")
public class QuestionController {

    private final QuestionService questionService;
    private final ResponseFactory responses;
    private final PageRequestFactory pageRequests;
    private final PageMapper pageMapper;

    /**
     * @param questionService Q&A use-cases.
     * @param responses       envelope builder.
     * @param pageRequests    safe {@link Pageable} factory.
     * @param pageMapper      {@code Page}→{@code PageMeta} adapter.
     */
    public QuestionController(QuestionService questionService,
                              ResponseFactory responses,
                              PageRequestFactory pageRequests,
                              PageMapper pageMapper) {
        this.questionService = questionService;
        this.responses = responses;
        this.pageRequests = pageRequests;
        this.pageMapper = pageMapper;
    }

    /**
     * Lists publicly-visible questions (OPEN/ANSWERED), optionally filtered to a representative.
     *
     * @param targetRepId optional representative public id filter (the rep's Q&A inbox), or {@code null}.
     * @param page        zero-based page index.
     * @param size        page size (capped at 100).
     * @param sort        sort expression.
     * @return a paged envelope of {@link QuestionDto} (answer bodies omitted in the list view).
     */
    @GetMapping
    @PreAuthorize("permitAll()")
    @Operation(summary = "List public Q&A questions")
    public ApiResponse<List<QuestionDto>> list(
            @RequestParam(required = false) UUID targetRepId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<QuestionDto> result = questionService.listPublic(targetRepId, pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    /**
     * Fetches a single question by public id, including its answer if present.
     *
     * @param questionId the question's public id.
     * @return an envelope carrying the {@link QuestionDto}.
     */
    @GetMapping("/{questionId}")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Get a question (with answer if answered)")
    public ApiResponse<QuestionDto> get(@PathVariable UUID questionId) {
        return responses.ok(questionService.get(questionId));
    }

    /**
     * Asks a representative a public question (UC-E09). T2-gated; no-self-question enforced in the service.
     *
     * @param request the target rep + question text (asker identity comes from the token).
     * @return {@code 201} + the created {@link QuestionDto}.
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @RequiresTier("T2")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ask a representative a question (T2)")
    public ApiResponse<QuestionDto> ask(@Valid @RequestBody AskQuestionRequest request) {
        QuestionDto dto = questionService.ask(
                CurrentUser.requirePublicId(), request.targetRepId(), request.body());
        return responses.ok(dto);
    }

    /**
     * Publishes the targeted representative's answer to an {@code OPEN} question (UC-E10, US-10.2) — flips it
     * to {@code ANSWERED}.
     *
     * <p><b>Authorization (target-rep only):</b> {@code isAuthenticated()} proves the caller is signed in; the
     * data-dependent rule that the answerer must be the question's <i>targeted</i> representative (D13/D16) is
     * enforced in {@link QuestionService#answer} — a non-target caller is {@code 403}, audited. The answerer is
     * taken from the security context, never the body.</p>
     *
     * @param questionId the question to answer.
     * @param request    the answer text (answerer identity comes from the token).
     * @return an envelope carrying the now-ANSWERED {@link QuestionDto} (with the answer body).
     */
    @PostMapping("/{questionId}/answer")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Answer a question (OPEN → ANSWERED)",
            description = "Targeted representative only. Publishes the answer.")
    public ApiResponse<QuestionDto> answer(@PathVariable UUID questionId,
                                           @Valid @RequestBody AnswerQuestionRequest request) {
        QuestionDto dto = questionService.answer(
                questionId, CurrentUser.requirePublicId(), request.body());
        return responses.ok(dto);
    }
}
