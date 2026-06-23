package com.taarifu.common.outbox;

import com.taarifu.common.domain.port.ClockPort;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Operability for the transactional outbox: retention purge of old PROCESSED rows + DLQ visibility
 * (ADR-0014 §1 "operability" and revisit triggers; security review P3-2).
 *
 * <p>Responsibility: keep {@code outbox_event} — a hot, ever-growing table — bounded and observable,
 * <b>without</b> touching the relay's hot poll path:</p>
 * <ul>
 *   <li><b>Retention purge.</b> The relay never deletes a row; this job hard-purges terminally
 *       {@link OutboxStatus#PROCESSED} rows older than {@link OutboxProperties#processedRetention}
 *       (default {@code P7D}), in bounded batches ({@link OutboxProperties#purgeBatchSize}) so a large
 *       backlog never holds a long table lock or a fat transaction. The partial PENDING index keeps the
 *       <i>relay</i> fast meanwhile, so this is operational hygiene, not correctness.</li>
 *   <li><b>DLQ visibility.</b> {@link OutboxStatus#FAILED} rows are the dead-letter queue. This job
 *       publishes their count as the {@code taarifu.outbox.failed} Micrometer gauge (for SRE alerting,
 *       ARCHITECTURE §9) and logs the count at {@code WARN} whenever it is non-zero, so a growing DLQ is
 *       never silent.</li>
 * </ul>
 *
 * <p><b>🔒 FAILED rows are never purged.</b> Retention deletes only PROCESSED rows; the DLQ is preserved
 * for diagnostics and a future operator re-queue (ADR-0014 revisit trigger (c)). The purge predicate pins
 * {@code status = 'PROCESSED'} explicitly rather than keying off {@code processed_at} (which FAILED rows
 * also carry).</p>
 *
 * <p><b>Multi-instance safety.</b> The purge is an idempotent, bounded bulk delete keyed on a time cutoff;
 * if two app instances run it concurrently each deletes a disjoint set of already-eligible rows (or one
 * finds nothing) — there is no correctness hazard, only mildly redundant work. No row lock is held across
 * the loop because each batch is its own statement. (No leader election is needed at MVP scale — a
 * documented simplification, KISS.)</p>
 *
 * <p>The maintenance cadence is independent of the relay poll interval and read from
 * {@code taarifu.outbox.maintenance-interval-ms} (default 1h) on the {@code @Scheduled} annotation, because
 * annotation attributes must be constant expressions and cannot reference a bound bean. Hourly is ample —
 * retention is a slow-moving concern (days) and the DLQ gauge is also kept fresh between ticks by the relay
 * never needing it on the hot path.</p>
 */
@Component
public class OutboxMaintenance {

    private static final Logger log = LoggerFactory.getLogger(OutboxMaintenance.class);

    /** The Micrometer gauge name SRE alerts on (review P3-2). */
    static final String FAILED_GAUGE = "taarifu.outbox.failed";

    /** Upper bound on purge loop iterations per run — defence against an unexpected non-shrinking backlog. */
    private static final int MAX_PURGE_ITERATIONS = 1000;

    private final OutboxEventRepository repository;
    private final ClockPort clock;
    private final OutboxProperties properties;
    private final MeterRegistry meterRegistry;

    /**
     * Last-observed DLQ depth, refreshed on each maintenance tick and exposed via the gauge. Held in an
     * {@link AtomicLong} so the gauge read never hits the database on a metrics scrape (the count query
     * runs on our schedule, not the scraper's) and so concurrent reads see a consistent value.
     */
    private final AtomicLong failedCount = new AtomicLong(0L);

    /**
     * @param repository    the outbox store (provides the batched purge and the FAILED count).
     * @param clock         the clock port, so the retention cutoff is testable and deterministic.
     * @param properties    retention window + purge batch size (defaulted — works out of the box).
     * @param meterRegistry the Micrometer registry (auto-configured by Spring Boot actuator) the DLQ gauge
     *                      registers on.
     */
    public OutboxMaintenance(OutboxEventRepository repository, ClockPort clock,
                             OutboxProperties properties, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.clock = clock;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Registers the {@code taarifu.outbox.failed} gauge once, bound to the cached {@link #failedCount}.
     * The gauge reports whatever the last maintenance tick observed; it is seeded immediately so the metric
     * exists from startup (initially 0) rather than only after the first scheduled run.
     */
    @PostConstruct
    void registerDlqGauge() {
        Gauge.builder(FAILED_GAUGE, failedCount, AtomicLong::get)
                .description("Outbox dead-letter queue depth: rows in terminal FAILED state (ADR-0014; review P3-2)")
                .register(meterRegistry);
    }

    /**
     * One maintenance cycle: purge expired PROCESSED rows, then refresh the DLQ gauge and warn if the DLQ
     * is non-empty. Runs on its own schedule, independent of the relay poll loop.
     *
     * <p>Not annotated {@code @Transactional}: each batched purge delete and the DLQ count are individual
     * repository calls, so each runs in its OWN repository-managed transaction (a {@code @Modifying} bulk
     * delete with no surrounding transaction commits per call). That is exactly the desired per-batch
     * commit — a large backlog drains incrementally rather than in one fat transaction — and it avoids the
     * Spring self-invocation pitfall where a {@code @Transactional} method called from within the same
     * bean would not be proxied.</p>
     */
    @Scheduled(fixedDelayString = "${taarifu.outbox.maintenance-interval-ms:3600000}")
    public void runMaintenance() {
        purgeProcessed();
        refreshDlqGauge();
    }

    /**
     * Hard-purges PROCESSED rows older than the retention window, looping over bounded batches until a
     * batch comes back short (fewer rows than the batch size means the eligible set is exhausted). Each
     * batch is a separate repository delete and so commits in its own transaction. FAILED rows are
     * untouched (the purge query pins {@code status = 'PROCESSED'}).
     *
     * @return the total number of rows deleted across all batches this run (useful for tests/metrics).
     */
    int purgeProcessed() {
        Instant cutoff = clock.now().minus(properties.processedRetention());
        int batchSize = properties.purgeBatchSize();
        int totalDeleted = 0;
        for (int i = 0; i < MAX_PURGE_ITERATIONS; i++) {
            int deleted = repository.deleteProcessedOlderThan(cutoff, batchSize);
            totalDeleted += deleted;
            if (deleted < batchSize) {
                break; // eligible set exhausted (or nothing was eligible)
            }
        }
        if (totalDeleted > 0) {
            log.info("Outbox retention purge removed {} PROCESSED row(s) older than {} (cutoff {})",
                    totalDeleted, properties.processedRetention(), cutoff);
        }
        return totalDeleted;
    }

    /**
     * Re-reads the DLQ depth, updates the gauge-backing value, and logs at {@code WARN} when the DLQ is
     * non-empty so operators are alerted even without a metrics pipeline. Logs by count only — never row
     * contents (PRD §18).
     *
     * @return the current FAILED-row (DLQ) count.
     */
    long refreshDlqGauge() {
        long failed = repository.countFailed();
        failedCount.set(failed);
        if (failed > 0) {
            log.warn("Outbox DLQ depth is {} (rows in terminal FAILED state) — investigate/replay (ADR-0014)", failed);
        }
        return failed;
    }
}
