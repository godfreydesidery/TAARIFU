package com.taarifu.communications.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Request to compose and publish an announcement (PRD §12 UC-G01/G02, M4 US-4.1).
 *
 * <p>Responsibility: the validated boundary input for {@code POST /announcements}. Bean Validation runs
 * at the edge ({@code @Valid}) so the service receives well-formed input (CLAUDE.md §8). Cross-module
 * targets (areas, category) are public {@code UUID}s the service resolves through the owning modules.
 * The {@code audienceRole} is the role-name string; {@code channels} are channel enum names validated in
 * the service against {@link com.taarifu.communications.domain.model.enums.Channel}.</p>
 *
 * <p>WHY at least one channel and one of {area/role/category} is required: an announcement with no
 * audience reaches no one, and one with no channel is undeliverable — both are caught here so the author
 * gets a localised validation error rather than a silently empty fan-out (PRD §12).</p>
 *
 * @param title          the headline (required, ≤200).
 * @param bodySw         the Swahili body (required — the default locale, ADR-0010; ≤4000).
 * @param bodyEn         the English body, or {@code null} (≤4000).
 * @param areaIds        targeted geo area public ids (may be empty if a role/category audience is given).
 * @param categoryId     optional tagged category public id, or {@code null}.
 * @param audienceRole   optional role-name narrowing, or {@code null}.
 * @param channels       the delivery channel names (required, non-empty) — FEED/PUSH/SMS/EMAIL.
 * @param attachmentRefs object-store keys for attachments (may be empty).
 * @param publishAt      when to go live (UTC), or {@code null} for immediately.
 * @param expireAt       when to stop showing (UTC), or {@code null} for never.
 */
public record PublishAnnouncementRequest(
        @NotBlank(message = "{communications.announcement.title.required}")
        @Size(max = 200, message = "{communications.announcement.title.tooLong}")
        String title,

        @NotBlank(message = "{communications.announcement.body.required}")
        @Size(max = 4000, message = "{communications.announcement.body.tooLong}")
        String bodySw,

        @Size(max = 4000, message = "{communications.announcement.body.tooLong}")
        String bodyEn,

        Set<UUID> areaIds,

        UUID categoryId,

        @Size(max = 32, message = "{communications.announcement.role.invalid}")
        String audienceRole,

        @NotEmpty(message = "{communications.announcement.channels.required}")
        Set<String> channels,

        Set<@NotNull String> attachmentRefs,

        Instant publishAt,

        Instant expireAt
) {
}
