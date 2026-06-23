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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SystemConfigService} — the admin system-config (app config + feature flags)
 * upsert/idempotency and clean-conflict rules (M14, UC-H07; PRD EI-16).
 *
 * <p>Responsibility: proves (a) upsert-by-natural-key updates the existing row rather than inserting a
 * second (one live row per platform / one per flag key), (b) a concurrent duplicate race surfaces as a
 * clean {@code 409 CONFLICT} (never a 500), and (c) a not-found read/delete is a typed {@code NOT_FOUND}.
 * No Docker.</p>
 */
@ExtendWith(MockitoExtension.class)
class SystemConfigServiceTest {

    @Mock
    private AppConfigRepository appConfigRepository;
    @Mock
    private FeatureFlagRepository featureFlagRepository;

    private final AdminConfigMapper mapper = new AdminConfigMapper();

    private SystemConfigService service() {
        return new SystemConfigService(appConfigRepository, featureFlagRepository, mapper);
    }

    private UpsertAppConfigRequest androidRequest(long minCode, boolean force) {
        return new UpsertAppConfigRequest(ClientPlatform.ANDROID, "2.3.0", minCode, "2.5.0", 250L,
                force, null, null);
    }

    @Test
    void upsertAppConfig_existingPlatform_updatesInPlace_noSecondRow() {
        AppConfig existing = AppConfig.create(ClientPlatform.ANDROID, "2.0.0", 200, false);
        when(appConfigRepository.findByPlatform(ClientPlatform.ANDROID)).thenReturn(Optional.of(existing));
        when(appConfigRepository.saveAndFlush(any(AppConfig.class))).thenAnswer(i -> i.getArgument(0));

        AppConfigDto dto = service().upsertAppConfig(androidRequest(230, true));

        // The same entity is mutated and saved — never a fresh create (one live row per platform).
        ArgumentCaptor<AppConfig> captor = ArgumentCaptor.forClass(AppConfig.class);
        verify(appConfigRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue()).isSameAs(existing);
        assertThat(captor.getValue().getMinSupportedVersionCode()).isEqualTo(230);
        assertThat(captor.getValue().isForceUpdate()).isTrue();
        assertThat(dto.platform()).isEqualTo(ClientPlatform.ANDROID);
        assertThat(dto.forceUpdate()).isTrue();
    }

    @Test
    void upsertAppConfig_newPlatform_creates() {
        when(appConfigRepository.findByPlatform(ClientPlatform.IOS)).thenReturn(Optional.empty());
        when(appConfigRepository.saveAndFlush(any(AppConfig.class))).thenAnswer(i -> i.getArgument(0));

        AppConfigDto dto = service().upsertAppConfig(new UpsertAppConfigRequest(
                ClientPlatform.IOS, "1.0.0", 100, null, null, false, "Karibu", null));

        assertThat(dto.platform()).isEqualTo(ClientPlatform.IOS);
        assertThat(dto.minSupportedVersionCode()).isEqualTo(100);
        assertThat(dto.splashMessage()).isEqualTo("Karibu");
    }

    @Test
    void upsertAppConfig_concurrentDuplicate_surfacesAsConflict() {
        when(appConfigRepository.findByPlatform(ClientPlatform.ANDROID)).thenReturn(Optional.empty());
        when(appConfigRepository.saveAndFlush(any(AppConfig.class)))
                .thenThrow(new DataIntegrityViolationException("ux_app_config_platform_live"));

        assertThatThrownBy(() -> service().upsertAppConfig(androidRequest(230, false)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void getAppConfig_missing_isNotFound() {
        when(appConfigRepository.findByPlatform(ClientPlatform.WEB)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getAppConfig(ClientPlatform.WEB))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void upsertFeatureFlag_existingKey_updatesInPlace() {
        FeatureFlag existing = FeatureFlag.create("payments.mpesa_purchase", "M-Pesa purchase");
        when(featureFlagRepository.findByKey("payments.mpesa_purchase")).thenReturn(Optional.of(existing));
        when(featureFlagRepository.saveAndFlush(any(FeatureFlag.class))).thenAnswer(i -> i.getArgument(0));

        FeatureFlagDto dto = service().upsertFeatureFlag(new UpsertFeatureFlagRequest(
                "payments.mpesa_purchase", "M-Pesa purchase", true, 50));

        ArgumentCaptor<FeatureFlag> captor = ArgumentCaptor.forClass(FeatureFlag.class);
        verify(featureFlagRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue()).isSameAs(existing);
        assertThat(captor.getValue().isEnabled()).isTrue();
        assertThat(captor.getValue().getRolloutPercentage()).isEqualTo(50);
        assertThat(dto.enabled()).isTrue();
    }

    @Test
    void upsertFeatureFlag_concurrentDuplicate_surfacesAsConflict() {
        when(featureFlagRepository.findByKey("x.y")).thenReturn(Optional.empty());
        when(featureFlagRepository.saveAndFlush(any(FeatureFlag.class)))
                .thenThrow(new DataIntegrityViolationException("ux_feature_flag_key_live"));

        assertThatThrownBy(() -> service().upsertFeatureFlag(
                new UpsertFeatureFlagRequest("x.y", null, true, 100)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void deleteFeatureFlag_missing_isNotFound_noSave() {
        when(featureFlagRepository.findByPublicId(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().deleteFeatureFlag(java.util.UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);

        verify(featureFlagRepository, never()).save(any());
    }
}
