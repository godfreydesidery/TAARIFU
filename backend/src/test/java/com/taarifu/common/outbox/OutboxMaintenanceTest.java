package com.taarifu.common.outbox;

import com.taarifu.common.domain.port.ClockPort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OutboxMaintenance} — retention purge of PROCESSED rows + DLQ visibility
 * (ADR-0014 §1 operability; security review P3-2).
 *
 * <p>Responsibility: prove the operability behaviours without a DB — the repository is mocked so the
 * tests pin the contract the maintenance job relies on:</p>
 * <ul>
 *   <li>the purge cutoff is {@code now - processedRetention} and the batch size comes from properties;</li>
 *   <li>the purge loops over bounded batches until one comes back short, summing the deletes;</li>
 *   <li>nothing is purged when nothing is eligible;</li>
 *   <li>the DLQ count is published to the {@code taarifu.outbox.failed} gauge and refreshed each tick.</li>
 * </ul>
 *
 * <p>That FAILED rows are never purged is enforced by the SQL predicate ({@code status = 'PROCESSED'}) in
 * {@link OutboxEventRepository#deleteProcessedOlderThan}; these unit tests assert the maintenance job only
 * ever calls that PROCESSED-pinned delete (never a blanket delete), with the Testcontainers/JPA slice
 * owning the SQL-level proof.</p>
 */
class OutboxMaintenanceTest {

    private static final Instant NOW = Instant.parse("2026-06-23T09:00:00Z");
    private final ClockPort fixedClock = () -> NOW;
    private final OutboxProperties properties = OutboxProperties.defaults();

    private OutboxMaintenance maintenance(OutboxEventRepository repo, MeterRegistry registry) {
        OutboxMaintenance m = new OutboxMaintenance(repo, fixedClock, properties, registry);
        m.registerDlqGauge();
        return m;
    }

    @Test
    void purge_usesRetentionCutoff_andConfiguredBatchSize() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        Instant expectedCutoff = NOW.minus(properties.processedRetention());
        // One short batch (< batchSize) -> loop runs exactly once.
        when(repo.deleteProcessedOlderThan(eq(expectedCutoff), eq(properties.purgeBatchSize())))
                .thenReturn(3);

        int deleted = maintenance(repo, new SimpleMeterRegistry()).purgeProcessed();

        assertThat(deleted).isEqualTo(3);
        verify(repo, times(1)).deleteProcessedOlderThan(expectedCutoff, properties.purgeBatchSize());
    }

    @Test
    void purge_loopsOverFullBatches_untilOneComesBackShort() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        int batch = properties.purgeBatchSize();
        Instant cutoff = NOW.minus(properties.processedRetention());
        // Two full batches then a short one ends the loop: total = batch + batch + 5.
        when(repo.deleteProcessedOlderThan(eq(cutoff), eq(batch)))
                .thenReturn(batch)
                .thenReturn(batch)
                .thenReturn(5);

        int deleted = maintenance(repo, new SimpleMeterRegistry()).purgeProcessed();

        assertThat(deleted).isEqualTo(batch + batch + 5);
        verify(repo, times(3)).deleteProcessedOlderThan(cutoff, batch);
    }

    @Test
    void purge_nothingEligible_isASingleNoOpBatch() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        when(repo.deleteProcessedOlderThan(eq(NOW.minus(properties.processedRetention())), anyInt()))
                .thenReturn(0);

        int deleted = maintenance(repo, new SimpleMeterRegistry()).purgeProcessed();

        assertThat(deleted).isZero();
        // A 0 (< batchSize) result must stop the loop immediately — exactly one probe call.
        verify(repo, times(1)).deleteProcessedOlderThan(NOW.minus(properties.processedRetention()),
                properties.purgeBatchSize());
        verifyNoMoreInteractions(repo);
    }

    @Test
    void refreshDlqGauge_publishesFailedCount_toMicrometerGauge() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxMaintenance maintenance = maintenance(repo, registry);
        when(repo.countFailed()).thenReturn(4L);

        long observed = maintenance.refreshDlqGauge();

        assertThat(observed).isEqualTo(4L);
        assertThat(registry.get(OutboxMaintenance.FAILED_GAUGE).gauge().value()).isEqualTo(4.0d);
    }

    @Test
    void dlqGauge_startsAtZero_beforeAnyTick() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // Hold a strong reference: the gauge keeps only a WEAK ref to the backing AtomicLong, so the
        // owning instance must stay reachable for the metric to keep reporting (not be GC'd to NaN).
        OutboxMaintenance held = maintenance(repo, registry); // gauge registered in @PostConstruct equivalent

        // Gauge exists and reports 0 from startup, before the first scheduled refresh hits the DB.
        assertThat(registry.get(OutboxMaintenance.FAILED_GAUGE).gauge().value()).isEqualTo(0.0d);
        verify(repo, never()).countFailed();
        assertThat(held).isNotNull(); // keep `held` reachable through the assertions above
    }

    @Test
    void runMaintenance_purgesThenRefreshesDlq() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(repo.deleteProcessedOlderThan(eq(NOW.minus(properties.processedRetention())), anyInt()))
                .thenReturn(0);
        when(repo.countFailed()).thenReturn(2L);

        maintenance(repo, registry).runMaintenance();

        verify(repo).deleteProcessedOlderThan(NOW.minus(properties.processedRetention()),
                properties.purgeBatchSize());
        verify(repo).countFailed();
        assertThat(registry.get(OutboxMaintenance.FAILED_GAUGE).gauge().value()).isEqualTo(2.0d);
    }

    @Test
    void retentionDefault_isSevenDays() {
        // Pins the P7D default the brief specifies (review P3-2) so an accidental change is caught.
        assertThat(properties.processedRetention()).isEqualTo(Duration.ofDays(7));
    }
}
