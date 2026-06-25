package com.taarifu.engagement.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for a representative to answer a public question (PRD §12.2 UC-E10, US-10.2).
 *
 * <p>Responsibility: the validated boundary input for {@code POST /questions/{id}/answer}. The answering
 * representative's identity comes from the authenticated principal (and must equal the question's target —
 * enforced in {@link com.taarifu.engagement.application.service.QuestionService#answer}), never the body —
 * so this carries only the answer text. Validation at the edge (CLAUDE.md §8); the {@code 8000} bound matches
 * the {@code qa_answer.body} column.</p>
 *
 * @param body the answer text (required, ≤ 8000 chars).
 */
public record AnswerQuestionRequest(
        @NotBlank(message = "engagement.answer.body.required")
        @Size(max = 8000, message = "engagement.answer.body.tooLong")
        String body
) {
}
