package com.taarifu.accountability.api.dto;

import com.taarifu.accountability.domain.model.enums.RatingSubjectType;

import java.util.UUID;

/**
 * Public response DTO for a subject's <b>computed</b> aggregate rating (PRD §10 Epic M6, US-6.2;
 * §23 fence).
 *
 * <p>This is the only public face of ratings: an aggregate derived from append-only, one-per-person
 * rating rows. No token balance contributes to it — wealth cannot move the score (§23 fence, D18).</p>
 *
 * @param subjectType the subject kind.
 * @param subjectId   the rated subject's public id.
 * @param count       number of ratings counted.
 * @param average     the average score (1..5), or {@code null} when there are no ratings.
 */
public record RatingSummaryDto(
        RatingSubjectType subjectType,
        UUID subjectId,
        long count,
        Double average
) {
}
