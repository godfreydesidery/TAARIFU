package com.taarifu.accountability.application.service;

import com.taarifu.accountability.api.dto.CreateRatingReplyDto;
import com.taarifu.accountability.api.dto.RatingReplyDto;
import com.taarifu.accountability.application.mapper.AccountabilityMapper;
import com.taarifu.accountability.domain.model.Rating;
import com.taarifu.accountability.domain.model.RatingReply;
import com.taarifu.accountability.domain.model.enums.RatingSubjectType;
import com.taarifu.accountability.domain.port.RepresentativeOwnershipPort;
import com.taarifu.accountability.domain.repository.RatingReplyRepository;
import com.taarifu.accountability.domain.repository.RatingRepository;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.common.security.CurrentUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Application service for a representative's <b>right-of-reply</b> to a rating — the D-rated-fairness rule and
 * its ownership/conflict-of-interest fence (PRD §10 Epic M6, US-6.2; PDPA fairness).
 *
 * <p>Responsibility: post (or revise) the single reply a rated representative is entitled to, after enforcing
 * the fence. Two entry points, two authorities — kept as separate methods so each path's guard is explicit
 * (SOLID single-responsibility; mirrors the {@code RatingService}/{@code CurationService} split):</p>
 * <ol>
 *   <li><b>Self-reply ({@link #replyAsRepresentative}):</b> the rated representative replies in their own
 *       voice. The <b>ownership fence</b> ({@link RepresentativeOwnershipPort}) requires the authenticated
 *       account to be the linked account of the rating's subject representative — a rep can reply to a rating
 *       about <i>themselves</i>, never about a rival (conflict-of-interest, D16). A mismatch is
 *       {@link ErrorCode#CONFLICT_OF_INTEREST}, audited. <b>Deny-by-default:</b> until the real ownership
 *       adapter is wired the stub denies every account, so this path is safely closed (CLAUDE.md §3).</li>
 *   <li><b>Curated reply ({@link #replyAsCurator}):</b> an {@code ADMIN}/{@code ROOT} curator posts the reply
 *       on the representative's behalf (D-Q4 curated authorship — how a representative without a linked
 *       account still exercises their right of reply). The endpoint's {@code @PreAuthorize("hasRole('ADMIN')")}
 *       is the authority; this path does not consult the ownership port.</li>
 * </ol>
 *
 * <p><b>The one-per-rating fairness cap (D-rated-fairness):</b> both paths revise the <i>existing</i> reply if
 * one is present, and a concurrent second insert is caught by the DB unique {@code ux_rating_reply_one_per_rating}
 * and surfaced as a clean {@link ErrorCode#CONFLICT}. A representative gets a reply, not a thread — a fair,
 * bounded counterweight, never a megaphone (US-6.2).</p>
 *
 * <p><b>Subject must be a REPRESENTATIVE:</b> a right-of-reply is a representative's prerogative, so a reply is
 * only ever accepted for a {@link RatingSubjectType#REPRESENTATIVE} rating; an {@code OFFICE}/{@code PROJECT}
 * rating has no representative to reply, so it is rejected {@link ErrorCode#CONFLICT_OF_INTEREST} (no one is
 * entitled to reply). <b>No token balance</b> is read anywhere on this path — fairness is never token-gated
 * (§23 fence).</p>
 */
@Service
@Transactional
public class RatingReplyService {

    private final RatingRepository ratingRepository;
    private final RatingReplyRepository ratingReplyRepository;
    private final RepresentativeOwnershipPort ownershipPort;
    private final AccountabilityMapper mapper;
    private final AuditEventService audit;

    /**
     * @param ratingRepository      rating lookup (the answered rating; no token-balance read — fence).
     * @param ratingReplyRepository append-only-ish reply store (one-per-rating cap + revise lookup).
     * @param ownershipPort         the right-of-reply ownership fence; default deny-stub until the real
     *                              institutions-backed adapter is wired (CENTRAL NEED).
     * @param mapper                entity → DTO mapper.
     * @param audit                 append-only audit writer ({@code RATING_REPLY_POSTED} success +
     *                              conflict-of-interest denial evidence; refs/codes only, never PII — L-1).
     */
    public RatingReplyService(RatingRepository ratingRepository,
                              RatingReplyRepository ratingReplyRepository,
                              RepresentativeOwnershipPort ownershipPort,
                              AccountabilityMapper mapper,
                              AuditEventService audit) {
        this.ratingRepository = ratingRepository;
        this.ratingReplyRepository = ratingReplyRepository;
        this.ownershipPort = ownershipPort;
        this.mapper = mapper;
        this.audit = audit;
    }

    /**
     * Posts (or revises) the rated representative's <b>own</b> right-of-reply, after the ownership fence.
     *
     * @param ratingPublicId the rating being answered.
     * @param request        the validated reply body.
     * @return the persisted {@link RatingReplyDto}.
     * @throws ResourceNotFoundException if no live rating has that id.
     * @throws ApiException {@link ErrorCode#CONFLICT_OF_INTEREST} if the caller is not the rating's subject
     *                      representative (or the rating is not about a representative);
     *                      {@link ErrorCode#CONFLICT} on a concurrent second reply.
     */
    public RatingReplyDto replyAsRepresentative(UUID ratingPublicId, CreateRatingReplyDto request) {
        UUID author = CurrentUser.requirePublicId();
        Rating rating = requireRepresentativeRating(ratingPublicId, author);
        UUID representativeId = rating.getSubjectId();

        // --- FENCE: ownership / conflict-of-interest (D16). The caller may reply ONLY to a rating about the
        // representative whose linked account they are — never a rating about a rival. Resolved via the
        // ownership port (deny-by-default until the real adapter is wired). No token balance is consulted. ---
        if (!ownershipPort.isLinkedAccountOf(author, representativeId)) {
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTHZ_SELF_ACTION_BLOCKED, AuditOutcome.DENIED)
                    .actor(author)
                    .subject(representativeId)
                    .reason("RATING_REPLY_NOT_RATING_SUBJECT")
                    .build());
            throw new ApiException(ErrorCode.CONFLICT_OF_INTEREST);
        }

        return upsertReply(rating, representativeId, author, false, request.body());
    }

    /**
     * Posts (or revises) a curated right-of-reply on the representative's behalf (D-Q4). The endpoint's
     * {@code ADMIN}/{@code ROOT} method security is the authority; the ownership port is not consulted.
     *
     * @param ratingPublicId the rating being answered.
     * @param request        the validated reply body.
     * @return the persisted {@link RatingReplyDto} (flagged {@code onBehalf=true}).
     * @throws ResourceNotFoundException if no live rating has that id.
     * @throws ApiException {@link ErrorCode#CONFLICT_OF_INTEREST} if the rating is not about a representative
     *                      (no one is entitled to reply); {@link ErrorCode#CONFLICT} on a concurrent second
     *                      reply.
     */
    public RatingReplyDto replyAsCurator(UUID ratingPublicId, CreateRatingReplyDto request) {
        UUID curator = CurrentUser.requirePublicId();
        Rating rating = requireRepresentativeRating(ratingPublicId, curator);
        return upsertReply(rating, rating.getSubjectId(), curator, true, request.body());
    }

    /**
     * Loads the answered rating and enforces that it is about a REPRESENTATIVE — only a representative has a
     * right of reply, so a reply to an {@code OFFICE}/{@code PROJECT} rating is a conflict-of-interest (no one
     * is entitled to it). Records a denial for the non-representative case.
     *
     * @param ratingPublicId the rating's public id.
     * @param actor          the acting account (for the denial audit actor).
     * @return the live REPRESENTATIVE rating.
     */
    private Rating requireRepresentativeRating(UUID ratingPublicId, UUID actor) {
        Rating rating = ratingRepository.findByPublicId(ratingPublicId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "accountability.rating.notFound", ratingPublicId));
        if (rating.getSubjectType() != RatingSubjectType.REPRESENTATIVE) {
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTHZ_SELF_ACTION_BLOCKED, AuditOutcome.DENIED)
                    .actor(actor)
                    .subject(rating.getSubjectId())
                    .reason("RATING_REPLY_SUBJECT_NOT_REPRESENTATIVE")
                    .build());
            throw new ApiException(ErrorCode.CONFLICT_OF_INTEREST);
        }
        return rating;
    }

    /**
     * Shared persistence path for both authorities: revise the existing reply (the one-per-rating cap means a
     * reply already present is edited, never duplicated) or create the first one, surfacing a concurrent
     * second insert as a clean {@link ErrorCode#CONFLICT}. Appends the {@code RATING_REPLY_POSTED} audit on
     * success.
     *
     * @param rating           the answered rating.
     * @param representativeId the rated representative's public id (= {@code rating.getSubjectId()}).
     * @param author          the replying account's public id (from the security context).
     * @param onBehalf        {@code true} for a curator on-behalf reply, {@code false} for a self-reply.
     * @param body            the reply text.
     * @return the persisted reply DTO.
     */
    private RatingReplyDto upsertReply(Rating rating, UUID representativeId, UUID author,
                                       boolean onBehalf, String body) {
        var existing = ratingReplyRepository.findByRating(rating);
        if (existing.isPresent()) {
            RatingReply reply = existing.get();
            reply.revise(body);
            RatingReplyDto revised = mapper.toRatingReplyDto(ratingReplyRepository.save(reply));
            auditPosted(author, representativeId, onBehalf);
            return revised;
        }

        RatingReply reply = RatingReply.create(rating, representativeId, author, onBehalf, body);
        try {
            RatingReplyDto created = mapper.toRatingReplyDto(ratingReplyRepository.saveAndFlush(reply));
            auditPosted(author, representativeId, onBehalf);
            return created;
        } catch (DataIntegrityViolationException ex) {
            // The DB unique (one reply per rating) is the final authority against a concurrent double-post —
            // surface a clean, localised 409 rather than a 500. A reply, not a thread (US-6.2).
            throw new ApiException(ErrorCode.CONFLICT, ex);
        }
    }

    /**
     * Appends a {@link AuditEventType#RATING_REPLY_POSTED} success event. References/codes only — the reply
     * mode ({@code SELF}/{@code CURATED}) as the non-PII reason; never the reply body, the rating, or any PII
     * (PRD §18, PDPA, L-1).
     *
     * @param author          the replying account's public id (actor).
     * @param representativeId the rated representative's public id (subject).
     * @param onBehalf        whether this was a curator on-behalf reply.
     */
    private void auditPosted(UUID author, UUID representativeId, boolean onBehalf) {
        audit.record(AuditEvent.Builder
                .of(AuditEventType.RATING_REPLY_POSTED, AuditOutcome.SUCCESS)
                .actor(author)
                .subject(representativeId)
                .reason(onBehalf ? "CURATED" : "SELF")
                .build());
    }
}
