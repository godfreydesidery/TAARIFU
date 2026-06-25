package com.taarifu.media.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.media.domain.model.MediaObject;
import com.taarifu.media.domain.repository.MediaObjectRepository;
import com.taarifu.privacy.api.event.ErasureRequested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MediaErasureHandler} — media's share of the PDPA ERASURE fan-out
 * (PRD §25.1, UC-A17/UC-S09; ADR-0016 §5).
 *
 * <p>Proves: every object the subject uploaded has its uploader linkage severed; exactly one
 * {@code SUBJECT_DATA_ERASED} audit row is APPENDED with references + counts; a redelivery with nothing
 * still linked is an idempotent no-op. Mockito only; no Docker.</p>
 */
@ExtendWith(MockitoExtension.class)
class MediaErasureHandlerTest {

    @Mock
    private MediaObjectRepository mediaObjectRepository;
    @Mock
    private AuditEventService audit;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID subject = UUID.randomUUID();
    private final UUID dsr = UUID.randomUUID();

    private MediaErasureHandler handler() {
        return new MediaErasureHandler(mediaObjectRepository, audit, objectMapper);
    }

    private EventEnvelope<?> event() {
        return new EventEnvelope<>(UUID.randomUUID(), ErasureRequested.EVENT_TYPE,
                ErasureRequested.AGGREGATE_TYPE, dsr, new ErasureRequested(subject, dsr),
                Instant.parse("2026-06-25T10:00:00Z"));
    }

    @Test
    void handle_seversUploaderLinkage_appendsAuditTombstone() {
        MediaObject obj1 = mock(MediaObject.class);
        MediaObject obj2 = mock(MediaObject.class);
        when(mediaObjectRepository.findByUploadedByProfileId(subject)).thenReturn(List.of(obj1, obj2));

        handler().handle(event());

        verify(obj1).severUploader();
        verify(obj2).severUploader();

        ArgumentCaptor<AuditEvent> ev = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(ev.capture());
        assertThat(ev.getValue().getEventType()).isEqualTo(AuditEventType.SUBJECT_DATA_ERASED);
        assertThat(ev.getValue().getReasonCode())
                .contains("media:objects=2")
                .contains("DSR:" + dsr);
    }

    @Test
    void handle_nothingLinked_isIdempotentNoOp() {
        when(mediaObjectRepository.findByUploadedByProfileId(subject)).thenReturn(List.of());

        handler().handle(event());

        verify(audit, never()).record(any(AuditEvent.class));
    }
}
