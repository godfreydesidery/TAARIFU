package com.taarifu.engagement.api.dto;

import java.util.UUID;

/**
 * Response DTO for a Q&A {@link com.taarifu.engagement.domain.model.Question}, optionally including its
 * {@link com.taarifu.engagement.domain.model.Answer} (PRD §9.1, §12.2 M10).
 *
 * <p>Responsibility: the boundary shape for question reads. Exposes the question public id and its
 * cross-module references ({@code askerProfileId}, {@code targetRepId}) by public id only. The
 * {@code answerBody}/{@code answeredByRepId} are populated when the question is {@code ANSWERED}.</p>
 *
 * @param id             the question's public id.
 * @param askerProfileId asker's identity profile public id.
 * @param targetRepId    targeted representative's public id (institutions module).
 * @param body           the question text.
 * @param upvotes        upvote count.
 * @param status         lifecycle state.
 * @param answerBody     the answer text, or {@code null} if unanswered.
 * @param answeredByRepId the answering representative's public id, or {@code null} if unanswered.
 */
public record QuestionDto(
        UUID id,
        UUID askerProfileId,
        UUID targetRepId,
        String body,
        int upvotes,
        String status,
        String answerBody,
        UUID answeredByRepId
) {
}
