package com.taarifu.admin.domain.model.enums;

/**
 * The client platform an {@code AppConfig} row targets (M14; PRD EI-16 app distribution / force-update).
 *
 * <p>Responsibility: lets the server-driven app config carry a <b>per-platform</b> minimum/force-update
 * version, because the Play Store and App Store ship and gate versions independently (an Android build
 * number is not comparable to an iOS one). The {@link #WEB} value covers the Angular/PWA client where a
 * min-version gate is advisory.</p>
 */
public enum ClientPlatform {

    /** Flutter Android client (Google Play). */
    ANDROID,

    /** Flutter iOS client (App Store). */
    IOS,

    /** Angular admin/citizen web / PWA client. */
    WEB
}
