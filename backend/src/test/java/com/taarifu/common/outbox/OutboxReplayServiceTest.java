package com.taarifu.common.outbox;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.outbox.OutboxReplayService.ReplayFilter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OutboxReplayService} — DLQ replay that re-queues FAILED rows to PENDING
 * (ADR-0014 revisit trigger (c); outbox-review P4-4).
 *
 * <p>Responsibility: prove the replay contract without a DB — the repository is mocked, so the tests pin
 * exactly what the service asks of the store and how it reports the outcome:</p>
 * <ul>
 *   <li>a single FAILED row is re-queued with {@code next_attempt_at = now()} (the counter/field reset
 *       — {@code attempts=0}, {@code last_error=NULL}, {@code processed_at=NULL} — is pinned by the
 *       FAILED-only SQL and proven at the JPA slice; here we assert the service drives that statement);</li>
 *   <li>a non-FAILED / unknown id is a no-op (returns 0) — the idempotent path;</li>
 *   <li>bulk replay passes the {@code eventType} + {@code processed_at} window through and applies the
 *       configured default cap when none is supplied, and an explicit positive limit overrides it.</li>
 * </ul>
 *
 * <p>That replay <b>only</b> ever touches FAILED rows (never PROCESSED/PENDING) is enforced by the
 * {@code WHERE status='FAILED'} predicate in {@link OutboxEventRepository#requeueFailedById} /
 * {@link OutboxEventRepository#requeueFailedBatch}; these unit tests assert the service calls only those
 * FAILED-pinned statements (never a blanket update), with the Testcontainers/JPA slice owning the
 * SQL-level proof.</p>
 */
class OutboxReplayServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-24T09:00:00Z");
    private final ClockPort fixedClock = () -> NOW;
    private final OutboxProperties properties = OutboxProperties.defaults();

    private OutboxReplayService service(OutboxEventRepository repo) {
        return new OutboxReplayService(repo, fixedClock, properties);
    }

    @Test
    void replayById_requeuesFailedRow_atNow_andReportsOne() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        UUID eventId = UUID.randomUUID();
        when(repo.requeueFailedById(eventId, NOW)).thenReturn(1);

        int requeued = service(repo).replayById(eventId);

        assertThat(requeued).isEqualTo(1);
        // next_attempt_at must be the clock's now (due immediately); the FAILED->PENDING + reset is in SQL.
        verify(repo, times(1)).requeueFailedById(eventId, NOW);
        verifyNoMoreInteractions(repo);
    }

    @Test
    void replayById_nonFailedOrUnknown_isANoOp_returningZero() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        UUID eventId = UUID.randomUUID();
        // Row is PROCESSED/PENDING or does not exist -> the FAILED-pinned UPDATE matches 0 rows.
        when(repo.requeueFailedById(eventId, NOW)).thenReturn(0);

        int requeued = service(repo).replayById(eventId);

        assertThat(requeued).isZero();
        verify(repo, times(1)).requeueFailedById(eventId, NOW);
        verifyNoMoreInteractions(repo);
    }

    @Test
    void replayById_isIdempotent_secondCallMatchesNothing() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        UUID eventId = UUID.randomUUID();
        // First call re-queues the FAILED row; the second finds it already PENDING -> 0 (idempotent).
        when(repo.requeueFailedById(eventId, NOW)).thenReturn(1).thenReturn(0);

        OutboxReplayService service = service(repo);
        int first = service.replayById(eventId);
        int second = service.replayById(eventId);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        verify(repo, times(2)).requeueFailedById(eventId, NOW);
    }

    @Test
    void replayById_nullId_throws_andNeverTouchesRepository() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);

        assertThatNullPointerException().isThrownBy(() -> service(repo).replayById(null));

        verifyNoInteractions(repo);
    }

    @Test
    void replayBatch_all_appliesDefaultCap_andNoFilters() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        when(repo.requeueFailedBatch(isNull(), isNull(), isNull(), eq(NOW),
                eq(properties.purgeBatchSize()))).thenReturn(7);

        int requeued = service(repo).replayBatch(ReplayFilter.all());

        assertThat(requeued).isEqualTo(7);
        // No filters (all null), now from the clock, and the default purge batch size as the cap.
        verify(repo, times(1)).requeueFailedBatch(isNull(), isNull(), isNull(), eq(NOW),
                eq(properties.purgeBatchSize()));
        verifyNoMoreInteractions(repo);
    }

    @Test
    void replayBatch_passesEventTypeAndProcessedWindow_through() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        Instant from = NOW.minusSeconds(3600);
        Instant to = NOW.minusSeconds(60);
        ReplayFilter filter = new ReplayFilter("REPORT_ROUTED", from, to, null);
        when(repo.requeueFailedBatch(eq("REPORT_ROUTED"), eq(from), eq(to), eq(NOW),
                eq(properties.purgeBatchSize()))).thenReturn(2);

        int requeued = service(repo).replayBatch(filter);

        assertThat(requeued).isEqualTo(2);
        verify(repo, times(1)).requeueFailedBatch("REPORT_ROUTED", from, to, NOW,
                properties.purgeBatchSize());
        verifyNoMoreInteractions(repo);
    }

    @Test
    void replayBatch_explicitPositiveLimit_overridesDefaultCap() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        ReplayFilter filter = new ReplayFilter(null, null, null, 10);
        when(repo.requeueFailedBatch(isNull(), isNull(), isNull(), eq(NOW), eq(10))).thenReturn(10);

        int requeued = service(repo).replayBatch(filter);

        assertThat(requeued).isEqualTo(10);
        verify(repo, times(1)).requeueFailedBatch(isNull(), isNull(), isNull(), eq(NOW), eq(10));
        verifyNoMoreInteractions(repo);
    }

    @Test
    void replayBatch_nonPositiveLimit_fallsBackToDefaultCap() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        ReplayFilter filter = new ReplayFilter(null, null, null, 0); // 0/negative -> default cap
        when(repo.requeueFailedBatch(isNull(), isNull(), isNull(), eq(NOW),
                eq(properties.purgeBatchSize()))).thenReturn(0);

        int requeued = service(repo).replayBatch(filter);

        assertThat(requeued).isZero();
        verify(repo, times(1)).requeueFailedBatch(isNull(), isNull(), isNull(), eq(NOW),
                eq(properties.purgeBatchSize()));
    }

    @Test
    void replayBatch_nullFilter_throws_andNeverTouchesRepository() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);

        assertThatNullPointerException().isThrownBy(() -> service(repo).replayBatch(null));

        verifyNoInteractions(repo);
    }
}
