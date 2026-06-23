package com.taarifu.accountability.api.dto;

import com.taarifu.accountability.domain.model.enums.RatingSubjectType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request to submit a binding {@link com.taarifu.accountability.domain.model.Rating}
 * (PRD §10 Epic M6, US-6.2; §23 fence; D13/D16/D18).
 *
 * <p>Rating a representative is a <b>binding action</b>: the endpoint enforces tier (T3), no-self-action,
 * and one-per-(rater, subject, period). No token balance is part of this request or its authorization —
 * the fence forbids it (§23, D18). The rater is taken from the security context, never from the body
 * (a caller can never rate as someone else).</p>
 *
 * @param subjectType the subject kind (required).
 * @param subjectId   the rated subject's public id (required).
 * @param score       the score, 1..5 (required).
 * @param comment     optional comment (moderated downstream), or {@code null}.
 * @param period      the rating period, e.g. {@code "2026-Q2"} (required; format {@code YYYY-Qn} or
 *                    {@code YYYY-MM}).
 */
public record CreateRatingDto(
        @NotNull(message = "accountability.rating.subjectType.required") RatingSubjectType subjectType,
        @NotNull(message = "accountability.rating.subject.required") UUID subjectId,
        @NotNull(message = "accountability.rating.score.required")
        @Min(value = 1, message = "accountability.rating.score.range")
        @Max(value = 5, message = "accountability.rating.score.range") Integer score,
        @Size(max = 2000, message = "accountability.rating.comment.tooLong") String comment,
        @NotBlank(message = "accountability.rating.period.required")
        @Pattern(regexp = "\\d{4}-(Q[1-4]|(0[1-9]|1[0-2]))",
                message = "accountability.rating.period.format") String period
) {
}
