package com.taarifu.common.outbox;

import com.taarifu.AbstractPostgisIntegrationTest;
import com.taarifu.common.outbox.domain.model.OutboxEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Testcontainers regression test for the outbox retention purge transaction boundary
 * (fix: {@code @Modifying} bulk delete needs a transaction; ADR-0014 §1 operability, review P3-2).
 *
 * <p>Responsibility: prove the purge runs <b>cleanly against a real PostgreSQL</b> from the maintenance
 * job's non-transactional call site, and deletes <b>only old PROCESSED rows</b> — leaving PENDING, FAILED
 * (the DLQ), and recent PROCESSED rows untouched.</p>
 *
 * <p><b>WHY this is the test that fails if the guard is removed.</b> The maintenance loop
 * ({@link OutboxMaintenance#purgeProcessed}) calls {@link OutboxEventRepository#deleteProcessedOlderThan}
 * with <b>no surrounding transaction</b>. A custom {@code @Query @Modifying} method is not wrapped in a
 * transaction by Spring Data unless it (or its caller) declares one; without the {@code @Transactional} now
 * on that repository method, this exact path throws
 * {@link org.springframework.dao.InvalidDataAccessApiUsageException} ("Executing an update/delete query") —
 * the production ERROR this fix resolves. These tests deliberately invoke the repository delete and the
 * full {@link OutboxMaintenance#purgeProcessed} loop <b>outside</b> any test-managed transaction, so the
 * only transaction available is the one the repository method itself now supplies. Pure-Mockito unit tests
 * (see {@link OutboxMaintenanceTest}) cannot catch a missing transaction — only a real datasource can.</p>
 *
 * <p><b>Short-lock semantics preserved.</b> Because the boundary is on the per-batch repository method (one
 * transaction per call) rather than on the loop, a backlog drains over several committed batches; this test
 * also exercises the multi-batch loop with a tiny batch size to confirm it terminates and commits each
 * batch.</p>
 *
 * <p>Uses {@code @SpringBootTest} (not {@code @DataJpaTest}) so the repository proxy carries its real
 * {@code @Transactional} semantics — {@code @DataJpaTest} wraps each test in a rollback-only transaction,
 * which would mask exactly the missing-transaction defect under test.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
// Disable the background @Scheduled OutboxRelay for this class: it directly seeds and deletes outbox_event
// rows, so the 1s poller would otherwise claim/version-bump the seeded PENDING rows concurrently and cause a
// StaleObjectStateException. This test exercises the purge transaction boundary, not dispatch.
@TestPropertySource(properties = "taarifu.outbox.relay.enabled=false")
class OutboxMaintenancePurgeIntegrationTest extends AbstractPostgisIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-06-24T12:00:00Z");

    /** Retention is 7 days by default; "old" = comfortably beyond the cutoff, "recent" = inside the window. */
    private static final Instant OLD_PROCESSED_AT = NOW.minus(30, ChronoUnit.DAYS);
    private static final Instant RECENT_PROCESSED_AT = NOW.minus(1, ChronoUnit.HOURS);

    @Autowired
    private OutboxEventRepository repository;

    private final OutboxProperties properties = OutboxProperties.defaults();
    private final Instant cutoff = NOW.minus(properties.processedRetention());

    /** Start every test from an empty table so counts are deterministic (no test-tx wrapper to roll back). */
    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void purge_runsCleanly_outsideAnyTransaction_andDeletesOnlyOldProcessedRows() {
        // Two old PROCESSED rows (eligible) + one recent PROCESSED + one PENDING + one FAILED (all kept).
        UUID oldA = saveProcessed(OLD_PROCESSED_AT);
        UUID oldB = saveProcessed(OLD_PROCESSED_AT);
        UUID recent = saveProcessed(RECENT_PROCESSED_AT);
        UUID pending = savePending();
        UUID failed = saveFailed(OLD_PROCESSED_AT); // FAILED also carries processed_at — must NOT be purged

        // The maintenance call site: no @Transactional here. If the repo delete lacked one, this throws
        // InvalidDataAccessApiUsageException ("Executing an update/delete query").
        int[] deleted = new int[1];
        assertThatCode(() -> deleted[0] = repository.deleteProcessedOlderThan(cutoff, properties.purgeBatchSize()))
                .doesNotThrowAnyException();

        assertThat(deleted[0]).isEqualTo(2); // only the two old PROCESSED rows
        assertThat(existsById(oldA)).isFalse();
        assertThat(existsById(oldB)).isFalse();
        assertThat(existsById(recent)).as("recent PROCESSED is inside the retention window").isTrue();
        assertThat(existsById(pending)).as("PENDING is still in flight").isTrue();
        assertThat(existsById(failed)).as("FAILED is the DLQ — never purged by retention").isTrue();
    }

    @Test
    void maintenancePurgeLoop_drainsBacklogOverBatches_committingEach() {
        // Seed five eligible old PROCESSED rows; a tiny batch size forces the loop to commit several batches.
        for (int i = 0; i < 5; i++) {
            saveProcessed(OLD_PROCESSED_AT);
        }
        // One recent PROCESSED survivor proves the loop stops at the eligible boundary, not the table.
        UUID survivor = saveProcessed(RECENT_PROCESSED_AT);

        OutboxProperties tinyBatch = new OutboxProperties(null, null, null, null, null, 2); // purgeBatchSize=2
        OutboxMaintenance maintenance =
                new OutboxMaintenance(repository, () -> NOW, tinyBatch, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        int[] purged = new int[1];
        assertThatCode(() -> purged[0] = maintenance.purgeProcessed()).doesNotThrowAnyException();

        assertThat(purged[0]).isEqualTo(5);                 // all five eligible rows, across 2+2+1 batches
        assertThat(existsById(survivor)).isTrue();          // recent row survives
        assertThat(repository.count()).isEqualTo(1L);       // only the survivor remains
    }

    @Test
    void purge_whenNothingEligible_isACleanNoOp() {
        savePending();
        saveProcessed(RECENT_PROCESSED_AT);
        saveFailed(OLD_PROCESSED_AT);

        int[] deleted = new int[1];
        assertThatCode(() -> deleted[0] = repository.deleteProcessedOlderThan(cutoff, properties.purgeBatchSize()))
                .doesNotThrowAnyException();

        assertThat(deleted[0]).isZero();
        assertThat(repository.count()).isEqualTo(3L); // nothing removed
    }

    // --- seeding helpers (each save() runs in the repository's own transaction) ---------------------------

    /** Persists a PENDING row due now; returns its public id. */
    private UUID savePending() {
        OutboxEvent row = OutboxEvent.pending(
                "REPORT", UUID.randomUUID(), "REPORT_ROUTED", payload(), NOW.minusSeconds(60), NOW);
        return repository.save(row).getPublicId();
    }

    /** Persists a PROCESSED row with an explicit {@code processed_at}; returns its public id. */
    private UUID saveProcessed(Instant processedAt) {
        OutboxEvent row = OutboxEvent.pending(
                "REPORT", UUID.randomUUID(), "REPORT_ROUTED", payload(), processedAt.minusSeconds(1), NOW);
        row.markProcessed(processedAt);
        return repository.save(row).getPublicId();
    }

    /** Persists a terminal FAILED (DLQ) row with an explicit failure {@code processed_at}; returns its id. */
    private UUID saveFailed(Instant failedAt) {
        OutboxEvent row = OutboxEvent.pending(
                "REPORT", UUID.randomUUID(), "REPORT_ROUTED", payload(), failedAt.minusSeconds(1), NOW);
        row.markFailed(failedAt, "TestException: seeded DLQ row");
        return repository.save(row).getPublicId();
    }

    /** Minimal ids-only JSON payload (PRD §18 — never PII). */
    private static String payload() {
        return "{\"reportId\":\"" + UUID.randomUUID() + "\"}";
    }

    /** True if a row with this public id still exists (purge removes the surrogate row, not just the status). */
    private boolean existsById(UUID publicId) {
        return repository.findAll().stream().anyMatch(e -> publicId.equals(e.getPublicId()));
    }
}
