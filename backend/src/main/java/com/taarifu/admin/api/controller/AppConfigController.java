package com.taarifu.admin.api.controller;

import com.taarifu.admin.api.dto.AppConfigDto;
import com.taarifu.admin.api.dto.FeatureFlagDto;
import com.taarifu.admin.application.service.SystemConfigService;
import com.taarifu.admin.domain.model.enums.ClientPlatform;
import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The <b>public, boot-time</b> app-config read for clients (M14, US-14.1; PRD EI-16).
 *
 * <p>Responsibility: serve the mobile/web client the version gate (min-version / force-update / splash)
 * and the live feature flags <b>before</b> the user authenticates — a client must be able to learn it is
 * below the minimum supported version and hard-block, even with no session, so this surface is
 * <b>public-read</b> (unauthenticated). The data is non-PII operational config by design.</p>
 *
 * <p><b>Security:</b> these are GET reads under {@code /app-config/**}, which must be added to the
 * security {@code PUBLIC_GET_PATTERNS} allow-list (listed under CENTRAL INTEGRATION NEEDS — the admin
 * module must not edit {@code SecurityConfig}). The matching <b>write/management</b> endpoints live in
 * {@link AdminSystemConfigController} under {@code /admin/**} and are {@code ROLE_ADMIN}/{@code ROOT}
 * gated. No method-security annotation is placed here because the path itself is intended to be public;
 * the management mutations are gated separately, so an anonymous caller can read but never change config.</p>
 */
@RestController
@RequestMapping(path = "/app-config")
@Tag(name = "App Config (public)", description = "Boot-time client config: min-version/force-update/splash + flags.")
public class AppConfigController {

    private final SystemConfigService systemConfig;
    private final ResponseFactory responses;

    /**
     * @param systemConfig the config service.
     * @param responses    envelope builder.
     */
    public AppConfigController(SystemConfigService systemConfig, ResponseFactory responses) {
        this.systemConfig = systemConfig;
        this.responses = responses;
    }

    /**
     * Returns the app config for a platform (the client's boot-time min-version/force-update gate).
     *
     * @param platform the client platform ({@code ANDROID}/{@code IOS}/{@code WEB}).
     * @return {@code 200} + the platform's config.
     */
    @GetMapping("/{platform}")
    @Operation(summary = "Get the boot-time config for a client platform")
    public ApiResponse<AppConfigDto> getForPlatform(@PathVariable ClientPlatform platform) {
        return responses.ok(systemConfig.getAppConfig(platform));
    }

    /**
     * Returns the currently live feature flags (so a client can gate features).
     *
     * @return {@code 200} + all feature flags.
     */
    @GetMapping("/flags")
    @Operation(summary = "List live feature flags for clients")
    public ApiResponse<List<FeatureFlagDto>> flags() {
        return responses.ok(systemConfig.listFeatureFlags());
    }
}
