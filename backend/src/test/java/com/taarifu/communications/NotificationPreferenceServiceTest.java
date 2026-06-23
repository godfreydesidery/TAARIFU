package com.taarifu.communications;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.communications.application.service.NotificationPreferenceService;
import com.taarifu.communications.domain.model.NotificationPreference;
import com.taarifu.communications.domain.model.enums.Channel;
import com.taarifu.communications.domain.model.enums.NotificationType;
import com.taarifu.communications.domain.repository.NotificationPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NotificationPreferenceService} — the opt-in + always-on invariants of M5
 * (PRD §13, UC-G08).
 *
 * <p>Responsibility: pins that an upsert validates the type/channel, refuses to disable an always-on
 * type (SYSTEM/MODERATION_OUTCOME), and updates an existing row in place. The always-on test fails if
 * the guard is removed — a citizen must never be able to silence security/moderation notices.</p>
 */
class NotificationPreferenceServiceTest {

    private NotificationPreferenceRepository repository;
    private NotificationPreferenceService service;
    private final UUID caller = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(NotificationPreferenceRepository.class);
        service = new NotificationPreferenceService(repository);
        when(repository.save(any(NotificationPreference.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findByProfileIdAndTypeAndChannel(any(), any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void upsert_createsNewPreference() {
        NotificationPreference p = service.upsert(caller, "NEW_ANNOUNCEMENT", "PUSH", true,
                LocalTime.of(22, 0), LocalTime.of(6, 0), "sw");

        assertThat(p.getType()).isEqualTo(NotificationType.NEW_ANNOUNCEMENT);
        assertThat(p.getChannel()).isEqualTo(Channel.PUSH);
        assertThat(p.isEnabled()).isTrue();
        assertThat(p.getQuietFrom()).isEqualTo(LocalTime.of(22, 0));
    }

    @Test
    void upsert_updatesExistingPreferenceInPlace() {
        NotificationPreference existing =
                NotificationPreference.of(caller, NotificationType.NEW_ANNOUNCEMENT, Channel.PUSH, true);
        when(repository.findByProfileIdAndTypeAndChannel(eq(caller),
                eq(NotificationType.NEW_ANNOUNCEMENT), eq(Channel.PUSH)))
                .thenReturn(Optional.of(existing));

        NotificationPreference p = service.upsert(caller, "NEW_ANNOUNCEMENT", "PUSH", false,
                null, null, "en");

        assertThat(p).isSameAs(existing);
        assertThat(p.isEnabled()).isFalse();
        assertThat(p.getLanguage()).isEqualTo("en");
    }

    @Test
    void disablingAlwaysOnType_isRejected() {
        assertThatThrownBy(() -> service.upsert(caller, "SYSTEM", "PUSH", false, null, null, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void unknownTypeOrChannel_isValidationFailure() {
        assertThatThrownBy(() -> service.upsert(caller, "NOPE", "PUSH", true, null, null, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.upsert(caller, "SYSTEM", "SMOKE_SIGNAL", true, null, null, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
