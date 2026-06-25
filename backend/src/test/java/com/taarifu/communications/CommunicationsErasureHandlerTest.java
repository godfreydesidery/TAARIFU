package com.taarifu.communications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.communications.application.service.CommunicationsErasureHandler;
import com.taarifu.communications.domain.model.DeviceToken;
import com.taarifu.communications.domain.model.NotificationPreference;
import com.taarifu.communications.domain.model.enums.Channel;
import com.taarifu.communications.domain.model.enums.DevicePlatform;
import com.taarifu.communications.domain.model.enums.NotificationType;
import com.taarifu.communications.domain.repository.DeviceTokenRepository;
import com.taarifu.communications.domain.repository.NotificationPreferenceRepository;
import com.taarifu.privacy.api.event.ErasureRequested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CommunicationsErasureHandler} — this module's share of right-to-erasure
 * (PRD §18, §25.1; ADR-0016 §5, ADR-0014; the {@link ErasureRequested} fan-out).
 *
 * <p>Responsibility: prove the handler severs exactly the subject's reachability data, idempotently,
 * without a DB (Mockito only):</p>
 * <ul>
 *   <li>it registers on exactly {@link ErasureRequested#EVENT_TYPE} (the relay routes by it);</li>
 *   <li>it deserialises the relay's {@code JsonNode} payload and soft-deletes the subject's live device
 *       tokens AND notification preferences (reachability fully severed);</li>
 *   <li>a subject with nothing live is a clean no-op (idempotent redelivery — reads only live rows).</li>
 * </ul>
 */
class CommunicationsErasureHandlerTest {

    /** A real mapper — the handler deserialises the JsonNode the relay hands it, exactly as in production. */
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    private DeviceTokenRepository deviceTokenRepository;
    private NotificationPreferenceRepository preferenceRepository;
    private CommunicationsErasureHandler handler;

    private final UUID subject = UUID.randomUUID();
    private final UUID dsr = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        deviceTokenRepository = mock(DeviceTokenRepository.class);
        preferenceRepository = mock(NotificationPreferenceRepository.class);
        handler = new CommunicationsErasureHandler(deviceTokenRepository, preferenceRepository, objectMapper);
    }

    @Test
    void registersOnErasureRequestedEventType() {
        assertThat(handler.handledEventTypes()).containsExactly(ErasureRequested.EVENT_TYPE);
    }

    @Test
    void erasesSubjectsDeviceTokensAndPreferences() {
        DeviceToken token = DeviceToken.register(subject, "tok-1", DevicePlatform.ANDROID, Instant.now());
        NotificationPreference pref =
                NotificationPreference.of(subject, NotificationType.NEW_ANNOUNCEMENT, Channel.PUSH, true);
        when(deviceTokenRepository.findByProfileId(subject)).thenReturn(List.of(token));
        when(preferenceRepository.findByProfileId(subject)).thenReturn(List.of(pref));

        handler.handle(envelopeAsRelayDelivers());

        // Both routing-PII rows are soft-deleted (reachability severed) and persisted.
        assertThat(token.isDeleted()).isTrue();
        assertThat(pref.isDeleted()).isTrue();
        verify(deviceTokenRepository).saveAll(List.of(token));
        verify(preferenceRepository).saveAll(List.of(pref));
    }

    @Test
    void nothingLiveForSubject_isIdempotentNoOp() {
        when(deviceTokenRepository.findByProfileId(subject)).thenReturn(List.of());
        when(preferenceRepository.findByProfileId(subject)).thenReturn(List.of());

        handler.handle(envelopeAsRelayDelivers()); // a redelivery after an earlier erase — must not throw.

        // Reads only live rows; finds none → saveAll(emptyList) is harmless and no row is mutated (clean no-op).
        verify(deviceTokenRepository).findByProfileId(subject);
        verify(preferenceRepository).findByProfileId(subject);
        verify(deviceTokenRepository).saveAll(anyList());
        verify(preferenceRepository).saveAll(anyList());
    }

    /**
     * Wraps the payload exactly as the {@code OutboxRelay} delivers it: round-tripped through JSON to a
     * {@link JsonNode} (the relay re-reads the stored {@code jsonb} as a tree), so this exercises the
     * handler's deserialisation path, not a shortcut.
     */
    private EventEnvelope<JsonNode> envelopeAsRelayDelivers() {
        JsonNode tree = objectMapper.valueToTree(new ErasureRequested(subject, dsr));
        return new EventEnvelope<>(UUID.randomUUID(), ErasureRequested.EVENT_TYPE,
                ErasureRequested.AGGREGATE_TYPE, dsr, tree, Instant.parse("2026-06-25T09:00:00Z"));
    }
}
