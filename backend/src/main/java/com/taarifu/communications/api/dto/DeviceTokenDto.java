package com.taarifu.communications.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a registered {@link com.taarifu.communications.domain.model.DeviceToken}
 * (PRD §13, EI-5; {@code POST /notification-tokens}).
 *
 * <p>Responsibility: the boundary shape returned to the caller after registration. It exposes the public
 * {@code id}, the {@code platform}, and the {@code lastSeenAt} timestamp — a confirmation receipt.</p>
 *
 * <p><b>Secret handling (PRD §18)</b>: the DTO <b>deliberately omits the token string</b>. The FCM token is
 * a sensitive routing credential; the client already holds it, so the API never echoes it back (defence in
 * depth — it cannot leak through a response log). Only the opaque public id is returned.</p>
 *
 * @param id         the device-token registration's public id (UUID).
 * @param platform   the registered device platform (ANDROID / IOS / WEB).
 * @param lastSeenAt when the token was last (re-)registered (UTC).
 */
public record DeviceTokenDto(
        UUID id,
        String platform,
        Instant lastSeenAt
) {
}
