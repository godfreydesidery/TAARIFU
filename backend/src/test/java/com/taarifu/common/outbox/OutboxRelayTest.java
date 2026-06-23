package com.taarifu.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.outbox.domain.model.OutboxEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OutboxRelay} — the at-least-once, idempotent, backed-off poller (ADR-0014 §3).
 *
 * <p>Responsibility: prove the relay's state machine without a DB — claim a batch, dispatch each row, and
 * drive it to the correct terminal state — covering the four behaviours the ADR pins:</p>
 * <ul>
 *   <li><b>happy path</b>: a PENDING row dispatches to its handler and becomes PROCESSED;</li>
 *   <li><b>no consumer</b>: an event with no registered handler is still marked PROCESSED (additive bus);</li>
 *   <li><b>handler idempotency</b>: a redelivered event reaches the same handler again (at-least-once),
 *       and the handler's dedup makes the effect happen once;</li>
 *   <li><b>failure path</b>: a throwing handler retries with a backed-off {@code next_attempt_at} (row
 *       stays PENDING, {@code attempts++}) until the cap, then becomes terminal FAILED — and one row's
 *       failure never disturbs a sibling row's success.</li>
 * </ul>
 *
 * <p>A real {@link EventDispatcher} (built from in-test handler beans) is used so dispatch routing is
 * exercised end-to-end; the repository is mocked to return a controlled "claimed" batch.</p>
 */
