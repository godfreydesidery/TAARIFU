package com.taarifu.admin.api.dto;

import com.taarifu.admin.domain.model.enums.ClientPlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Admin request to create or replace the app-config for a platform (M14, UC-H07; PRD EI-16).
 *
 * <p>Responsibility: the full desired state of a platform's version gate + splash. The service upserts by
 * {@link #platform} (one live row per platform). Validation at the edge (Bean Validation) keeps a
 * malformed gate out of the domain (CLAUDE.md §8).</p>
 *
 * @param platform                the targeted client platform (required).
 * @param minSupportedVersion     human min version string (required).
 * @param minSupportedVersionCode comparable min build code (required, {@code >= 0}); the client gates on
 *                                this.
 * @param latestVersion           human latest version string, or {@code null}.
 * @param latestVersionCode       comparable latest build code, or {@code null}.
 * @param forceUpdate             whether below-min clients must hard-block (defaults handled if null at the
 *                                edge — here required to be explicit).
 * @param splashMessage           optional splash banner text (max 512), or {@code null}.
 * @param splashUrl               optional splash URL (max 512), or {@code null}.
 */
public record UpsertAppConfigRequest(
        @NotNull ClientPlatform platform,
        @NotBlank @Size(max = 32) String minSupportedVersion,
        @PositiveOrZero long minSupportedVersionCode,
        @Size(max = 32) String latestVersion,
        Long latestVersionCode,
        boolean forceUpdate,
        @Size(max = 512) String splashMessage,
        @Size(max = 512) String splashUrl) {
}
