package com.taarifu.admin.api.controller;

import com.taarifu.admin.api.dto.AppConfigDto;
import com.taarifu.admin.api.dto.FeatureFlagDto;
import com.taarifu.admin.api.dto.UpsertAppConfigRequest;
import com.taarifu.admin.api.dto.UpsertFeatureFlagRequest;
import com.taarifu.admin.application.service.SystemConfigService;
import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The admin <b>system-config management</b> surface — app config (min-version/force-update/splash) and
 * feature flags (M14, US-14.1, UC-H07; PRD EI-16).
 *
 * <p>Responsibility: a thin HTTP layer for the back-office to view and upsert {@code AppConfig} per
 * platform and to create/update/retire {@code FeatureFlag}s, delegating to {@link SystemConfigService}.
 * The matching public boot-time <i>reads</i> live in {@link AppConfigController}; this controller owns the
 * <i>writes</i> and the admin reads. No business logic, no {@code @Transactional} (ARCHITECTURE §3.3).</p>
 *
 * <p><b>Authorization (deny-by-default, ARCHITECTURE §6.2):</b> every method is
 * {@code hasAnyRole('ADMIN','ROOT')} — app config and feature flags are platform-admin powers (a
 * force-update gate can hard-block every client, so it must never be merely "authenticated-only"; this is
 * the legacy gap the design forbids, PRD §7.1). The security-gate test fails closed if an annotation is
 * removed.</p>
 */
@RestController
@RequestMapping(path = "/admin/config")
@Tag(name = "Admin System Config", description = "Manage app config (min-version/force-update) and feature flags.")
public class AdminSystemConfigController {

    private final SystemConfigService systemConfig;
    private final ResponseFactory responses;

    /**
     * @param systemConfig the config service.
     * @param responses    envelope builder.
     */
    public AdminSystemConfigController(SystemConfigService systemConfig, ResponseFactory responses) {
        this.systemConfig = systemConfig;
        this.responses = responses;
    }

    // ---- AppConfig ---------------------------------------------------------------------------------

    /**
     * Lists every platform's app config.
     *
     * @return {@code 200} + the configs.
     */
    @GetMapping("/app")
    @PreAuthorize("hasAnyRole('ADMIN','ROOT')")
    @Operation(summary = "List app config for all platforms")
    public ApiResponse<List<AppConfigDto>> listAppConfigs() {
        return responses.ok(systemConfig.listAppConfigs());
    }

    /**
     * Creates or replaces the app config for a platform (idempotent on platform).
     *
     * @param request the full desired config state.
     * @return {@code 200} + the persisted config.
     */
    @PutMapping("/app")
    @PreAuthorize("hasAnyRole('ADMIN','ROOT')")
    @Operation(summary = "Create or replace a platform's app config",
            description = "Sets min-version/force-update/splash for the platform (one live row per platform).")
    public ApiResponse<AppConfigDto> upsertAppConfig(@Valid @RequestBody UpsertAppConfigRequest request) {
        return responses.ok(systemConfig.upsertAppConfig(request));
    }

    // ---- FeatureFlag -------------------------------------------------------------------------------

    /**
     * Lists all feature flags (admin view).
     *
     * @return {@code 200} + the flags ordered by key.
     */
    @GetMapping("/flags")
    @PreAuthorize("hasAnyRole('ADMIN','ROOT')")
    @Operation(summary = "List all feature flags")
    public ApiResponse<List<FeatureFlagDto>> listFlags() {
        return responses.ok(systemConfig.listFeatureFlags());
    }

    /**
     * Creates or updates a feature flag (idempotent on key).
     *
     * @param request the full desired flag state.
     * @return {@code 200} + the persisted flag.
     */
    @PostMapping("/flags")
    @PreAuthorize("hasAnyRole('ADMIN','ROOT')")
    @Operation(summary = "Create or update a feature flag")
    public ApiResponse<FeatureFlagDto> upsertFlag(@Valid @RequestBody UpsertFeatureFlagRequest request) {
        return responses.ok(systemConfig.upsertFeatureFlag(request));
    }

    /**
     * Retires (soft-deletes) a feature flag by public id.
     *
     * @param flagPublicId the flag's public id.
     * @return {@code 200} (empty body).
     */
    @DeleteMapping("/flags/{flagPublicId}")
    @PreAuthorize("hasAnyRole('ADMIN','ROOT')")
    @Operation(summary = "Retire a feature flag (soft-delete)")
    public ApiResponse<Void> deleteFlag(@PathVariable UUID flagPublicId) {
        systemConfig.deleteFeatureFlag(flagPublicId);
        return responses.ok(null);
    }
}
