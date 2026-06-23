package com.taarifu.analytics;

import com.taarifu.analytics.api.RecordEventCommand;
import com.taarifu.analytics.application.service.AnalyticsRecordingService;
import com.taarifu.analytics.domain.model.AnalyticsEvent;
import com.taarifu.analytics.domain.model.enums.AnalyticsChannel;
import com.taarifu.analytics.domain.model.enums.AnalyticsEventType;
import com.taarifu.analytics.domain.model.enums.AnalyticsTier;
import com.taarifu.analytics.domain.repository.AnalyticsEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AnalyticsRecordingService} — the idempotency guarantee of the public recording API
 * (M15; PRD Appendix E.0/E.3 "idempotent via event_id"; ADR-0013).
 *
 * <p>Responsibility: proves, without a DB, that (a) a fresh event is appended, (b) a known {@code eventId}
 * is a no-op via the cheap pre-check, and (c) a concurrent insert race that trips the unique constraint is
 * swallowed as a successful no-op (no double-count, the same discipline as the token ledger). Validation
 * of the command contract is also pinned.</p>
 */
class AnalyticsRecordingServiceTest {

    private final AnalyticsEventRepository events = mock(AnalyticsEventRepository.class);
    private final AnalyticsRecordingService service = new AnalyticsRecordingService(events);

    private static RecordEventCommand command(UUID eventId) {
        return new RecordEventCommand(
                eventId, AnalyticsEventType.REPORT_FILED, Instant.parse("2026-06-23T10:00:00Z"),
                "salted-hash-abc", UUID.randomUUID(), UUID.randomUUID(),
                AnalyticsTier.T2, AnalyticsChannel.USSD, null, null, null, null);
    }

    @Test
    void record_freshEvent_appendsAndReturnsTrue() {
        UUID id = UUID.randomUUID();
        when(events.existsByEventId(id)).thenReturn(false);

        boolean recorded = service.record(command(id));

        assertThat(recorded).isTrue();
        verify(events).save(any(AnalyticsEvent.class));
    }

    @Test
    void record_knownEventId_isNoOpAndDoesNotSave() {
        UUID id = UUID.randomUUID();
        when(events.existsByEventId(id)).thenReturn(true);

        boolean recorded = service.record(command(id));

        assertThat(recorded).isFalse();
        verify(events, never()).save(any());
    }

    @Test
    void record_concurrentInsertRace_isSwallowedAsNoOp() {
        UUID id = UUID.randomUUID();
        // Pre-check passes (another worker has not yet committed), but the unique constraint fires on save.
        when(events.existsByEventId(id)).thenReturn(false);
        when(events.save(any(AnalyticsEvent.class)))
                .thenThrow(new DataIntegrityViolationException("uq_analytics_event_event_id"));

        boolean recorded = service.record(command(id));

        // The duplicate is the idempotency guarantee working — a no-op, not an error.
        assertThat(recorded).isFalse();
    }

    @Test
    void record_nullCommandOrType_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.record(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.record(new RecordEventCommand(
                UUID.randomUUID(), null, Instant.now(), null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
