package com.taarifu.engagement.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Engagement's minimised slice of a data-subject ACCESS export — the petitions the subject created, the
 * petitions they signed, the questions they asked, and the surveys they responded to (PRD §18 PDPA right of
 * access, UC-A16/UC-S09; ADR-0016 §4).
 *
 * <p>Responsibility: the boundary shape {@code EngagementExportContributor} returns for the privacy module's
 * export aggregation — the subject's <b>own</b> engagement footprint, returned to the subject.</p>
 *
 * <p><b>🔒 Data-minimisation (PRD §18):</b> lists only the subject's own civic acts (created petitions,
 * signatures, questions, survey responses) by public id + the subject's own content (their signature comment,
 * their question body, their answers payload). It never enumerates other citizens' signatures or another
 * subject's data. The subject is identified by their authenticated account; this DTO carries no national/voter
 * ID.</p>
 *
 * @param createdPetitions petitions the subject authored (may be empty).
 * @param signatures       petitions the subject signed (may be empty).
 * @param questions        questions the subject asked (may be empty).
 * @param surveyResponses  surveys the subject responded to (may be empty).
 */
public record EngagementExportView(
        List<CreatedPetition> createdPetitions,
        List<Signature> signatures,
        List<AskedQuestion> questions,
        List<SurveyResponseItem> surveyResponses) {

    /**
     * One petition the subject authored.
     *
     * @param petitionPublicId the petition's public id.
     * @param title            the headline the subject wrote.
     * @param status           the lifecycle status name.
     * @param createdAt        when it was created (UTC).
     */
    public record CreatedPetition(UUID petitionPublicId, String title, String status, Instant createdAt) {
    }

    /**
     * One signature the subject gave.
     *
     * @param petitionPublicId the signed petition's public id.
     * @param comment          the subject's own comment, or {@code null}.
     * @param publicSignature  whether the subject opted to be shown publicly.
     * @param signedAt         when they signed (UTC).
     */
    public record Signature(UUID petitionPublicId, String comment, boolean publicSignature, Instant signedAt) {
    }

    /**
     * One question the subject asked.
     *
     * @param questionPublicId the question's public id.
     * @param body             the subject's own question text.
     * @param status           the lifecycle status name.
     * @param askedAt          when it was asked (UTC).
     */
    public record AskedQuestion(UUID questionPublicId, String body, String status, Instant askedAt) {
    }

    /**
     * One survey response the subject submitted.
     *
     * @param responsePublicId the response's public id.
     * @param surveyPublicId   the survey's public id.
     * @param answers          the subject's own answers payload (JSON).
     * @param respondedAt      when they responded (UTC).
     */
    public record SurveyResponseItem(UUID responsePublicId, UUID surveyPublicId, String answers,
                                     Instant respondedAt) {
    }
}
