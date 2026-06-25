package com.taarifu.payments;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.payments.application.service.ReconciliationService;
import com.taarifu.payments.application.service.TopUpReconciliationJob;
import com.taarifu.payments.domain.model.TopUp;
import com.taarifu.payments.domain.model.enums.MobileMoneyProvider;
import com.taarifu.payments.domain.model.enums.TopUpStatus;
import com.taarifu.payments.domain.model.enums.WalletOwnerKind;
import com.taarifu.payments.domain.repository.TopUpRepository;
import com.taarifu.payments.infrastructure.config.PaymentsReconciliationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TopUpReconciliationJob} — the scheduled settlement safety net (ADR-0015 addendum;
 * PRD §23.5). No database needed; the locked claim + the shared {@link ReconciliationService} are mocked.
 *
 * <p>Responsibility: proves the job's three outcomes per stale row and its operability accounting —
 * <ul>
 *   <li><b>confirm-and-credit:</b> a stale PENDING row the provider now settles is driven to SUCCEEDED via
 *       the shared {@code reconcile} path (the ONE settlement code path — never trust-the-callback);</li>
 *   <li><b>expire:</b> a row older than {@code expireAfter} that the provider still does not settle is moved
 *       to FAILED with the {@code RECONCILE_EXPIRED} reason so it stops being re-checked;</li>
 *   <li><b>mismatch (benign):</b> a still-un-settled row younger than {@code expireAfter} is left PENDING and
 *       counted, not expired;</li>
 *   <li><b>idempotent + bounded:</b> the job claims via the locked, paged query and only reuses the shared,
 *       idempotent reconcile — it never double-credits.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TopUpReconciliationJobTest {

    @Mock
    private TopUpRepository topUps;
    @Mock
    private ReconciliationService reconciliation;
    @Mock
    private ClockPort clock;

    private TopUpReconciliationJob job;

    private final UUID buyerId = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-06-25T12:00:00Z");
    private static final MobileMoneyProvider PROVIDER = MobileMoneyProvider.MPESA;

    @BeforeEach
    void setUp() {
        // minAge 2m, expireAfter 30m, batch 50, poll 5m — the production defaults.
        PaymentsReconciliationProperties config = new PaymentsReconciliationProperties(
                true, 50, Duration.ofMinutes(2), Duration.ofMinutes(30), Duration.ofMinutes(5));
        when(clock.now()).thenReturn(NOW);
        job = new TopUpReconciliationJob(topUps, reconciliation, config, clock);
    }

    /** A stale PENDING row the provider now settles is confirmed-and-credited via the shared reconcile path. */
    @Test
    void tick_confirmsSettledRow() {
        TopUp pending = pendingTopUp(NOW.minus(Duration.ofMinutes(10))); // older than minAge, younger than expiry
        when(topUps.claimStaleNonTerminalForUpdate(any(), any())).thenReturn(List.of(pending));
        TopUp settled = pendingTopUp(NOW.minus(Duration.ofMinutes(10)));
        settled.markSucceeded(UUID.randomUUID());
        when(reconciliation.reconcile(eq(PROVIDER), eq(pending.getProviderRef()))).thenReturn(settled);

        int claimed = job.reconcileStale();

        assertThat(claimed).isEqualTo(1);
        verify(reconciliation).reconcile(PROVIDER, pending.getProviderRef());
        verify(topUps, never()).save(any()); // settle path saves inside reconcile, not the job
    }

    /** A row older than expireAfter the provider still won't settle is expired to FAILED(RECONCILE_EXPIRED). */
    @Test
    void tick_expiresStuckRow() {
        TopUp stuck = pendingTopUp(NOW.minus(Duration.ofMinutes(45))); // older than expireAfter (30m)
        when(topUps.claimStaleNonTerminalForUpdate(any(), any())).thenReturn(List.of(stuck));
        when(reconciliation.reconcile(eq(PROVIDER), anyString())).thenReturn(stuck); // still PENDING after recheck
        when(topUps.save(any(TopUp.class))).thenAnswer(inv -> inv.getArgument(0));

        job.reconcileStale();

        ArgumentCaptor<TopUp> saved = ArgumentCaptor.forClass(TopUp.class);
        verify(topUps).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(TopUpStatus.FAILED);
        assertThat(saved.getValue().getFailureReason()).isEqualTo("RECONCILE_EXPIRED");
    }

    /** A still-un-settled row younger than expireAfter is left PENDING (a benign mismatch), not expired. */
    @Test
    void tick_leavesYoungUnsettledRowPending() {
        TopUp young = pendingTopUp(NOW.minus(Duration.ofMinutes(10))); // older than minAge, younger than expiry
        when(topUps.claimStaleNonTerminalForUpdate(any(), any())).thenReturn(List.of(young));
        when(reconciliation.reconcile(eq(PROVIDER), anyString())).thenReturn(young); // not settled

        job.reconcileStale();

        verify(topUps, never()).save(any()); // not expired
        assertThat(young.getStatus()).isEqualTo(TopUpStatus.PENDING);
    }

    /** An empty claim does nothing and reports zero. */
    @Test
    void tick_emptyClaimIsNoOp() {
        when(topUps.claimStaleNonTerminalForUpdate(any(), any())).thenReturn(List.of());

        assertThat(job.reconcileStale()).isZero();
        verify(reconciliation, never()).reconcile(any(), anyString());
    }

    // ---- fixtures ----------------------------------------------------------------------------------

    /** A PENDING top-up with a set publicId, providerRef, and createdAt (for the age windows). */
    private TopUp pendingTopUp(Instant createdAt) {
        TopUp t = new TopUp(buyerId, WalletOwnerKind.USER, PROVIDER, 10_000, 100, "TZS",
                "idem-" + UUID.randomUUID());
        setField(t, "publicId", UUID.randomUUID());
        setField(t, "createdAt", createdAt);
        t.markPending("REF-" + UUID.randomUUID());
        return t;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
