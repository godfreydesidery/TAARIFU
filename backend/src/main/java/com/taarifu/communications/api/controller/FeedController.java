package com.taarifu.communications.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.pagination.PageMapper;
import com.taarifu.common.pagination.PageRequestFactory;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.common.security.RequiresTier;
import com.taarifu.communications.api.dto.FeedItemDto;
import com.taarifu.communications.application.service.FeedQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The citizen's personalised feed (PRD §12 UC-G04, §22.6, M4).
 *
 * <p>Responsibility: the thin REST surface over {@link FeedQueryService}. It validates paging, delegates,
 * and wraps the result in the single {@link ApiResponse} envelope — no business logic, no
 * {@code @Transactional} (CLAUDE.md §8).</p>
 *
 * <p><b>Authorization</b>: the personalised feed is a registered-citizen capability —
 * {@code @PreAuthorize("isAuthenticated()")} plus {@link RequiresTier T1} (the tier at which a citizen may
 * "subscribe to feed", PRD §7.3). The feed is built strictly from the <b>caller's own</b> follows, so one
 * citizen can never read another's personalised feed (PRD §18). Items are lean (snippet only) for the
 * feature-phone data budget (PRD §15).</p>
 */
@RestController
@RequestMapping("/feed")
@Tag(name = "Feed", description = "The citizen's personalised announcement feed.")
public class FeedController {

    private final FeedQueryService feedQueryService;
    private final ResponseFactory responses;
    private final PageRequestFactory pageRequests;
    private final PageMapper pageMapper;

    /**
     * @param feedQueryService feed assembly (read-only).
     * @param responses        envelope builder.
     * @param pageRequests     safe pageable factory.
     * @param pageMapper       page-meta adapter.
     */
    public FeedController(FeedQueryService feedQueryService,
                          ResponseFactory responses,
                          PageRequestFactory pageRequests,
                          PageMapper pageMapper) {
        this.feedQueryService = feedQueryService;
        this.responses = responses;
        this.pageRequests = pageRequests;
        this.pageMapper = pageMapper;
    }

    /**
     * Returns the caller's personalised feed page (newest announcements first).
     *
     * @param page zero-based page index.
     * @param size page size (capped at 100).
     * @param sort sort expression (defaults to publish time desc in the query).
     * @return a paged envelope of {@link FeedItemDto}.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @RequiresTier("T1")
    @Operation(summary = "Get my personalised feed",
            description = "Live, non-expired announcements from my followed areas/categories, newest first.")
    public ApiResponse<List<FeedItemDto>> myFeed(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        var pageable = pageRequests.of(page, size, sort);
        Page<FeedItemDto> result = feedQueryService.getFeed(CurrentUser.requirePublicId(), pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }
}
