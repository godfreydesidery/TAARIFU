package com.taarifu.moderation.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.pagination.PageMapper;
import com.taarifu.common.pagination.PageRequestFactory;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.moderation.api.dto.AppealDto;
import com.taarifu.moderation.api.dto.AppealSummaryDto;
import com.taarifu.moderation.api.dto.DecideAppealRequest;
import com.taarifu.moderation.api.dto.FileAppealRequest;
import com.taarifu.moderation.application.service.AppealService;
import com.taarifu.moderation.domain.model.enums.AppealStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST surface for appeals against moderation actions (PRD §25.8, UC-H03).
 *
 * <p>Responsibility: the thin HTTP layer for filing and deciding appeals.</p>
 * <ul>
 *   <li><b>File</b> ({@code POST /moderation/actions/{actionId}/appeals}) — any authenticated user; the
 *       service additionally verifies the caller is the actioned content's author (only the affected party
 *       may appeal). The appellant is taken from the security context, never the body.</li>
 *   <li><b>Decide</b> ({@code POST /moderation/appeals/{appealId}/decision}) — {@code hasRole('MODERATOR')};
 *       the service enforces <b>appeal independence</b> (the decider must differ from the moderator who
 *       took the original action — §25.8). This is a live cross-row check, so it lives in the service, not
 *       a static {@code @PreAuthorize}.</li>
 * </ul>
 *
 * <p>No business logic, no {@code @Transactional} (CLAUDE.md §8).</p>
 */
@RestController
@RequestMapping(path = "/moderation")
@Tag(name = "Moderation — Appeals", description = "File + decide appeals (independent moderator).")
public class AppealController {

    private final AppealService appealService;
    private final ResponseFactory responses;
    private final PageRequestFactory pageRequests;
    private final PageMapper pageMapper;

    /**
     * @param appealService appeal use-case service.
     * @param responses     envelope builder.
     * @param pageRequests  bounded {@link Pageable} factory.
     * @param pageMapper    {@code Page → PageMeta} mapper.
     */
    public AppealController(AppealService appealService,
                           ResponseFactory responses,
                           PageRequestFactory pageRequests,
                           PageMapper pageMapper) {
        this.appealService = appealService;
        this.responses = responses;
        this.pageRequests = pageRequests;
        this.pageMapper = pageMapper;
    }

    /**
     * Lists the moderator appeals queue as paged summary rows (UC-H03), optionally filtered by status.
     *
     * <p>Method-secured exactly like the other staff moderation endpoints: {@code hasRole('MODERATOR')}.
     * The staff second-factor (MFA) gate is upstream — a {@code ROLE_MODERATOR} access token is only
     * issued after the TOTP step (AUTH-DESIGN §14.1), so reaching this handler already implies MFA was
     * satisfied, identical to {@code GET /moderation/items}. Authenticated, never public — no
     * {@code SecurityConfig} allow-list change. Viewing the queue carries no conflict-of-interest, so it
     * is open to all moderators; the appeal-independence (D16) fence applies only at decide time.</p>
     *
     * @param status the appeal status to filter by (e.g. {@code OPEN}); omit for all statuses.
     * @param page   zero-based page index.
     * @param size   page size (capped at {@link PageRequestFactory#MAX_SIZE}).
     * @param sort   sort expression (default {@code createdAt,desc} → newest first); e.g. {@code createdAt,asc}.
     * @return {@code 200} + the paged {@link AppealSummaryDto}s with pagination {@code meta}.
     */
    @GetMapping("/appeals")
    @PreAuthorize("hasRole('MODERATOR')")
    @Operation(summary = "List the appeals queue",
            description = "Paged appeal summaries for moderators (optional status filter). ROLE_MODERATOR only.")
    public ApiResponse<List<AppealSummaryDto>> appeals(
            @RequestParam(required = false) AppealStatus status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<AppealSummaryDto> result = appealService.appealQueue(status, pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    /**
     * Files an appeal against a moderation action (UC-H03), allowed only to the affected content author.
     *
     * @param actionId the appealed action's public id.
     * @param request  the validated appeal request (grounds).
     * @return {@code 201} + the created {@link AppealDto}.
     */
    @PostMapping("/actions/{actionId}/appeals")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "File an appeal",
            description = "Only the actioned content's author may appeal (verified server-side).")
    public ResponseEntity<ApiResponse<AppealDto>> fileAppeal(
            @PathVariable UUID actionId,
            @Valid @RequestBody FileAppealRequest request) {
        AppealDto appeal = appealService.fileAppeal(CurrentUser.requirePublicId(), actionId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(responses.ok(appeal));
    }

    /**
     * Decides an open appeal (UC-H03), enforcing appeal independence in the service (D16, §25.8).
     *
     * @param appealId the appeal's public id.
     * @param request  the validated decision request (outcome + note).
     * @return {@code 200} + the decided {@link AppealDto}.
     */
    @PostMapping("/appeals/{appealId}/decision")
    @PreAuthorize("hasRole('MODERATOR')")
    @Operation(summary = "Decide an appeal",
            description = "ROLE_MODERATOR. Blocked if the decider took the original action (§25.8).")
    public ApiResponse<AppealDto> decideAppeal(
            @PathVariable UUID appealId,
            @Valid @RequestBody DecideAppealRequest request) {
        AppealDto appeal = appealService.decideAppeal(CurrentUser.requirePublicId(), appealId, request);
        return responses.ok(appeal);
    }
}
