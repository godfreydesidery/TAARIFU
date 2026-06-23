package com.taarifu.communications.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a {@link com.taarifu.communications.domain.model.Notification}
 * (PRD §13, UC-G09, M5).
 *
 * <p>Responsibility: the boundary shape for a recipient's notification-list read. Exposes the public
 * {@code id}, the event type, the channel, the delivery status, and the source ref (deep-link) — never
 * inline PII (PRD §18). The recipient is implicitly the caller, so no recipient id is returned.</p>
 *
 * @param id         the notification's public id (UUID) — the mark-read target.
 * @param type       the event type name.
 * @param channel    the delivery channel name.
 * @param status     the delivery status name (QUEUED/SENT/DELIVERED/READ/FAILED).
 * @param payloadRef the source-content reference (deep-link), or {@code null}.
 * @param createdAt  when the notification was created (UTC).
 * @param readAt     when the recipient read it (UTC), or {@code null}.
 */
public record NotificationDto(
        UUID id,
        String type,
        String channel,
        String status,
        String payloadRef,
        Instant createdAt,
        Instant readAt
) {
}
