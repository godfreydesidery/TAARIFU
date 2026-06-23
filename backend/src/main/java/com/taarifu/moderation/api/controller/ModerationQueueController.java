package com.taarifu.moderation.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.pagination.PageMapper;
import com.taarifu.common.pagination.PageRequestFactory;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.moderation.api.dto.ModerationActionDto;
import com.taarifu.moderation.api.dto.ModerationItemDto;
import com.taarifu.moderation.api.dto.TakeActionRequest;
import com.taarifu.moderation.application.service.ModerationQueueService;
import com.taarifu.moderation.domain.model.enums.ModerationItemStatus;
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
 * REST surface for the moderator queue (PRD §18, US-12.2, UC-H01/H02, §25.8).
 *
 * <p>Responsibility: the thin HTTP layer for listing the prioritised queue and taking an action. Every
 * endpoint is gated by {@code hasRole('MODERATOR')} (deny-by-default method security); the
 * conflict-of-interest guard (D16 — a moderator may not action their own content) is enforced in
 * {@link ModerationQueueService}, because it requires the queue item's recorded subject author and so
 * cannot be a static {@code @PreAuthorize} expression. The acting moderator is taken from the security
 * context, never the body. No business logic, no {@code @Transactional} (CLAUDE.md §8).</p>
 */
@RestController
@RequestMapping(path = "/moderation/items")
@Tag(name = "Moderation — Queue", description = "Moderator queue + actions (ROLE_MODERATOR).")
public class ModerationQueueController {

    private final ModerationQueueService queueService;
    private final ResponseFactory responses;
    private final PageRequestFactory pageRequests;
    private final PageMapper pageMapper;

    /**
     * @param queueService moderator-queue use-case service.
     * @param responses    envelope builder.
     * @param pageRequests bounded {@link Pageable} factory.
     * @param pageMapper   {@code Page → PageMeta} mapper.
     */
    public ModerationQueueController(ModerationQueueService queueService,
                                     ResponseFactory responses,
                                     PageRequestFactory pageRequests,
                                     PageMapper pageMapper) {
        this.queueService = queueService;
        this.responses = responses;
        this.pageRequests = pageRequests;
        this.pageMapper = pageMapper;
    }

    /**
     * Lists the queue by status, prioritised by severity then SLA deadline (UC-H01).
     *
     * @param status the queue status to list (default {@code PENDING}).
     * @param page   zero-based page index.
     * @param size   page size (capped at {@link PageRequestFactory#MAX_SIZE}).
     * @param sort   sort expression (default {@code severity,desc}); e.g. {@code slaDueAt,asc}.
     * @return {@code 200} + the paged {@link ModerationItemDto}s with pagination {@code meta}.
     */
    @GetMapping
    @PreAuthorize("hasRole('MODERATOR')")
    @Operation(summary = "List the moderation queue",
            description = "Prioritised by severity + SLA. ROLE_MODERATOR only.")
    public ApiResponse<List<ModerationItemDto>> queue(
            @RequestParam(defaultValue = "PENDING") ModerationItemStatus status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(defaultValue = "severity,desc") String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<ModerationItemDto> result = queueService.queue(status, pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    /**
     * Takes a moderation action on a queue item (UC-H02), enforcing the D16 self-action guard in the
     * service.
     *
     * @param itemId  the queue item's public id.
     * @param request the validated action request.
     * @return {@code 201} + the recorded {@link ModerationActionDto}.
     */
    @PostMapping("/{itemId}/actions")
    @PreAuthorize("hasRole('MODERATOR')")
    @Operation(summary = "Take a moderation action",
            description = "ROLE_MODERATOR. Blocked if the moderator authored the subject (D16).")
    public ResponseEntity<ApiResponse<ModerationActionDto>> takeAction(
            @PathVariable UUID itemId,
            @Valid @RequestBody TakeActionRequest request) {
        ModerationActionDto action =
                queueService.takeAction(CurrentUser.requirePublicId(), itemId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(responses.ok(action));
    }
}
