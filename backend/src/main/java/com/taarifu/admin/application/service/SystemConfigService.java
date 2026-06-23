package com.taarifu.admin.application.service;

import com.taarifu.admin.api.dto.AppConfigDto;
import com.taarifu.admin.api.dto.FeatureFlagDto;
import com.taarifu.admin.api.dto.UpsertAppConfigRequest;
import com.taarifu.admin.api.dto.UpsertFeatureFlagRequest;
import com.taarifu.admin.application.mapper.AdminConfigMapper;
import com.taarifu.admin.domain.model.AppConfig;
import com.taarifu.admin.domain.model.FeatureFlag;
import com.taarifu.admin.domain.model.enums.ClientPlatform;
import com.taarifu.admin.domain.repository.AppConfigRepository;
import com.taarifu.admin.domain.repository.FeatureFlagRepository;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.security.CurrentUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The admin <b>system-config</b> surface — server-driven app config (min-version / force-update / splash)
 * and feature flags (M14, US-14.1, UC-H07; PRD EI-16).
 *
 * <p>Responsibility: own the {@code app_config} and {@code feature_flag} tables (the admin module's own
 * data — no cross-module reach). Upserts are idempotent on their natural key (one live {@code AppConfig}
 * per {@link ClientPlatform}; one {@link FeatureFlag} per key); reads serve both the admin console and the
 * public boot-time config endpoint. The transaction boundary lives here (ARCHITECTURE §3.3); the
 * "who changed it" trail is captured by {@code BaseEntity}'s {@code created_by}/{@code updated_by} audit
 * columns (populated by {@code AuditorAwareImpl} from the authenticated admin).</p>
 *
 * <p>WHY upsert-by-natural-key rather than create-then-edit-by-id: an operator thinks in terms of "the
 * Android min-version" or "the {@code mpesa_purchase} flag", not an opaque row id; keeping a single live
 * row per platform/key (enforced by the partial-unique indexes in V90) avoids conflicting duplicates. A
 * race that still produces a duplicate surfaces as a clean {@code 409 CONFLICT}, never a 500.</p>
 */
@Service
public class SystemConfigService {

    private final AppConfigRepository appConfigRepository;
    private final FeatureFlagRepository featureFlagRepository;
    private final AdminConfigMapper mapper;

    /**
     * @param appConfigRepository   per-platform app-config store.
     * @param featureFlagRepository feature-flag store.
     * @param mapper                entity→DTO boundary.
     */
    public SystemConfigService(AppConfigRepository appConfigRepository,
                               FeatureFlagRepository featureFlagRepository,
                               AdminConfigMapper mapper) {
        this.appConfigRepository = appConfigRepository;
        this.featureFlagRepository = featureFlagRepository;
        this.mapper = mapper;
    }

    // ---- AppConfig ---------------------------------------------------------------------------------

    /**
     * @return every platform's app config (the bounded reference set; not paginated).
     */
    @Transactional(readOnly = true)
    public List<AppConfigDto> listAppConfigs() {
        return appConfigRepository.findAll().stream().map(mapper::toDto).toList();
    }

    /**
     * Reads one platform's app config.
     *
     * @param platform the client platform.
     * @return the config view.
     * @throws ApiException {@code NOT_FOUND} if no config is set for that platform yet.
     */
    @Transactional(readOnly = true)
    public AppConfigDto getAppConfig(ClientPlatform platform) {
        AppConfig config = appConfigRepository.findByPlatform(platform)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return mapper.toDto(config);
    }

    /**
     * Creates or replaces the app config for a platform (idempotent on {@code platform}).
     *
     * @param request the full desired config state.
     * @return the persisted config view.
     * @throws ApiException {@code CONFLICT} on a concurrent duplicate-platform race.
     */
    @Transactional
    public AppConfigDto upsertAppConfig(UpsertAppConfigRequest request) {
        AppConfig config = appConfigRepository.findByPlatform(request.platform())
                .orElseGet(() -> AppConfig.create(request.platform(), request.minSupportedVersion(),
                        request.minSupportedVersionCode(), request.forceUpdate()));
        config.update(request.minSupportedVersion(), request.minSupportedVersionCode(),
                request.latestVersion(), request.latestVersionCode(), request.forceUpdate(),
                request.splashMessage(), request.splashUrl());
        try {
            return mapper.toDto(appConfigRepository.saveAndFlush(config));
        } catch (DataIntegrityViolationException ex) {
            // Concurrent insert of the same platform hit ux_app_config_platform_live → clean 409, not 500.
            throw new ApiException(ErrorCode.CONFLICT);
        }
    }

    // ---- FeatureFlag -------------------------------------------------------------------------------

    /**
     * @return all feature flags, ordered by key (the operator-curated, bounded set; not paginated).
     */
    @Transactional(readOnly = true)
    public List<FeatureFlagDto> listFeatureFlags() {
        return featureFlagRepository.findAllByOrderByKeyAsc().stream().map(mapper::toDto).toList();
    }

    /**
     * Creates or updates a feature flag (idempotent on {@code key}).
     *
     * @param request the full desired flag state.
     * @return the persisted flag view.
     * @throws ApiException {@code CONFLICT} on a concurrent duplicate-key race.
     */
    @Transactional
    public FeatureFlagDto upsertFeatureFlag(UpsertFeatureFlagRequest request) {
        FeatureFlag flag = featureFlagRepository.findByKey(request.key())
                .orElseGet(() -> FeatureFlag.create(request.key(), request.description()));
        flag.update(request.description(), request.enabled(), request.rolloutPercentage());
        try {
            return mapper.toDto(featureFlagRepository.saveAndFlush(flag));
        } catch (DataIntegrityViolationException ex) {
            // Concurrent insert of the same key hit ux_feature_flag_key_live → clean 409.
            throw new ApiException(ErrorCode.CONFLICT);
        }
    }

    /**
     * Retires (soft-deletes) a feature flag by public id.
     *
     * <p>Soft-delete (not a physical remove) keeps the flag's history and the {@code BaseEntity} audit
     * columns intact (PRD §9/§18); the partial-unique index frees the key for re-creation.</p>
     *
     * @param flagPublicId the flag's public id.
     * @throws ApiException {@code NOT_FOUND} if no such flag.
     */
    @Transactional
    public void deleteFeatureFlag(UUID flagPublicId) {
        FeatureFlag flag = featureFlagRepository.findByPublicId(flagPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        flag.markDeleted(CurrentUser.current().map(CurrentUser::publicId).orElse(null));
        featureFlagRepository.save(flag);
    }
}
