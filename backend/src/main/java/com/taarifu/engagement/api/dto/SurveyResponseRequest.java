package com.taarifu.engagement.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO to respond to a survey/poll (PRD §12.2 UC-E07, US-8.2).
 *
 * <p>Responsibility: carries the respondent's {@code answers} JSON. The respondent's identity is taken
 * from the authenticated principal, never the body, so one can only respond as oneself (one-per-person).
 * No token field exists — for a binding poll the response path never reads a token balance (PRD §23.5).</p>
 *
 * @param answers the JSON answers payload (required; aligned to the survey's questions; parsed at the edge).
 */
public record SurveyResponseRequest(
        @NotBlank(message = "engagement.response.answers.required")
        String answers
) {
}
