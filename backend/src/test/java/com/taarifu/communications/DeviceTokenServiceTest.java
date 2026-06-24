package com.taarifu.communications;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.communications.application.service.DeviceTokenService;
import com.taarifu.communications.domain.model.DeviceToken;
import com.taarifu.communications.domain.model.enums.DevicePlatform;
import com.taarifu.communications.domain.repository.DeviceTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DeviceTokenService} — the push device-token registry rules (PRD §13, EI-5).
 *
 * <p>Responsibility: pins the load-bearing invariants — registering a known token is an idempotent re-bind
 * (no duplicate row), unregistering another user's token is forbidden (a citizen never silences another's
 * device), unregistering a missing token is an idempotent no-op, prune is an idempotent soft-delete safe on
 * an unknown token, {@code tokensFor} returns the freshest device first, and a blank token / unknown
 * platform is a validation failure. The ownership test fails if the guard is removed.</p>
 */
class DeviceTokenServiceTest {

    private DeviceTokenRepository repository;
    private FixedClock clock;
    private DeviceTokenService service;
    private final UUID caller = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(DeviceTokenRepository.class);
        clock = new FixedClock(Instant.parse("2026-06-24T10:00:00Z"));
        service = new DeviceTokenService(repository, clock);
        when(repository.save(any(DeviceToken.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void register_newToken_insertsBoundToCaller() {
        when(repository.findByToken("tok-1")).thenReturn(Optional.empty());

        DeviceToken out = service.register(caller, "tok-1", "ANDROID");

        assertThat(out.getProfileId()).isEqualTo(caller);
        assertThat(out.getToken()).isEqualTo("tok-1");
        assertThat(out.getPlatform()).isEqualTo(DevicePlatform.ANDROID);
        verify(repository).save(out);
    }

    @Test
    void register_knownToken_isIdempotent_rebindsAndRefreshes_noDuplicate() {
        UUID previousOwner = UUID.randomUUID();
        DeviceToken existing = DeviceToken.register(previousOwner, "tok-2", DevicePlatform.IOS,
                Instant.parse("2026-01-01T00:00:00Z"));
        when(repository.findByToken("tok-2")).thenReturn(Optional.of(existing));

        // Same physical token re-registers under the current caller (device handed to a new login).
        DeviceToken out = service.register(caller, "tok-2", "WEB");

        assertThat(out).isSameAs(existing);                 // no new row
        assertThat(out.getProfileId()).isEqualTo(caller);   // re-bound
        assertThat(out.getPlatform()).isEqualTo(DevicePlatform.WEB);
        assertThat(out.getLastSeenAt()).isEqualTo(clock.now());
        verify(repository).save(existing);
    }

    @Test
    void register_trimsToken() {
        when(repository.findByToken("tok-3")).thenReturn(Optional.empty());

        DeviceToken out = service.register(caller, "  tok-3  ", "ANDROID");

        assertThat(out.getToken()).isEqualTo("tok-3");
    }

    @Test
    void register_blankToken_isValidationFailure() {
        assertThatThrownBy(() -> service.register(caller, "   ", "ANDROID"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repository, never()).save(any());
    }

    @Test
    void register_unknownPlatform_isValidationFailure() {
        when(repository.findByToken(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.register(caller, "tok", "WATCH"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repository, never()).save(any());
    }

    @Test
    void unregister_anotherUsersToken_isForbidden() {
        DeviceToken othersToken = DeviceToken.register(UUID.randomUUID(), "tok-x",
                DevicePlatform.ANDROID, clock.now());
        when(repository.findByToken("tok-x")).thenReturn(Optional.of(othersToken));

        assertThatThrownBy(() -> service.unregister(caller, "tok-x"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(othersToken.isDeleted()).isFalse();
        verify(repository, never()).save(any());
    }

    @Test
    void unregister_ownToken_softDeletes() {
        DeviceToken mine = DeviceToken.register(caller, "tok-mine", DevicePlatform.ANDROID, clock.now());
        when(repository.findByToken("tok-mine")).thenReturn(Optional.of(mine));

        service.unregister(caller, "tok-mine");

        assertThat(mine.isDeleted()).isTrue();
        verify(repository).save(mine);
    }

    @Test
    void unregister_missingToken_isNoOpSuccess() {
        when(repository.findByToken("gone")).thenReturn(Optional.empty());

        service.unregister(caller, "gone"); // must not throw

        verify(repository, never()).save(any());
    }

    @Test
    void pruneInvalid_softDeletesKnownToken_idempotentOnUnknown() {
        DeviceToken dead = DeviceToken.register(caller, "dead", DevicePlatform.ANDROID, clock.now());
        when(repository.findByToken("dead")).thenReturn(Optional.of(dead));
        when(repository.findByToken("unknown")).thenReturn(Optional.empty());

        service.pruneInvalid("dead");
        service.pruneInvalid("unknown"); // no-op, must not throw

        assertThat(dead.isDeleted()).isTrue();
        verify(repository).save(dead);
    }

    @Test
    void tokensFor_returnsFreshestDeviceFirst() {
        DeviceToken older = DeviceToken.register(caller, "old", DevicePlatform.ANDROID,
                Instant.parse("2026-01-01T00:00:00Z"));
        DeviceToken newer = DeviceToken.register(caller, "new", DevicePlatform.ANDROID,
                Instant.parse("2026-06-01T00:00:00Z"));
        when(repository.findByProfileId(caller)).thenReturn(List.of(older, newer));

        assertThat(service.tokensFor(caller)).containsExactly("new", "old");
    }
}
