package com.taarifu.communications.api.dto;

import com.taarifu.communications.domain.model.DeviceToken;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to register (or idempotently refresh) the caller's push device token
 * (PRD §13, EI-5, US-5.1; {@code POST /notification-tokens}).
 *
 * <p>Responsibility: the validated boundary input for device-token registration. The {@code token} is the
 * opaque FCM registration token the citizen's app obtained from the FCM SDK; the {@code platform} is the
 * {@link com.taarifu.communications.domain.model.enums.DevicePlatform} name, validated in the service.</p>
 *
 * <p><b>Secret handling (PRD §18)</b>: the {@code token} is a sensitive routing credential — it is never
 * logged or echoed back to any other user. The registration response intentionally does not return the
 * token value.</p>
 *
 * @param token    the opaque FCM/APNs/WebPush registration token, required (max
 *                 {@link DeviceToken#MAX_TOKEN_LENGTH}).
 * @param platform the device platform name (ANDROID / IOS / WEB), required.
 */
public record RegisterDeviceTokenRequest(
        @NotBlank(message = "{communications.deviceToken.token.required}")
        @Size(max = DeviceToken.MAX_TOKEN_LENGTH, message = "{communications.deviceToken.token.required}")
        String token,

        @NotBlank(message = "{communications.deviceToken.platform.required}")
        String platform
) {
}
