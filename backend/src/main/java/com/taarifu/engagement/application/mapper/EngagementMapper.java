package com.taarifu.engagement.application.mapper;

import com.taarifu.engagement.api.dto.PetitionDto;
import com.taarifu.engagement.api.dto.QuestionDto;
import com.taarifu.engagement.api.dto.SurveyDto;
import com.taarifu.engagement.domain.model.Answer;
import com.taarifu.engagement.domain.model.Petition;
import com.taarifu.engagement.domain.model.Question;
import com.taarifu.engagement.domain.model.Survey;
import org.springframework.stereotype.Component;

/**
 * Maps engagement entities to their boundary DTOs (ARCHITECTURE.md §3.3, CLAUDE.md §8).
 *
 * <p>Responsibility: the single translation layer from {@link Petition}/{@link Survey}/{@link Question}
 * entities to {@code api.dto} records, ensuring <b>entities never leave the module</b> (CLAUDE.md §8) and
 * that only {@code publicId}s — never internal numeric ids — are exposed (ADR-0006).</p>
 *
 * <p>WHY a hand-written {@code @Component} mapper (not MapStruct): the mappings are trivial and benefit
 * from explicit, documented null-handling (e.g. an unanswered question has no answer); this mirrors the
 * geography slice's mapper choice (ARCHITECTURE §2) without adding an annotation-processor dependency.</p>
 */
@Component
public class EngagementMapper {

    /**
     * @param petition the petition entity.
     * @return the petition DTO (public ids only; derived signature count, never balance-weighted).
     */
    public PetitionDto toPetitionDto(Petition petition) {
        return new PetitionDto(
                petition.getPublicId(),
                petition.getTitle(),
                petition.getBody(),
                petition.getTargetType().name(),
                petition.getTargetId(),
                petition.getSignatureGoal(),
                petition.getSignatureCount(),
                petition.getDeadline(),
                petition.getCreatorProfileId(),
                petition.getCreatorOrgId(),
                petition.getStatus().name(),
                petition.getResponse());
    }

    /**
     * @param survey the survey entity.
     * @return the survey DTO (public ids only; raw JSON blobs for questions/audience).
     */
    public SurveyDto toSurveyDto(Survey survey) {
        return new SurveyDto(
                survey.getPublicId(),
                survey.getTitle(),
                survey.getDescription(),
                survey.getType().name(),
                survey.isBinding(),
                survey.getAudienceScope(),
                survey.getQuestions(),
                survey.getStartsAt(),
                survey.getEndsAt(),
                survey.isAnonymous(),
                survey.getStatus().name(),
                survey.getCreatorProfileId(),
                survey.getCreatorOrgId());
    }

    /**
     * Maps a question plus its optional answer.
     *
     * @param question the question entity.
     * @param answer   the answer entity, or {@code null} if unanswered.
     * @return the question DTO including the answer fields when present.
     */
    public QuestionDto toQuestionDto(Question question, Answer answer) {
        return new QuestionDto(
                question.getPublicId(),
                question.getAskerProfileId(),
                question.getTargetRepId(),
                question.getBody(),
                question.getUpvotes(),
                question.getStatus().name(),
                answer != null ? answer.getBody() : null,
                answer != null ? answer.getAnsweredByRepId() : null);
    }
}
