package com.taarifu.admin.api.dto;

import java.util.UUID;

/**
 * Response view of a {@link com.taarifu.admin.domain.model.FeatureFlag} (M14, UC-H07).
 *
 * <p>Responsibility: the wire shape for the admin console (management) and the public config read (so a
 * client knows which features are live). No PII.</p>
 *
 * @param publicId          the flag's public id (admin edits/deletes by this).
 * @param key               the stable machine key.
 * @param description       the human purpose, or {@code null}.
 * @param enabled           the master switch state.
 * @param rolloutPercentage the staged-rollout percentage (0–100).
 */
public record FeatureFlagDto(
        UUID publicId,
        String key,
        String description,
        boolean enabled,
        int rolloutPercentage) {
}
