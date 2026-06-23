package com.taarifu.communications.api.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Response DTO for an {@link com.taarifu.communications.domain.model.Announcement}
 * (PRD §9.1, §22.6, M4).
 *
 * <p>Responsibility: the boundary shape for announcement reads and the publish response. Exposes only
 * the public {@code id} (never the internal {@code Long}, ADR-0006) and references cross-module entities
 * by their public ids. Both language bodies are returned so the client renders the recipient's locale
 * (ADR-0010).</p>
 *
 * @param id            the announcement's public id (UUID).
 * @param authorId      the authoring profile's public id.
 * @param title         the headline.
 * @param bodySw        the Swahili body.
 * @param bodyEn        the English body, or {@code null}.
 * @param categoryId    the tagged category public id, or {@code null}.
 * @param audienceRole  the role-name narrowing, or {@code null}.
 * @param status        the lifecycle status name (DRAFT/SCHEDULED/PUBLISHED/EXPIRED).
 * @param moderationHeld whether held for moderation (blocks publish until cleared).
 * @param areaIds       the targeted geo area public ids.
 * @param channels      the delivery channel names.
 * @param attachmentRefs attachment object-store keys.
 * @param publishAt     when it goes/went live (UTC), or {@code null}.
 * @param expireAt      when it stops showing (UTC), or {@code null}.
 */
public record AnnouncementDto(
        UUID id,
        UUID authorId,
        String title,
        String bodySw,
        String bodyEn,
        UUID categoryId,
        String audienceRole,
        String status,
        boolean moderationHeld,
        Set<UUID> areaIds,
        Set<String> channels,
        Set<String> attachmentRefs,
        Instant publishAt,
        Instant expireAt
) {
}
