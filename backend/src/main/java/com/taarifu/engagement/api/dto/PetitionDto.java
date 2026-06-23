package com.taarifu.engagement.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a {@link com.taarifu.engagement.domain.model.Petition} (PRD §9.1, §12.2 M9).
 *
 * <p>Responsibility: the boundary shape for petition reads. Exposes only the {@code publicId} of the
 * petition and its cross-module references ({@code targetId}, {@code creatorProfileId}/
 * {@code creatorOrgId}) — never an internal numeric id (ADR-0006). The {@code signatureCount} is the
 * derived counter (no token weighting — integrity fence, PRD §23.5).</p>
 *
 * @param id              the petition's public id.
 * @param title           headline.
 * @param body            the ask.
 * @param targetType      REPRESENTATIVE or OFFICE.
 * @param targetId        the addressee's public id (institutions module), or {@code null} in draft.
 * @param signatureGoal   success threshold.
 * @param signatureCount  current valid-signature count (one-per-person, never balance-weighted).
 * @param deadline        deadline, or {@code null}.
 * @param creatorProfileId authoring person's profile public id, or {@code null}.
 * @param creatorOrgId    authoring organisation public id, or {@code null}.
 * @param status          lifecycle state.
 * @param response        target's published response, or {@code null}.
 */
public record PetitionDto(
        UUID id,
        String title,
        String body,
        String targetType,
        UUID targetId,
        int signatureGoal,
        int signatureCount,
        Instant deadline,
        UUID creatorProfileId,
        UUID creatorOrgId,
        String status,
        String response
) {
}
