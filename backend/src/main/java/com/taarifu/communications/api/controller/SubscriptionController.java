package com.taarifu.communications.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.pagination.PageMapper;
import com.taarifu.common.pagination.PageRequestFactory;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.common.security.RequiresTier;
import com.taarifu.communications.api.dto.FollowRequest;
import com.taarifu.communications.api.dto.SubscriptionDto;
import com.taarifu.communications.application.mapper.CommunicationsMapper;
import com.taarifu.communications.application.service.SubscriptionService;
import com.taarifu.communications.domain.model.Subscription;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Citizen subscription (follow) management — PRD §9.1, UC-G05, M4.
 *
 * <p>Responsibility: the thin REST surface over {@link SubscriptionService}. It validates input,
 * delegates, and wraps results in the single {@link ApiResponse} envelope — no business logic, no
 * {@code @Transactional} (CLAUDE.md §8).</p>
 *
 * <p><b>Authorization</b>: following requires a registered citizen — {@code @PreAuthorize("isAuthenticated()")}
 * plus {@link RequiresTier T1} (the PRD-locked tier at which a citizen may "follow representatives/areas,
 * subscribe to feed", PRD §7.3). A citizen manages only their own follows (the service enforces ownership
 * on unfollow). This is a non-binding civic action: it never reads a token balance and is not part of the
 * integrity fence (PRD §23.5) — following is pure discovery/reach.</p>
 */
@RestController
@RequestMapping("/subscriptions")
@Tag(name = "Subscriptions", description = "Follow areas, representatives, and categories for the feed.")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final CommunicationsMapper mapper;
    private final ResponseFactory responses;
    private final PageRequestFactory pageRequests;
    private final PageMapper pageMapper;

    /**
     * @param subscriptionService follow/unfollow/list orchestration.
     * @param mapper              entity→DTO mapper.
     * @param responses           envelope builder.
     * @param pageRequests        safe pageable factory.
     * @param pageMapper          page-meta adapter.
     */
    public SubscriptionController(SubscriptionService subscriptionService,
                                  CommunicationsMapper mapper,
                                  ResponseFactory responses,
                                  PageRequestFactory pageRequests,
                                  PageMapper pageMapper) {
        this.subscriptionService = subscriptionService;
        this.mapper = mapper;
        this.responses = responses;
        this.pageRequests = pageRequests;
        this.pageMapper = pageMapper;
    }

    /**
     * Follows a target (idempotent).
     *
     * @param request the validated follow request (target type + id).
     * @return {@code 201} + the {@link SubscriptionDto}.
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @RequiresTier("T1")
    @Operation(summary = "Follow a target (area/representative/category)")
    public ResponseEntity<ApiResponse<SubscriptionDto>> follow(@Valid @RequestBody FollowRequest request) {
        Subscription s = subscriptionService.follow(
                CurrentUser.requirePublicId(), request.targetType(), request.targetId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responses.ok(mapper.toSubscriptionDto(s)));
    }

    /**
     * Unfollows a target (idempotent soft-delete).
     *
     * @param subscriptionId the follow edge's public id.
     * @return {@code 200} with no body.
     */
    @DeleteMapping("/{subscriptionId}")
    @PreAuthorize("isAuthenticated()")
    @RequiresTier("T1")
    @Operation(summary = "Unfollow")
    public ResponseEntity<ApiResponse<Void>> unfollow(@PathVariable UUID subscriptionId) {
        subscriptionService.unfollow(CurrentUser.requirePublicId(), subscriptionId);
        return ResponseEntity.ok(responses.ok(null));
    }

    /**
     * Lists the caller's follows, paged.
     *
     * @param page zero-based page index.
     * @param size page size (capped at 100).
     * @param sort sort expression.
     * @return a paged envelope of {@link SubscriptionDto}.
     */
    @GetMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    @RequiresTier("T1")
    @Operation(summary = "List my follows")
    public ApiResponse<List<SubscriptionDto>> listMine(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        var pageable = pageRequests.of(page, size, sort);
        var result = subscriptionService.listMyFollows(CurrentUser.requirePublicId(), pageable)
                .map(mapper::toSubscriptionDto);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }
}
