package com.taarifu.admin.api.dto;

import com.taarifu.admin.domain.model.enums.ClientPlatform;

import java.util.UUID;

/**
 * Response view of an {@link com.taarifu.admin.domain.model.AppConfig} row (M14, UC-H07; PRD EI-16).
 *
 * <p>Responsibility: the wire shape returned both to the admin console (management) and to the public
 * app-config read (the client's boot/min-version gate). Carries no PII; safe to serve unauthenticated.</p>
 *
 * @param publicId                the config row's public id (admin edits/deletes by this).
 * @param platform                the targeted client platform.
 * @param minSupportedVersion     human min version string (display).
 * @param minSupportedVersionCode comparable min build code — the client gates on this (EI-16).
 * @param latestVersion           human latest version string, or {@code null}.
 * @param latestVersionCode       comparable latest build code, or {@code null}.
 * @param forceUpdate             whether below-min clients must hard-block.
 * @param splashMessage           optional splash/maintenance banner text, or {@code null}.
 * @param splashUrl               optional splash URL, or {@code null}.
 */
public record AppConfigDto(
        UUID publicId,
        ClientPlatform platform,
        String minSupportedVersion,
        long minSupportedVersionCode,
        String latestVersion,
        Long latestVersionCode,
        boolean forceUpdate,
        String splashMessage,
        String splashUrl) {
}
