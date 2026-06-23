package com.taarifu.engagement.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Request DTO to create a petition (PRD §12.2 UC-E01, US-9.1).
 *
 * <p>Responsibility: the validated boundary input for {@code POST /petitions}. Validation is at the edge
 * (CLAUDE.md §8); the message keys resolve Swahili-first (ADR-0010). The created petition starts in
 * {@code DRAFT} and goes through moderation before public visibility (UC-E02) — the creator never sets
 * the status directly.</p>
 *
 * @param title         headline (required).
 * @param body          the ask (required).
 * @param targetType    {@code REPRESENTATIVE} or {@code OFFICE} (required; validated against the enum in
 *                      the service).
 * @param targetId      the addressee's public id in the institutions module (required; by id only).
 * @param signatureGoal success threshold (must be ≥ 1).
 * @param deadline      optional deadline, or {@code null}.
 */
public record CreatePetitionRequest(
        @NotBlank(message = "engagement.petition.title.required")
        @Size(max = 200, message = "engagement.petition.title.tooLong")
        String title,

        @NotBlank(message = "engagement.petition.body.required")
        @Size(max = 8000, message = "engagement.petition.body.tooLong")
        String body,

        @NotBlank(message = "engagement.petition.targetType.required")
        String targetType,

        @NotNull(message = "engagement.petition.targetId.required")
        UUID targetId,

        @Min(value = 1, message = "engagement.petition.signatureGoal.min")
        int signatureGoal,

        Instant deadline
) {
}
