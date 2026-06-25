package com.taarifu.accountability.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Accountability's minimised slice of a data-subject ACCESS export — the ratings the subject submitted
 * (PRD §18 PDPA right of access, UC-A16/UC-S09; ADR-0016 §4).
 *
 * <p>Responsibility: the boundary shape {@code AccountabilityExportContributor} returns for the privacy
 * module's export aggregation — the subject's <b>own</b> ratings, returned to the subject.</p>
 *
 * <p><b>🔒 Data-minimisation (PRD §18):</b> lists only the ratings the subject themselves gave (subject kind,
 * rated subject public id, score, the subject's own comment, period). It never enumerates other raters' rows
 * and never exposes the computed aggregate of another subject's data. The subject is identified by their
 * authenticated account; this DTO carries no national/voter ID.</p>
 *
 * @param ratings the ratings the subject submitted (may be empty).
 */
public record AccountabilityExportView(List<SubmittedRating> ratings) {

    /**
     * One rating the subject submitted as a rater.
     *
     * @param ratingPublicId   the rating's public id.
     * @param subjectType      the rated subject kind (REPRESENTATIVE/OFFICE/PROJECT).
     * @param ratedSubjectId   the rated subject's public id.
     * @param score            the score the subject gave (1..5).
     * @param comment          the subject's own comment, or {@code null}.
     * @param period           the rating period (e.g. {@code "2026-Q2"}).
     * @param submittedAt      when the rating was created (UTC).
     */
    public record SubmittedRating(
            UUID ratingPublicId,
            String subjectType,
            UUID ratedSubjectId,
            int score,
            String comment,
            String period,
            Instant submittedAt) {
    }
}
