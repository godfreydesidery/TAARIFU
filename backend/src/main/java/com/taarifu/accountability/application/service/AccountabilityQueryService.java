package com.taarifu.accountability.application.service;

import com.taarifu.accountability.api.dto.AttendanceDto;
import com.taarifu.accountability.api.dto.AttendanceSummaryDto;
import com.taarifu.accountability.api.dto.ContributionDto;
import com.taarifu.accountability.api.dto.PromiseDto;
import com.taarifu.accountability.api.dto.PromiseStatusEntryDto;
import com.taarifu.accountability.api.dto.RatingReplyDto;
import com.taarifu.accountability.api.dto.RatingSummaryDto;
import com.taarifu.accountability.application.mapper.AccountabilityMapper;
import com.taarifu.accountability.domain.model.Promise;
import com.taarifu.accountability.domain.model.enums.ContributionType;
import com.taarifu.accountability.domain.model.enums.PromiseStatus;
import com.taarifu.accountability.domain.model.enums.RatingSubjectType;
import com.taarifu.accountability.domain.repository.AttendanceRepository;
import com.taarifu.accountability.domain.repository.PromiseRepository;
import com.taarifu.accountability.domain.repository.PromiseStatusEntryRepository;
import com.taarifu.accountability.domain.repository.RatingReplyRepository;
import com.taarifu.accountability.domain.repository.RatingRepository;
import com.taarifu.accountability.domain.repository.RepresentativeContributionRepository;
import com.taarifu.common.error.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only application service for public accountability reads (PRD §10 Epic M6, US-6.1/6.2/6.3).
 *
 * <p>Responsibility: serves the citizen-facing reads — a representative's contributions, attendance (rows
 * + computed summary), promises, and a subject's <b>aggregate</b> rating. Returns DTOs only (entities
 * never leak — CLAUDE.md §8) and owns the read-only transaction boundary.</p>
 *
 * <p>WHY individual ratings are NOT exposed here, only the aggregate: US-6.2 publishes the aggregate
 * score; per-rater rows are private to the rater. The aggregate is computed from append-only rows and no
 * token balance contributes to it (§23 fence, D18).</p>
 */
@Service
@Transactional(readOnly = true)
public class AccountabilityQueryService {

    private final RepresentativeContributionRepository contributionRepository;
    private final AttendanceRepository attendanceRepository;
    private final PromiseRepository promiseRepository;
    private final PromiseStatusEntryRepository promiseStatusEntryRepository;
    private final RatingRepository ratingRepository;
    private final RatingReplyRepository ratingReplyRepository;
    private final AccountabilityMapper mapper;

    /**
     * @param contributionRepository       contribution persistence port.
     * @param attendanceRepository         attendance persistence port.
     * @param promiseRepository            promise persistence port.
     * @param promiseStatusEntryRepository promise status-timeline persistence port (US-6.3 timeline read).
     * @param ratingRepository             rating persistence port (aggregate read only on this path).
     * @param ratingReplyRepository        right-of-reply persistence port (reply-with-rating read).
     * @param mapper                       entity/aggregate → DTO mapper.
     */
    public AccountabilityQueryService(RepresentativeContributionRepository contributionRepository,
                                      AttendanceRepository attendanceRepository,
                                      PromiseRepository promiseRepository,
                                      PromiseStatusEntryRepository promiseStatusEntryRepository,
                                      RatingRepository ratingRepository,
                                      RatingReplyRepository ratingReplyRepository,
                                      AccountabilityMapper mapper) {
        this.contributionRepository = contributionRepository;
        this.attendanceRepository = attendanceRepository;
        this.promiseRepository = promiseRepository;
        this.promiseStatusEntryRepository = promiseStatusEntryRepository;
        this.ratingRepository = ratingRepository;
        this.ratingReplyRepository = ratingReplyRepository;
        this.mapper = mapper;
    }