class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-06-23T09:00:00Z");
    private final ClockPort fixedClock = () -> NOW;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** A test handler that records the {@code eventId}s it has seen and asserts idempotent effect. */
    private static final class RecordingHandler implements DomainEventHandler {
        private final String type;
        private final List<UUID> seen = new java.util.ArrayList<>();
        private final java.util.Set<UUID> applied = new java.util.HashSet<>();
        private final AtomicInteger effects = new AtomicInteger();
        private final boolean alwaysThrow;

        RecordingHandler(String type, boolean alwaysThrow) {
            this.type = type;
            this.alwaysThrow = alwaysThrow;
        }

        @Override public Set<String> handledEventTypes() {
            return Set.of(type);
        }

        @Override public void handle(EventEnvelope<?> event) {
            seen.add(event.eventId());
            if (alwaysThrow) {
                throw new IllegalStateException("downstream unavailable");
            }
            // Idempotent effect: apply once per eventId even if delivered twice (at-least-once).
            if (applied.add(event.eventId())) {
                effects.incrementAndGet();
            }
        }
    }

    /** Builds a persisted-looking PENDING row with a known public_id (the eventId). */
    private OutboxEvent pendingRow(String eventType, UUID publicId) {
        OutboxEvent row = OutboxEvent.pending(
                "REPORT", UUID.randomUUID(), eventType,
                objectMapperWrite(java.util.Map.of("reportId", UUID.randomUUID().toString())),
                NOW.minusSeconds(5), NOW.minusSeconds(5));
        // BaseEntity.publicId is normally assigned on persist; set it via the JPA lifecycle hook proxy.
        setPublicId(row, publicId);
        return row;
    }

    private String objectMapperWrite(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Reflectively assigns the inherited {@code publicId} (no public setter — it is persistence-managed). */
    private static void setPublicId(OutboxEvent row, UUID publicId) {
        try {
            var field = Class.forName("com.taarifu.common.domain.model.BaseEntity")
                    .getDeclaredField("publicId");
            field.setAccessible(true);
            field.set(row, publicId);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private OutboxRelay relay(OutboxEventRepository repo, EventDispatcher dispatcher) {
        return new OutboxRelay(repo, dispatcher, objectMapper, fixedClock, OutboxProperties.defaults());
    }

    @Test
    void pendingRow_isDispatchedToHandler_andMarkedProcessed() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        RecordingHandler handler = new RecordingHandler("REPORT_ROUTED", false);
        EventDispatcher dispatcher = new EventDispatcher(List.of(handler));
        UUID eventId = UUID.randomUUID();
        OutboxEvent row = pendingRow("REPORT_ROUTED", eventId);
        when(repo.claimDue(any(Instant.class), any())).thenReturn(List.of(row));

        int dispatched = relay(repo, dispatcher).poll();

        assertThat(dispatched).isEqualTo(1);
        assertThat(handler.seen).containsExactly(eventId);
        assertThat(handler.effects.get()).isEqualTo(1);
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
        assertThat(row.getProcessedAt()).isEqualTo(NOW);
        assertThat(row.getAttempts()).isZero();
    }

    @Test
    void eventWithNoRegisteredHandler_isStillMarkedProcessed() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        EventDispatcher dispatcher = new EventDispatcher(List.of()); // no handlers at all
        OutboxEvent row = pendingRow("UNCONSUMED_EVENT", UUID.randomUUID());
        when(repo.claimDue(any(Instant.class), any())).thenReturn(List.of(row));

        relay(repo, dispatcher).poll();

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
        assertThat(row.getProcessedAt()).isEqualTo(NOW);
    }

    @Test
    void redeliveredEvent_reachesHandlerTwice_butEffectAppliesOnce() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        RecordingHandler handler = new RecordingHandler("REPORT_ROUTED", false);
        EventDispatcher dispatcher = new EventDispatcher(List.of(handler));
        UUID eventId = UUID.randomUUID();
        OutboxRelay relay = relay(repo, dispatcher);

        // Two separate poll cycles deliver the SAME eventId (simulating the at-least-once crash window).
        when(repo.claimDue(any(Instant.class), any()))
                .thenReturn(List.of(pendingRow("REPORT_ROUTED", eventId)))
                .thenReturn(List.of(pendingRow("REPORT_ROUTED", eventId)));

        relay.poll();
        relay.poll();

        assertThat(handler.seen).containsExactly(eventId, eventId); // delivered twice (at-least-once)
        assertThat(handler.effects.get()).isEqualTo(1);             // effect applied once (idempotent)
    }

    @Test
    void handlerException_belowCap_schedulesBackedOffRetry_rowStaysPending() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        RecordingHandler failing = new RecordingHandler("REPORT_ROUTED", true);
        EventDispatcher dispatcher = new EventDispatcher(List.of(failing));
        OutboxEvent row = pendingRow("REPORT_ROUTED", UUID.randomUUID());
        when(repo.claimDue(any(Instant.class), any())).thenReturn(List.of(row));

        relay(repo, dispatcher).poll(); // does NOT throw

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);   // still retryable
        assertThat(row.getAttempts()).isEqualTo(1);                    // one failed attempt recorded
        assertThat(row.getNextAttemptAt()).isAfterOrEqualTo(NOW);      // backed off (full jitter -> [now, now+cap])
        assertThat(row.getLastError()).contains("IllegalStateException"); // redacted: class + message, no stack
        assertThat(row.getLastError()).contains("downstream unavailable");
        assertThat(row.getProcessedAt()).isNull();
    }

    @Test
    void handlerException_atCap_marksRowFailed_theDlq() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        RecordingHandler failing = new RecordingHandler("REPORT_ROUTED", true);
        EventDispatcher dispatcher = new EventDispatcher(List.of(failing));
        OutboxEvent row = pendingRow("REPORT_ROUTED", UUID.randomUUID());
        // Drive attempts to one below the cap (default 8) so THIS failed try is the terminal one.
        for (int i = 0; i < OutboxProperties.defaults().maxAttempts() - 1; i++) {
            row.scheduleRetry(NOW, "prior");
        }
        when(repo.claimDue(any(Instant.class), any())).thenReturn(List.of(row));

        relay(repo, dispatcher).poll(); // does NOT throw

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);                 // terminal DLQ
        assertThat(row.getAttempts()).isEqualTo(OutboxProperties.defaults().maxAttempts());
        assertThat(row.getProcessedAt()).isEqualTo(NOW);
        assertThat(row.getLastError()).contains("IllegalStateException");
    }

    @Test
    void oneRowFailure_doesNotDisturbSiblingRowSuccess() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        RecordingHandler good = new RecordingHandler("GOOD_EVENT", false);
        RecordingHandler bad = new RecordingHandler("BAD_EVENT", true);
        EventDispatcher dispatcher = new EventDispatcher(List.of(good, bad));
        OutboxEvent okRow = pendingRow("GOOD_EVENT", UUID.randomUUID());
        OutboxEvent badRow = pendingRow("BAD_EVENT", UUID.randomUUID());
        when(repo.claimDue(any(Instant.class), any())).thenReturn(List.of(okRow, badRow));

        int dispatched = relay(repo, dispatcher).poll();

        assertThat(dispatched).isEqualTo(2);
        assertThat(okRow.getStatus()).isEqualTo(OutboxStatus.PROCESSED); // sibling unaffected
        assertThat(badRow.getStatus()).isEqualTo(OutboxStatus.PENDING);  // failed row isolated to retry
        assertThat(badRow.getAttempts()).isEqualTo(1);
    }

    @Test
    void emptyBatch_isANoOp() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        EventDispatcher dispatcher = new EventDispatcher(List.of());
        when(repo.claimDue(any(Instant.class), any())).thenReturn(List.of());

        assertThat(relay(repo, dispatcher).poll()).isZero();
    }

    @Test
    void backoffNeverExceedsCap() {
        // Reflective check that the highest-attempt backoff is clamped to the cap (full jitter -> <= cap).
        OutboxProperties props = OutboxProperties.defaults();
        Duration cap = props.backoffCap();
        // Drive a row to a high attempt count and assert next_attempt_at stays within [now, now+cap].
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        RecordingHandler failing = new RecordingHandler("REPORT_ROUTED", true);
        EventDispatcher dispatcher = new EventDispatcher(List.of(failing));
        OutboxEvent row = pendingRow("REPORT_ROUTED", UUID.randomUUID());
        for (int i = 0; i < 5; i++) {
            row.scheduleRetry(NOW, "prior");
        }
        when(repo.claimDue(any(Instant.class), any())).thenReturn(List.of(row));

        relay(repo, dispatcher).poll();

        assertThat(row.getNextAttemptAt()).isBetween(NOW, NOW.plus(cap));
    }
}
