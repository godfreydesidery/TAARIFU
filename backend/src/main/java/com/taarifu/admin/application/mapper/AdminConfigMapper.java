package com.taarifu.admin.application.mapper;

import com.taarifu.admin.api.dto.AppConfigDto;
import com.taarifu.admin.api.dto.FeatureFlagDto;
import com.taarifu.admin.domain.model.AppConfig;
import com.taarifu.admin.domain.model.FeatureFlag;
import org.springframework.stereotype.Component;

/**
 * Maps admin system-config entities to their wire DTOs (ARCHITECTURE.md §3.3 — entities never leak past
 * {@code api}).
 *
 * <p>Responsibility: the single, DRY entity→DTO boundary for {@link AppConfig} and {@link FeatureFlag}.
 * A plain Spring bean (not MapStruct) keeps the two trivial mappings explicit and reviewable; both DTOs
 * are PII-free by construction.</p>
 */
@Component
public class AdminConfigMapper {

    /**
     * @param config the config entity.
     * @return its wire view (carries the public id, never the internal PK).
     */
    public AppConfigDto toDto(AppConfig config) {
        return new AppConfigDto(
                config.getPublicId(),
                config.getPlatform(),
                config.getMinSupportedVersion(),
                config.getMinSupportedVersionCode(),
                config.getLatestVersion(),
                config.getLatestVersionCode(),
                config.isForceUpdate(),
                config.getSplashMessage(),
                config.getSplashUrl());
    }

    /**
     * @param flag the flag entity.
     * @return its wire view.
     */
    public FeatureFlagDto toDto(FeatureFlag flag) {
        return new FeatureFlagDto(
                flag.getPublicId(),
                flag.getKey(),
                flag.getDescription(),
                flag.isEnabled(),
                flag.getRolloutPercentage());
    }
}
