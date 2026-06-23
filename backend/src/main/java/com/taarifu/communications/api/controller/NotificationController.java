package com.taarifu.communications.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.pagination.PageMapper;
import com.taarifu.common.pagination.PageRequestFactory;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.communications.api.dto.NotificationDto;
import com.taarifu.communications.application.mapper.CommunicationsMapper;
import com.taarifu.communications.application.service.NotificationQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The citizen's in-app notification list and read receipts (PRD §13, UC-G09, M5).
 *
 * <p>Responsibility: the thin REST surface over {@link NotificationQueryService}. It validates input,
 * delegates, and wraps results in the single {@link ApiResponse} envelope — no business logic, no
 * {@code @Transactional} (CLAUDE.md §8).</p>
 *
 * <p><b>Authorization</b>: a registered citizen reads only their <b>own</b> notifications
 * ({@code @PreAuthorize("isAuthenticated()")}; the service enforces recipient ownership on mark-read).
 * Notifications carry deep-links to potentially private content, so cross-user exposure is never
 * permitted (PRD §18).</p>
 */
@RestController
@RequestMapping("/notifications")
@Tag(name = "Notifications", description = "The citizen's notification inbox.")
public class NotificationController {

    private final NotificationQueryService notificationQueryService;
    private final CommunicationsMapper mapper;
    private final ResponseFactory responses;
    private final PageRequestFactory pageRequests;
    private final PageMapper pageMapper;

    /**
     * @param notificationQueryService list/mark-read orchestration.
     * @param mapper                   entity→DTO mapper.
     * @param responses                envelope builder.
     * @param pageRequests             safe pageable factory.
     * @param pageMapper               page-meta adapter.
     */
    public NotificationController(NotificationQueryService notificationQueryService,
                                  CommunicationsMapper mapper,
                                  ResponseFactory responses,
                                  PageRequestFactory pageRequests,
                                  PageMapper pageMapper) {
        this.notificationQueryService = notificationQueryService;
        this.mapper = mapper;
        this.responses = responses;
        this.pageRequests = pageRequests;
        this.pageMapper = pageMapper;
    }

    /**
     * Lists the caller's notifications, newest first, paged.
     *
     * @param page zero-based page index.
     * @param size page size (capped at 100).
     * @param sort sort expression.
     * @return a paged envelope of {@link NotificationDto}.
     */
    @GetMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List my notifications")
    public ApiResponse<List<NotificationDto>> listMine(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        var pageable = pageRequests.of(page, size, sort);
        var result = notificationQueryService.listMine(CurrentUser.requirePublicId(), pageable)
                .map(mapper::toNotificationDto);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    /**
     * Marks one of the caller's notifications read.
     *
     * @param notificationId the notification's public id.
     * @return {@code 200} + the updated {@link NotificationDto}.
     */
    @PostMapping("/{notificationId}/read")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Mark a notification read")
    public ApiResponse<NotificationDto> markRead(@PathVariable UUID notificationId) {
        var n = notificationQueryService.markRead(CurrentUser.requirePublicId(), notificationId);
        return responses.ok(mapper.toNotificationDto(n));
    }
}