    /**
     * Lists a representative's contributions, optionally filtered by type.
     *
     * @param representativeId the subject representative's public id.
     * @param type             the type to filter by, or {@code null} for all types.
     * @param pageable         paging/sorting.
     * @return a page of {@link ContributionDto}.
     */
    public Page<ContributionDto> listContributions(UUID representativeId, ContributionType type,
                                                   Pageable pageable) {
        Page<?> page = type == null
                ? contributionRepository.findByRepresentativeId(representativeId, pageable)
                : contributionRepository.findByRepresentativeIdAndType(representativeId, type, pageable);
        // Cast is safe: both branches return Page<RepresentativeContribution>.
        @SuppressWarnings("unchecked")
        Page<com.taarifu.accountability.domain.model.RepresentativeContribution> typed =
                (Page<com.taarifu.accountability.domain.model.RepresentativeContribution>) page;
        return typed.map(mapper::toContributionDto);
    }

    /**
     * Lists a representative's per-session attendance rows.
     *
     * @param representativeId the subject representative's public id.
     * @param pageable         paging/sorting.
     * @return a page of {@link AttendanceDto}.
     */
    public Page<AttendanceDto> listAttendance(UUID representativeId, Pageable pageable) {
        return attendanceRepository.findByRepresentativeId(representativeId, pageable)
                .map(mapper::toAttendanceDto);
    }

    /**
     * Computes a representative's attendance summary (present/total/rate).
     *
     * @param representativeId the subject representative's public id.
     * @return the {@link AttendanceSummaryDto}.
     */
    public AttendanceSummaryDto attendanceSummary(UUID representativeId) {
        return mapper.toAttendanceSummaryDto(representativeId,
                attendanceRepository.aggregateByRepresentativeId(representativeId));
    }

    /**
     * Lists a representative's promises, optionally filtered by status.
     *
     * @param representativeId the subject representative's public id.
     * @param status           the status to filter by, or {@code null} for all.
     * @param pageable         paging/sorting.
     * @return a page of {@link PromiseDto}.
     */
    public Page<PromiseDto> listPromises(UUID representativeId, PromiseStatus status, Pageable pageable) {
        Page<com.taarifu.accountability.domain.model.Promise> page = status == null
                ? promiseRepository.findByRepresentativeId(representativeId, pageable)
                : promiseRepository.findByRepresentativeIdAndStatus(representativeId, status, pageable);
        return page.map(mapper::toPromiseDto);
    }

    /**
     * Lists a promise's citizen-visible status timeline (the dated provenance of how it moved — US-6.3).
     *
     * @param promisePublicId the promise's public id.
     * @param pageable        paging/sorting (the controller defaults to oldest→newest for the timeline).
     * @return a page of {@link PromiseStatusEntryDto}.
     * @throws ResourceNotFoundException if no live promise has that id.
     */
    public Page<PromiseStatusEntryDto> promiseTimeline(UUID promisePublicId, Pageable pageable) {
        Promise promise = promiseRepository.findByPublicId(promisePublicId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "accountability.promise.notFound", promisePublicId));
        return promiseStatusEntryRepository.findByPromise(promise, pageable)
                .map(mapper::toPromiseStatusEntryDto);
    }

    /**
     * Computes a subject's aggregate rating (count + average) — the only public face of ratings.
     *
     * @param subjectType the subject kind.
     * @param subjectId   the rated subject's public id.
     * @return the {@link RatingSummaryDto}; no token balance contributes (§23 fence, D18).
     */
    public RatingSummaryDto ratingSummary(RatingSubjectType subjectType, UUID subjectId) {
        return mapper.toRatingSummaryDto(subjectType, subjectId,
                ratingRepository.aggregate(subjectType, subjectId));
    }

    /**
     * Returns the representative's right-of-reply to a single rating, if one exists — so a client showing a
     * (moderated) rating comment can show the representative's reply <i>with</i> it (the D-rated-fairness rule,
     * US-6.2). Public read: the aggregate is one-sided without the reply, so the reply is part of the public
     * accountability picture. No token balance is read (§23 fence).
     *
     * @param ratingPublicId the rating's public id.
     * @return the reply DTO, or {@link Optional#empty()} if the rating has no reply (or does not exist).
     */
    public Optional<RatingReplyDto> ratingReply(UUID ratingPublicId) {
        return ratingRepository.findByPublicId(ratingPublicId)
                .flatMap(ratingReplyRepository::findByRating)
                .map(mapper::toRatingReplyDto);
    }
}
