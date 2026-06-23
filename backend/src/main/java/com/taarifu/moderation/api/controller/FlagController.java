package com.taarifu.moderation.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.common.security.RequiresTier;
import com.taarifu.moderation.api.dto.FlagContentRequest;
import com.taarifu.moderation.api.dto.FlagDto;
import com.taarifu.moderation.application.service.FlagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for citizen content-flagging (PRD §18, US-12.1, UC-E13/H01).
 *
 * <p>Responsibility: the thin HTTP layer for {@code POST /flags}. Open to <b>any authenticated citizen at
 * T1+</b> — flagging is a civic-core safety action and must never be priced or quota-gated (integrity
 * fence, D18, §23.5): there is no token check on this path. Authorization is {@code isAuthenticated()}
 * plus {@link RequiresTier}{@code ("T1")} (enforced live by the {@code RequiresTierAspect}); the flagger
 * is taken from the security context, never the body. No business logic, no {@code @Transactional}
 * (CLAUDE.md §8).</p>
 */
@RestController
@RequestMapping(path = "/flags")
@Tag(name = "Moderation — Flags", description = "Citizen content-flagging (T1+, any authenticated).")
public class FlagController {

    private final FlagService flagService;
    private final ResponseFactory responses;

    /**
     * @param flagService flag use-case service.
     * @param responses   envelope builder.
     */
    public FlagController(FlagService flagService, ResponseFactory responses) {
        this.flagService = flagService;
        this.responses = responses;
    }

    /**
     * Flags a piece of content for moderator review.
     *
     * @param request the validated flag request (subject ref + reason + optional detail).
     * @return {@code 201} + the created {@link FlagDto} (feedback to the flagger — US-12.1).
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @RequiresTier("T1")
    @Operation(summary = "Flag content",
            description = "Any authenticated citizen (T1+). Never token-gated (integrity fence D18).")
    public ResponseEntity<ApiResponse<FlagDto>> flag(@Valid @RequestBody FlagContentRequest request) {
        FlagDto created = flagService.flag(CurrentUser.requirePublicId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(responses.ok(created));
    }
}
