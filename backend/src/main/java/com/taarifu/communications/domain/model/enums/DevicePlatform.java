package com.taarifu.communications.domain.model.enums;

/**
 * The client platform a registered push device-token belongs to (PRD §13, EI-5).
 *
 * <p>Responsibility: the closed set of device families a citizen may register a push token for. It is
 * recorded against each {@link com.taarifu.communications.domain.model.DeviceToken} so operations and
 * future per-platform routing (e.g. an APNs vs. FCM split) can reason about a token without parsing it.
 * In the current MVP every platform delivers through the single FCM HTTP v1 adapter (FCM carries Android,
 * and iOS/Web via FCM's APNs/WebPush bridges), so this is descriptive metadata, not an adapter selector.</p>
 *
 * <p>WHY this lives in {@code communications} (not {@code common}): the device registry is this module's
 * domain; other modules never reference a platform — they only target a recipient profile, and this module
 * resolves the recipient's tokens internally (DRY, ARCHITECTURE §3.2).</p>
 */
public enum DevicePlatform {

    /** An Android client (FCM-native). */
    ANDROID,

    /** An iOS client (delivered through FCM's APNs bridge in this MVP). */
    IOS,

    /** A web/PWA client (delivered through FCM WebPush). */
    WEB
}
