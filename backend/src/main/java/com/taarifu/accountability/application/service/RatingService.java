package com.taarifu.accountability.application.service;

import com.taarifu.accountability.api.dto.CreateRatingDto;
import com.taarifu.accountability.api.dto.RatingDto;
import com.taarifu.accountability.application.mapper.AccountabilityMapper;
import com.taarifu.accountability.domain.model.Rating;
import com.taarifu.accountability.domain.model.enums.RatingSubjectType;
import com.taarifu.accountability.domain.repository.RatingRepository;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.common.security.ScopeGuard;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The binding-action service for representative/office/project ratings — the civic-integrity fence in
 * code (PRD §10 Epic M6, US-6.2; §23 fence; D13/D16/D18).
 *
 * <p>Responsibility: submits a {@link Rating} only after enforcing the full fence, and NEVER reading a
 * token balance. The fence (in order):</p>
 * <ol>
 *   <li><b>Tier (D13):</b> enforced by {@code @RequiresTier("T3")} on the controller method (live-resolved
 *       by the aspect, not the token claim) — so a sub-T3 caller never reaches this service.</li>
 *   <li><b>No self-action (D16):</b> {@link ScopeGuard#isNotSelf} blocks a representative rating their own
 *       subject id → {@link ErrorCode#CONFLICT_OF_INTEREST}.</li>
 *   <li><b>Electoral scope (D13):</b> // TODO(wiring) — a citizen may only rate a representative they are
 *       an elector of; enforce once the institutions/geography electoral mapping is wired
 *       ({@code ScopeGuard.canActInConstituency} on the representative's constituency).</li>
 *   <li><b>One per person (D16):</b> a pre-check plus the DB unique
 *       {@code (subject_type, subject_id, rater, period)} make a duplicate a hard {@link ErrorCode#CONFLICT}
 *       — one person, one rating per period, <b>regardless of token balance</b>.</li>
 * </ol>
 *
 * <p><b>Fence invariant (D18, §23):</b> there is deliberately no token dependency injected or referenced
 * anywhere in this class — tokens can never appear in a binding action's authorization path. The
 * keystone test asserts the rater key in the uniqueness check is the caller's own identity (never a
 * body-supplied id), so a caller can never rate as someone else nor stuff the ballot.</p>
 *
 * <p>Rater identity: the rater key is the caller's immutable identity public id from the security context
 * ({@link CurrentUser#requirePublicId()}) — the same axis {@link ScopeGuard#isNotSelf} compares against,
 * so the self-check and the one-per-person key are consistent. // TODO(wiring): if a distinct profile id
 * is ever required, resolve user→profile via the identity module's public API rather than trusting the
 * body.</p>
 */
@Service
@Transactional
public class RatingService {

    private final RatingRepository ratingRepository;
    private final ScopeGuard scopeGuard;
    private final AccountabilityMapper mapper;

    /**
     * @param ratingRepository append-only rating store (no token-balance access on this path — fence).
     * @param scopeGuard        the common security seam for the no-self-action / electoral-scope checks.
     * @param mapper            entity → DTO mapper.
     */
    public RatingService(RatingRepository ratingRepository, ScopeGuard scopeGuard,
                         AccountabilityMapper mapper) {
        this.ratingRepository = ratingRepository;
        this.scopeGuard = scopeGuard;
        this.mapper = mapper;
    }

    /**
     * Submits (or, for the rater's own existing row, revises) a binding rating after the fence.
     *
     * @param request the validated rating request (subject, score, period, optional comment).
     * @return the persisted {@link RatingDto}.
     * @throws ApiException {@link ErrorCode#CONFLICT_OF_INTEREST} on a self-rating;
     *                      {@link ErrorCode#CONFLICT} on a duplicate (rater, subject, period).
     */
    public RatingDto submit(CreateRatingDto request) {
        UUID rater = CurrentUser.requirePublicId();

        // --- FENCE: no self-action (D16). A representative cannot rate their own subject id. ---
        if (!scopeGuard.isNotSelf(request.subjectId())) {
            throw new ApiException(ErrorCode.CONFLICT_OF_INTEREST);
        }

        // --- FENCE: electoral scope (D13). Only an elector of the representative may rate them. ---
        // TODO(wiring): resolve the representative's constituency via the institutions module and call
        //   scopeGuard.canActInConstituency(constituencyPublicId); deny with OUT_OF_SCOPE otherwise.

        RatingSubjectType subjectType = request.subjectType();
        UUID subjectId = request.subjectId();
        String period = request.period();

        // --- FENCE: one per (rater, subject, period) (D16). Revise the rater's OWN existing row only. ---
        var existing = ratingRepository
                .findBySubjectTypeAndSubjectIdAndRaterProfileIdAndPeriod(subjectType, subjectId, rater, period);
        if (existing.isPresent()) {
            Rating own = existing.get();
            own.revise(request.score(), request.comment());
            return mapper.toRatingDto(ratingRepository.save(own));
        }

        Rating rating = Rating.create(subjectType, subjectId, rater, request.score(),
                request.comment(), period);
        try {
            return mapper.toRatingDto(ratingRepository.saveAndFlush(rating));
        } catch (DataIntegrityViolationException ex) {
            // The DB unique is the final authority against a concurrent double-submit — surface a clean,
            // localised 409 rather than a 500. One person, one rating per period (D16).
            throw new ApiException(ErrorCode.CONFLICT, ex);
        }
    }
}
