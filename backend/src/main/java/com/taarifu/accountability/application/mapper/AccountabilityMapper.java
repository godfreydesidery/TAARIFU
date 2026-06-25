package com.taarifu.accountability.application.mapper;

import com.taarifu.accountability.api.dto.AttendanceDto;
import com.taarifu.accountability.api.dto.AttendanceSummaryDto;
import com.taarifu.accountability.api.dto.ContributionDto;
import com.taarifu.accountability.api.dto.PromiseDto;
import com.taarifu.accountability.api.dto.PromiseStatusEntryDto;
import com.taarifu.accountability.api.dto.RatingDto;
import com.taarifu.accountability.api.dto.RatingReplyDto;
import com.taarifu.accountability.api.dto.RatingSummaryDto;
import com.taarifu.accountability.domain.model.Attendance;
import com.taarifu.accountability.domain.model.Promise;
import com.taarifu.accountability.domain.model.PromiseStatusEntry;
import com.taarifu.accountability.domain.model.Rating;
import com.taarifu.accountability.domain.model.RatingReply;
import com.taarifu.accountability.domain.model.RepresentativeContribution;
import com.taarifu.accountability.domain.model.enums.RatingSubjectType;
import com.taarifu.accountability.domain.repository.AttendanceRepository;
import com.taarifu.accountability.domain.repository.RatingRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Maps accountability entities and computed aggregates to their boundary DTOs (ARCHITECTURE.md §3.3,
 * CLAUDE.md §8).
 *
 * <p>Responsibility: the single translation layer so entities never leave the module and only
 * {@code publicId}s are exposed (never the internal {@code Long id} — ADR-0006). Aggregate mappers derive
 * presentation values (attendance rate, average) so the wire shape is consistent and the underlying rows
 * stay the source of truth.</p>
 */
@Component
public class AccountabilityMapper {

    /**
     * @param c a contribution entity.
     * @return its public response DTO.
     */
    public ContributionDto toContributionDto(RepresentativeContribution c) {
        return new ContributionDto(
                c.getPublicId(),
                c.getRepresentativeId(),
                c.getType(),
                c.getTitle(),
                c.getSummary(),
                c.getOccurredOn(),
                c.getParliamentSession(),
                c.getSourceUrl(),
                c.getAttachmentRefs());
    }

    /**
     * @param a an attendance entity.
     * @return its public response DTO.
     */
    public AttendanceDto toAttendanceDto(Attendance a) {
        return new AttendanceDto(
                a.getPublicId(),
                a.getRepresentativeId(),
                a.getSessionRef(),
                a.isPresent());
    }

    /**
     * @param representativeId the subject representative's public id.
     * @param aggregate        the computed present/total projection.
     * @return the attendance summary DTO, with rate = present/total (or {@code null} when total is 0).
     */
    public AttendanceSummaryDto toAttendanceSummaryDto(UUID representativeId,
                                                       AttendanceRepository.AttendanceAggregate aggregate) {
        long present = aggregate == null ? 0 : aggregate.getPresent();
        long total = aggregate == null ? 0 : aggregate.getTotal();
        Double rate = total == 0 ? null : (double) present / (double) total;
        return new AttendanceSummaryDto(representativeId, present, total, rate);
    }

    /**
     * @param p a promise entity.
     * @return its public response DTO.
     */
    public PromiseDto toPromiseDto(Promise p) {
        return new PromiseDto(
                p.getPublicId(),
                p.getRepresentativeId(),
                p.getText(),
                p.getMadeAt(),
                p.getStatus(),
                p.getEvidenceRef(),
                List.copyOf(p.getLinkedProjectIds()));
    }

    /**
     * @param e a promise status-timeline entry.
     * @return its public response DTO (one dated transition in the promise's timeline — US-6.3).
     */
    public PromiseStatusEntryDto toPromiseStatusEntryDto(PromiseStatusEntry e) {
        return new PromiseStatusEntryDto(
                e.getPublicId(),
                e.getStatus(),
                e.getEvidenceRef(),
                e.getNote(),
                e.getCreatedAt());
    }

    /**
     * @param reply a representative right-of-reply entity.
     * @return its public response DTO (shown with the rating it answers — the D-rated-fairness rule). The
     *         author account is deliberately NOT surfaced publicly (only whether it was a curator on-behalf
     *         reply).
     */
    public RatingReplyDto toRatingReplyDto(RatingReply reply) {
        return new RatingReplyDto(
                reply.getPublicId(),
                reply.getRepresentativeId(),
                reply.isOnBehalf(),
                reply.getBody(),
                reply.getCreatedAt());
    }

    /**
     * @param r a rating entity.
     * @return its response DTO (the rater's own confirmation; individual ratings are not public lists).
     */
    public RatingDto toRatingDto(Rating r) {
        return new RatingDto(
                r.getPublicId(),
                r.getSubjectType(),
                r.getSubjectId(),
                r.getRaterProfileId(),
                r.getScore(),
                r.getComment(),
                r.getPeriod());
    }

    /**
     * @param subjectType the subject kind.
     * @param subjectId   the subject's public id.
     * @param aggregate   the computed count/average projection.
     * @return the public aggregate rating DTO (no token balance contributes — §23 fence).
     */
    public RatingSummaryDto toRatingSummaryDto(RatingSubjectType subjectType, UUID subjectId,
                                               RatingRepository.RatingAggregate aggregate) {
        long count = aggregate == null ? 0 : aggregate.getCount();
        Double average = aggregate == null ? null : aggregate.getAverage();
        return new RatingSummaryDto(subjectType, subjectId, count, average);
    }
}
