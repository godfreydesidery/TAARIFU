package com.taarifu.reporting.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a single timeline {@link com.taarifu.reporting.domain.model.CaseEvent}
 * (PRD §10 US-3.2).
 *
 * <p>Responsibility: the boundary shape for a case-timeline entry. The {@code publicEvent} flag is
 * surfaced so a client can style internal vs public notes; the read service ensures the public timeline
 * never <i>contains</i> internal events in the first place (defence-in-depth, US-3.4).</p>
 *
 * @param id             the event's public id.
 * @param eventType      the event type name.
 * @param publicEvent    {@code true} if a public event, {@code false} if internal-only.
 * @param actorProfileId acting profile public id, or {@code null} for system/anonymous.
 * @param message        the event body/description.
 * @param createdAt      event instant (UTC).
 */
public record CaseEventDto(
        UUID id,
        String eventType,
        boolean publicEvent,
        UUID actorProfileId,
        String message,
        Instant createdAt
) {
}
