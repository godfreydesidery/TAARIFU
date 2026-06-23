package com.taarifu.accountability.application.service;

import com.taarifu.accountability.api.dto.CreateRatingDto;
import com.taarifu.accountability.api.dto.RatingDto;
import com.taarifu.accountability.application.mapper.AccountabilityMapper;
import com.taarifu.accountability.domain.model.Rating;
import com.taarifu.accountability.domain.model.enums.RatingSubjectType;
import com.taarifu.accountability.domain.repository.RatingRepository;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
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
 *   <li><b>Electoral scope (D13, two-tier — F1):</b> a citizen may only rate a representative they are an
 *       elector of, gated by the subject's <b>mandate</b>:
 *       <ul>
 *         <li>a <b>constituency-mandate MP</b> → the rater must be an elector of the rep's constituency
 *             ({@link RepresentativeQueryApi#constituencyOf} × {@link ElectoralScopeApi#isElectorOf});</li>
 *         <li>a <b>councillor / ward executive (Diwani)</b> → the rater must be an elector of the rep's
 *             <b>ward</b> ({@link RepresentativeQueryApi#wardOf} × {@link ElectoralScopeApi#isElectorOfWard})
 *             — a councillor holds a Ward (Kata), so the gate is the ward, not a constituency (F1: previously
 *             skipped, letting anyone nationwide rate any councillor);</li>
 *         <li>a genuinely seat-less rep (<b>special-seats/Viti Maalum, nominated</b>) → no geographic gate
 *             (PRD §22.6).</li>
 *       </ul>
 *       A mismatch is {@link ErrorCode#OUT_OF_SCOPE}, audited. Both ports are published api-package query
 *       ports — accountability never imports identity/institutions internals (ADR-0013).</li>
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
    private final AuditEventService audit;

    /**
     * @param ratingRepository       append-only rating store (no token-balance access on this path — fence).
     * @param scopeGuard             the common security seam for the no-self-action check (D16).
     * @param representativeQueryApi institutions' published port resolving the subject rep's constituency/ward (D13).
     * @param electoralScopeApi      identity's published port checking the rater's electoral scope (D13).
     * @param mapper                 entity → DTO mapper.
     * @param audit                  append-only audit writer (binding-success {@code RATING_SUBMITTED} +
     *                               out-of-scope denial evidence; refs/public-ids only, never PII — R-4, L-1).
     */
    public RatingService(RatingRepository ratingRepository, ScopeGuard scopeGuard,
                         RepresentativeQueryApi representativeQueryApi,
                         ElectoralScopeApi electoralScopeApi,
                         AccountabilityMapper mapper,
                         AuditEventService audit) {
        this.ratingRepository = ratingRepository;
        this.scopeGuard = scopeGuard;
        this.representativeQueryApi = representativeQueryApi;
        this.electoralScopeApi = electoralScopeApi;
        this.mapper = mapper;
        this.audit = audit;
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

        // --- FENCE: electoral scope (D13, two-tier — F1). Only an elector of the representative's seat may
        // rate them. The gate keys off the rep's MANDATE, resolved via institutions' published ports (both
        // throw NOT_FOUND if the rep does not exist — cannot rate a phantom):
        //   * constituency-mandate MP  -> rater must be an elector of the rep's CONSTITUENCY;
        //   * councillor / ward-exec   -> rater must be an elector of the rep's WARD (F1: previously skipped);
        //   * special-seats / nominated -> no geographic seat, no geographic gate (PRD §22.6).
        // The rater's electoral location is checked via identity's published ports. NOTE: no token balance is
        // ever consulted here (fence, §23.5).
        if (subjectType == RatingSubjectType.REPRESENTATIVE && !isElectorOfRepSeat(rater, subjectId)) {
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTHZ_SCOPE_DENIED, AuditOutcome.DENIED)
                    .actor(rater)
                    .subject(subjectId)
                    .reason("RATE_REP_OUT_OF_ELECTORAL_SCOPE")
                    .build());
            throw new ApiException(ErrorCode.OUT_OF_SCOPE);
        }

        // --- FENCE: one per (rater, subject, period) (D16). Revise the rater's OWN existing row only. ---
        var existing = ratingRepository
                .findBySubjectTypeAndSubjectIdAndRaterProfileIdAndPeriod(subjectType, subjectId, rater, period);
        if (existing.isPresent()) {
            Rating own = existing.get();
            own.revise(request.score(), request.comment());
            RatingDto revised = mapper.toRatingDto(ratingRepository.save(own));
            auditSubmitted(rater, subjectId, subjectType, period);
            return revised;
        }

        Rating rating = Rating.create(subjectType, subjectId, rater, request.score(),
                request.comment(), period);
        try {
            RatingDto created = mapper.toRatingDto(ratingRepository.saveAndFlush(rating));
            auditSubmitted(rater, subjectId, subjectType, period);
            return created;
        } catch (DataIntegrityViolationException ex) {
            // The DB unique is the final authority against a concurrent double-submit — surface a clean,
            // localised 409 rather than a 500. One person, one rating per period (D16).
            throw new ApiException(ErrorCode.CONFLICT, ex);
        }
    }

    /**
     * Resolves whether the rater is an elector of the subject representative's seat, dispatching on the
     * rep's mandate (F1, D13): a constituency-mandate MP is gated on the rep's constituency; a
     * councillor/ward-exec on the rep's ward; a genuinely seat-less rep (special-seats/nominated) carries no
     * geographic gate and is always allowed. Both ports throw {@code NOT_FOUND} for a non-existent rep.
     *
     * @param rater     the authenticated rater's account public id.
     * @param subjectId the subject representative's public id.
     * @return {@code true} if the rater may rate this rep on the electoral-scope axis.
     */
    private boolean isElectorOfRepSeat(UUID rater, UUID subjectId) {
        Optional<UUID> constituency = representativeQueryApi.constituencyOf(subjectId);
        if (constituency.isPresent()) {
            return electoralScopeApi.isElectorOf(rater, constituency.get());
        }
        // No constituency: a councillor/ward-exec is ward-tier — gate on the ward (F1).
        Optional<UUID> ward = representativeQueryApi.wardOf(subjectId);
        if (ward.isPresent()) {
            return electoralScopeApi.isElectorOfWard(rater, ward.get());
        }
        // Genuinely seat-less (special-seats / nominated) — no geographic electoral gate (PRD §22.6).
        return true;
    }

    /**
     * Appends a {@link AuditEventType#RATING_SUBMITTED} success event (R-4): the most sensitive civic acts
     * carry a complete immutable trail. References/public-ids and a non-PII reason only (the subject type +
     * period as the reason code) — never the score, comment, or any PII (PRD §18, PDPA).
     *
     * @param rater       the rater's account public id (actor).
     * @param subjectId   the rated subject's public id (subject).
     * @param subjectType the rated subject's type.
     * @param period      the rating period (non-PII reason context).
     */
    private void auditSubmitted(UUID rater, UUID subjectId, RatingSubjectType subjectType, String period) {
        audit.record(AuditEvent.Builder
                .of(AuditEventType.RATING_SUBMITTED, AuditOutcome.SUCCESS)
                .actor(rater)
                .subject(subjectId)
                .reason(subjectType.name() + ":" + period)
                .build());
    }
}
