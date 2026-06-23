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
import com.taarifu.identity.api.ElectoralScopeApi;
import com.taarifu.institutions.api.RepresentativeQueryApi;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
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
 *   <li><b>Electoral scope (D13):</b> a citizen may only rate a representative they are an elector of. The
 *       subject's constituency is resolved via {@link RepresentativeQueryApi#constituencyOf} (institutions);
 *       the rater's single voter-ID-authoritative {@code isElectoral} constituency is checked against it via
 *       {@link ElectoralScopeApi#isElectorOf} (identity). A mismatch is {@link ErrorCode#OUT_OF_SCOPE}. Both
 *       are published api-package query ports — accountability never imports identity/institutions internals
 *       (ADR-0013). A representative with <b>no</b> constituency (councillor/special-seats/nominated) carries
 *       no constituency electoral gate, so this check is skipped for them.</li>
 *   <li><b>One per person (D16):</b> a pre-check plus the DB unique
 *       {@code (subject_type, subject_id, rater, period)} make a duplicate a hard {@link ErrorCode#CONFLICT}
 *       — one person, one rating per period, <b>regardless of token balance</b>.</li>
 * </ol>
 *
 * <p><b>Fence invariant (D18, §23):</b> there is deliberately no token dependency injected or referenced
 * anywhere in this class — tokens can never appear in a binding action's authorization path. The electoral
 * collaborators ({@link RepresentativeQueryApi}, {@link ElectoralScopeApi}) are scope/identity ports, not
 * wallet ports. The keystone test asserts the rater key in the uniqueness check is the caller's own identity
 * (never a body-supplied id), so a caller can never rate as someone else nor stuff the ballot.</p>
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
    private final RepresentativeQueryApi representativeQueryApi;
    private final ElectoralScopeApi electoralScopeApi;
    private final AccountabilityMapper mapper;

    /**
     * @param ratingRepository       append-only rating store (no token-balance access on this path — fence).
     * @param scopeGuard             the common security seam for the no-self-action check (D16).
     * @param representativeQueryApi institutions' published port resolving the subject rep's constituency (D13).
     * @param electoralScopeApi      identity's published port checking the rater's electoral scope (D13).
     * @param mapper                 entity → DTO mapper.
     */
    public RatingService(RatingRepository ratingRepository, ScopeGuard scopeGuard,
                         RepresentativeQueryApi representativeQueryApi,
                         ElectoralScopeApi electoralScopeApi,
                         AccountabilityMapper mapper) {
        this.ratingRepository = ratingRepository;
        this.scopeGuard = scopeGuard;
        this.representativeQueryApi = representativeQueryApi;
        this.electoralScopeApi = electoralScopeApi;
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

        RatingSubjectType subjectType = request.subjectType();
        UUID subjectId = request.subjectId();
        String period = request.period();

        // --- FENCE: electoral scope (D13). Only an elector of the representative may rate them. ---
        // Resolve the subject rep's constituency (institutions' published port; throws NOT_FOUND if the rep
        // does not exist), then check the rater's single isElectoral constituency against it (identity's
        // published port). A rep with NO constituency (councillor/special-seats/nominated) carries no
        // constituency gate, so the check is skipped. NOTE: no token balance is consulted here (fence, §23.5).
        if (subjectType == RatingSubjectType.REPRESENTATIVE) {
            Optional<UUID> repConstituency = representativeQueryApi.constituencyOf(subjectId);
            if (repConstituency.isPresent()
                    && !electoralScopeApi.isElectorOf(rater, repConstituency.get())) {
                throw new ApiException(ErrorCode.OUT_OF_SCOPE);
            }
        }

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
