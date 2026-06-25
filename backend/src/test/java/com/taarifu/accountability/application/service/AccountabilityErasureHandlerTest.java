package com.taarifu.accountability.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.accountability.domain.model.Rating;
import com.taarifu.accountability.domain.repository.RatingRepository;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.outbox.EventEnvelope;
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
 * Unit tests for {@link AccountabilityErasureHandler} — accountability's share of the PDPA ERASURE fan-out
 * (PRD §25.1, §23.5, UC-A17/UC-S09; ADR-0016 §5).
 *
 * <p>Proves the load-bearing invariants:</p>
 * <ul>
 *   <li><b>Rater de-identified with a deterministic, non-account tombstone</b> — the rating stays counted in
 *       the aggregate (§23.5: erasure must not rewrite a democratic tally); the token is stable across a
 *       redelivery and is NOT the subject's account id;</li>
 *   <li><b>One {@code SUBJECT_DATA_ERASED} audit row APPENDED</b> with references + counts;</li>
 *   <li><b>Idempotent:</b> nothing linked → no-op (no second audit row).</li>
 * </ul>
 * Mockito only; no Docker.
 */
@ExtendWith(MockitoExtension.class)
class AccountabilityErasureHandlerTest {

    @Mock
    private RatingRepository ratingRepository;
    @Mock
    private AuditEventService audit;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID subject = UUID.randomUUID();
    private final UUID dsr = UUID.randomUUID();

    private AccountabilityErasureHandler handler() {
        return new AccountabilityErasureHandler(ratingRepository, audit, objectMapper);
    }

    private EventEnvelope<?> event() {
        return new EventEnvelope<>(UUID.randomUUID(), ErasureRequested.EVENT_TYPE,
                ErasureRequested.AGGREGATE_TYPE, dsr, new ErasureRequested(subject, dsr),
                Instant.parse("2026-06-25T10:00:00Z"));
    }

    @Test
    void handle_deidentifiesRaters_withDeterministicNonAccountTombstone_appendsAudit() {
        Rating r1 = mock(Rating.class);
        Rating r2 = mock(Rating.class);
        when(ratingRepository.findByRaterProfileId(subject)).thenReturn(List.of(r1, r2));

        handler().handle(event());

        ArgumentCaptor<UUID> tok1 = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> tok2 = ArgumentCaptor.forClass(UUID.class);
        verify(r1).anonymiseRater(tok1.capture());
        verify(r2).anonymiseRater(tok2.capture());
        // Same stable token for both rows; not the account id (rater unrecoverable, count preserved).
        assertThat(tok1.getValue()).isNotNull().isNotEqualTo(subject).isEqualTo(tok2.getValue());

        ArgumentCaptor<AuditEvent> ev = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(ev.capture());
        assertThat(ev.getValue().getEventType()).isEqualTo(AuditEventType.SUBJECT_DATA_ERASED);
        assertThat(ev.getValue().getReasonCode())
                .contains("accountability:ratings=2")
                .contains("DSR:" + dsr);
    }

    @Test
    void handle_tombstoneIsStableAcrossRedelivery() {
        Rating first = mock(Rating.class);
        Rating second = mock(Rating.class);
        when(ratingRepository.findByRaterProfileId(subject))
                .thenReturn(List.of(first)).thenReturn(List.of(second));

        handler().handle(event());
        handler().handle(event());

        ArgumentCaptor<UUID> t1 = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> t2 = ArgumentCaptor.forClass(UUID.class);
        verify(first).anonymiseRater(t1.capture());
        verify(second).anonymiseRater(t2.capture());
        assertThat(t1.getValue()).isEqualTo(t2.getValue());
    }

    @Test
    void handle_nothingLinked_isIdempotentNoOp() {
        when(ratingRepository.findByRaterProfileId(subject)).thenReturn(List.of());

        handler().handle(event());

        verify(audit, never()).record(any(AuditEvent.class));
    }
}
