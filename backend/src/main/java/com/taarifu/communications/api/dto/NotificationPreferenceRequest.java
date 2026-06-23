package com.taarifu.communications.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;

/**
 * Request to set a single notification preference (PRD §13, UC-G08, M5).
 *
 * <p>Responsibility: the validated boundary input for {@code PUT /notification-preferences}. The
 * {@code type} and {@code channel} are enum names validated in the service; the rest are the mutable
 * settings (opt-in, quiet hours, language). Identity of the preference is {@code (caller, type, channel)}
 * — there is no id in the request because a citizen upserts their own per-pair setting.</p>
 *
 * <p>WHY upsert semantics (not create/update split): a citizen toggling a channel should not need to
 * know whether a row already exists; the service upserts on {@code (profile,type,channel)} (the DB
 * unique constraint backs it). "Always" types (SYSTEM/MODERATION_OUTCOME) reject an opt-out in the
 * service (PRD §13).</p>
 *
 * @param type      the notification type name, required.
 * @param channel   the channel name, required.
 * @param enabled   whether opted in.
 * @param quietFrom quiet window start (local time), or {@code null}.
 * @param quietTo   quiet window end (local time), or {@code null}.
 * @param language  preferred language tag (e.g. {@code sw}/{@code en}), or {@code null} (≤8).
 */
public record NotificationPreferenceRequest(
        @NotBlank(message = "{communications.preference.type.required}")
        String type,

        @NotBlank(message = "{communications.preference.channel.required}")
        String channel,

        boolean enabled,

        LocalTime quietFrom,

        LocalTime quietTo,

        @Size(max = 8, message = "{communications.preference.language.invalid}")
        String language
) {
}
