package com.taarifu.engagement.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request DTO to ask a representative a public question (PRD §12.2 UC-E09, US-10.x).
 *
 * <p>Responsibility: the validated boundary input for {@code POST /questions}. Asking is a <b>T2</b>
 * action (PRD §7.3); the asker's identity comes from the authenticated principal, never the body. The
 * targeted representative is referenced by public id (institutions module; by id only — no import).
 * Validation at the edge (CLAUDE.md §8).</p>
 *
 * @param targetRepId the targeted representative's public id (required).
 * @param body        the question text (required).
 */
public record AskQuestionRequest(
        @NotNull(message = "engagement.question.targetRepId.required")
        UUID targetRepId,

        @NotBlank(message = "engagement.question.body.required")
        @Size(max = 4000, message = "engagement.question.body.tooLong")
        String body
) {
}
