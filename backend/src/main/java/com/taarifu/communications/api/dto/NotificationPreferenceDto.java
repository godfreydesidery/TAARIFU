package com.taarifu.communications.api.dto;

import java.time.LocalTime;
import java.util.UUID;

/**
 * Response DTO for a {@link com.taarifu.communications.domain.model.NotificationPreference}
 * (PRD §13, UC-G08, M5).
 *
 * <p>Responsibility: the boundary shape for preference reads. Exposes the public {@code id} plus the
 * (type, channel) pair and the citizen's settings (ADR-0006).</p>
 *
 * @param id        the preference's public id (UUID).
 * @param type      the governed notification type name.
 * @param channel   the governed channel name.
 * @param enabled   whether opted in.
 * @param quietFrom quiet window start (local), or {@code null}.
 * @param quietTo   quiet window end (local), or {@code null}.
 * @param language  preferred language tag, or {@code null}.
 */
public record NotificationPreferenceDto(
        UUID id,
        String type,
        String channel,
        boolean enabled,
        LocalTime quietFrom,
        LocalTime quietTo,
        String language
) {
}
