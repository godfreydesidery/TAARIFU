package com.taarifu.accountability.api.controller;

import com.taarifu.accountability.api.dto.CreateRatingDto;
import com.taarifu.accountability.api.dto.RatingDto;
import com.taarifu.accountability.application.service.RatingService;
import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.security.RequiresTier;
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
 * REST surface for submitting a binding rating — the civic-integrity fence at the edge (PRD §10 Epic M6,
 * US-6.2; §23 fence; D13/D16/D18).
 *
 * <p>Responsibility: a single authenticated, T3-gated endpoint that delegates to {@link RatingService}.
 * The fence is enforced across the annotation + the service:</p>
 * <ul>
 *   <li>{@code @PreAuthorize("isAuthenticated()")} — a principal is required.</li>
 *   <li>{@code @RequiresTier("T3")} — the <b>live</b> tier (DB-resolved by the aspect, never the token
 *       claim) must be T3; a binding action requires verified identity (D13). This annotation is the tier
 *       half of the fence.</li>
 *   <li>The service adds no-self-action, electoral-scope (and representative existence — both via
 *       institutions' published {@code RepresentativeQueryApi}), and one-per-(rater, subject, period) —
 *       and reads <b>no token balance</b> (§23/D18).</li>
 * </ul>
 *
 * <p>The rater is taken from the security context inside the service, never from the request body — a
 * caller can never rate as someone else.</p>
 */
@RestController
@RequestMapping("/ratings")
@Tag(name = "Ratings (binding)",
        description = "Submit a binding T3 rating; fenced from tokens — one person, one rating per period.")
public class RatingController {

    private final RatingService ratingService;
    private final ResponseFactory responses;

    /**
     * @param ratingService the fenced binding-action service.
     * @param responses     envelope builder.
     */
    public RatingController(RatingService ratingService, ResponseFactory responses) {
        this.ratingService = ratingService;
        this.responses = responses;
    }

    /**
     * Submits (or revises the rater's own) binding rating for a subject and period.
     *
     * @param request the validated rating request (subject, score 1..5, period, optional comment).
     * @return {@code 201} + the persisted {@link RatingDto}.
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @RequiresTier("T3")
    @Operation(summary = "Submit a binding rating",
            description = "T3 only; one per (rater, subject, period); no self-rating; tokens never apply (§23).")
    public ResponseEntity<ApiResponse<RatingDto>> submit(@Valid @RequestBody CreateRatingDto request) {
        RatingDto created = ratingService.submit(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(responses.ok(created));
    }
}
