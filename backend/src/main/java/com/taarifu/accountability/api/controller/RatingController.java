package com.taarifu.accountability.api.controller;

import com.taarifu.accountability.api.dto.CreateRatingDto;
import com.taarifu.accountability.api.dto.CreateRatingReplyDto;
import com.taarifu.accountability.api.dto.RatingDto;
import com.taarifu.accountability.api.dto.RatingReplyDto;
import com.taarifu.accountability.application.service.RatingReplyService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

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
    private final RatingReplyService ratingReplyService;
    private final ResponseFactory responses;

    /**
     * @param ratingService      the fenced binding-action service.
     * @param ratingReplyService the right-of-reply service (ownership fence).
     * @param responses          envelope builder.
     */
    public RatingController(RatingService ratingService, RatingReplyService ratingReplyService,
                            ResponseFactory responses) {
        this.ratingService = ratingService;
        this.ratingReplyService = ratingReplyService;
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

    /**
     * Posts (or revises) the rated representative's <b>own</b> right-of-reply to a rating — the
     * D-rated-fairness rule (US-6.2).
     *
     * <p>{@code @PreAuthorize("isAuthenticated()")} requires a principal; the {@link RatingReplyService}
     * enforces the <b>ownership/conflict-of-interest fence</b> — the caller must be the linked account of the
     * rating's subject representative (a rep may reply to a rating about themselves, never about a rival).
     * Until the real ownership adapter is wired the deny-stub closes this path; the curated on-behalf path
     * (admin) remains available meanwhile. At most one reply per rating (a reply, not a thread).</p>
     *
     * @param ratingId the rating's public id (path).
     * @param request  the validated reply body.
     * @return {@code 201} + the persisted {@link RatingReplyDto}.
     */
    @PostMapping("/{ratingId}/reply")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Post a representative's right-of-reply",
            description = "The rated representative replies to a rating about themselves (ownership-fenced); "
                    + "one reply per rating; never token-gated (§23).")
    public ResponseEntity<ApiResponse<RatingReplyDto>> reply(
            @PathVariable UUID ratingId,
            @Valid @RequestBody CreateRatingReplyDto request) {
        RatingReplyDto created = ratingReplyService.replyAsRepresentative(ratingId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(responses.ok(created));
    }
}
