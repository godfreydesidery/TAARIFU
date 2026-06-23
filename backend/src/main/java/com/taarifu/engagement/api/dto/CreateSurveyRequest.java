package com.taarifu.engagement.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Request DTO to create a survey/poll (PRD §12.2 UC-E06, US-8.1).
 *
 * <p>Responsibility: the validated boundary input for {@code POST /surveys}. {@code type} must be
 * {@code SURVEY} or {@code POLL}; {@code binding} is honoured only for a {@code POLL} (the entity forces
 * a non-poll to non-binding). The created survey starts in {@code DRAFT}. Validation at the edge
 * (CLAUDE.md §8).</p>
 *
 * @param title         title (required).
 * @param description   optional description, or {@code null}.
 * @param type          {@code SURVEY} or {@code POLL} (required; validated against the enum in the service).
 * @param binding       whether a poll is binding (T3 + one-per-person on response); ignored for SURVEY.
 * @param audienceScope JSON audience descriptor, or {@code null} for open-to-all.
 * @param questions     JSON questions definition, or {@code null}.
 * @param startsAt      open instant, or {@code null}.
 * @param endsAt        close instant, or {@code null}.
 * @param anonymous     whether responses are anonymous.
 */
public record CreateSurveyRequest(
        @NotBlank(message = "engagement.survey.title.required")
        @Size(max = 200, message = "engagement.survey.title.tooLong")
        String title,

        @Size(max = 4000, message = "engagement.survey.description.tooLong")
        String description,

        @NotBlank(message = "engagement.survey.type.required")
        String type,

        boolean binding,

        String audienceScope,

        String questions,

        Instant startsAt,

        Instant endsAt,

        boolean anonymous
) {
}
