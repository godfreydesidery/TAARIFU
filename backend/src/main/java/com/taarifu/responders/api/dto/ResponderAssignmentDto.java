package com.taarifu.responders.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a {@link com.taarifu.responders.domain.model.ResponderAssignment} (PRD §24.3).
 *
 * <p>Responsibility: the boundary shape for the owner+collaborator view of a report's assignments.
 * The report is exposed as its loose {@code reportId} (reporting built in parallel); the responder as
 * its public id + name. Internal notes are NOT modelled here (they are per-responder/private, §24.3).</p>
 *
 * @param id              the assignment's public id (UUID).
 * @param reportId        the report id this assignment belongs to (loose reference).
 * @param responderId     the assigned responder's public id.
 * @param responderName   the assigned responder's display name.
 * @param role            {@code OWNER} or {@code COLLABORATOR}.
 * @param status          the responder's progress status.
 * @param assignedAt      when the assignment was made (UTC).
 * @param slaPolicy       SLA snapshot text, or {@code null}.
 */
public record ResponderAssignmentDto(
        UUID id,
        UUID reportId,
        UUID responderId,
        String responderName,
        String role,
        String status,
        Instant assignedAt,
        String slaPolicy
) {
}
