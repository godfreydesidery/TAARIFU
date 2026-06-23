package com.taarifu.accountability.api.dto;

import com.taarifu.accountability.domain.model.enums.RatingSubjectType;

import java.util.UUID;

/**
 * Response DTO for a single {@link com.taarifu.accountability.domain.model.Rating} — returned to the
 * rater on create/revise (PRD §10 Epic M6, US-6.2).
 *
 * <p>Individual ratings are not a public list (only the aggregate is public — US-6.2); this DTO is the
 * rater's own confirmation. The rater identity is included as a public id only.</p>
 *
 * @param id             the rating's public id.
 * @param subjectType    the subject kind.
 * @param subjectId      the rated subject's public id.
 * @param raterProfileId the rater's profile public id (owner).
 * @param score          the score (1..5).
 * @param comment        the optional comment, or {@code null}.
 * @param period         the rating period (e.g. {@code "2026-Q2"}).
 */
public record RatingDto(
        UUID id,
        RatingSubjectType subjectType,
        UUID subjectId,
        UUID raterProfileId,
        int score,
        String comment,
        String period
) {
}
