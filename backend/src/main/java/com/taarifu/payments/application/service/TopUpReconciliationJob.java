package com.taarifu.payments.application.service;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.payments.domain.model.TopUp;
import com.taarifu.payments.domain.port.MobileMoneyGateway;
import com.taarifu.payments.domain.repository.TopUpRepository;
import com.taarifu.payments.infrastructure.config.PaymentsReconciliationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The {@code @Scheduled} <b>settlement reconciliation job</b>: a safety net for the asynchronous, lossy
 * Tanzanian mobile-money rails (ADR-0015 addendum; PRD §23.5, §21 EI-20).
 *
 * <p>Responsibility: webhooks get lost, duplicated, and arrive out of order. The webhook path
 * ({@link ReconciliationService}) is the primary settlement route, but a top-up can sit PENDING forever if
 * its confirming callback never arrives. This job periodically (a) <b>re-checks</b> stale non-terminal
 * top-ups against the provider out-of-band ({@link MobileMoneyGateway#verifySettled}) and confirms-and-credits
 * the ones that settled (reusing the exact idempotent, fence-safe {@link ReconciliationService#reconcile}
 * path — never trust-the-callback), and (b) <b>expires</b> rows that have been stuck past
 * {@code expireAfter} to FAILED so they stop being re-checked and the citizen's UI can settle on a final
 * state. It also surfaces a <b>mismatch</b> count (rows still un-settled after the re-check) for operability.</p>
 *
 * <p><b>Idempotent + short-lock (PRD §23.5; ARCHITECTURE §10):</b> each tick claims a small, bounded batch of
 * stale rows under {@code FOR UPDATE} (the repository's {@code claimStaleNonTerminalForUpdate}) so two
 * instances never re-check the same row, the lock is held only for the short job transaction, and every row
 * the tick touches is driven toward a terminal state — so it falls out of the next claim. Re-confirming a row
 * that the webhook already settled is a no-op (the {@link TopUp#isTerminal()} guard inside
 * {@link ReconciliationService#reconcile}); the credit's stable per-top-up idempotency key means a settled
 * row is never double-credited even if a tick and a webhook race.</p>
 *
 * <p><b>🔒 Fence (D18):</b> this job calls only {@link ReconciliationService#reconcile} (a convenience-wallet
 * top-up) and {@link TopUp#markFailed}. It has no dependency on any binding-action module and never reads a
 * token balance — the same fence the rest of payments preserves by construction.</p>
 *
 * <p><b>Operability toggle:</b> gated by {@code taarifu.payments.reconciliation.enabled} (default
 * {@code true}), mirroring the outbox relay's enabled-toggle so it can be pinned to a subset of instances,
 * paused for a maintenance window, or turned off in a test that manipulates {@code top_up} rows directly.
 * Disabling removes only the poller — the webhook-driven settlement path is unaffected.</p>
 *
 * <p><b>Privacy (PRD §18):</b> the job logs counts + the rail only — never a buyer id, MSISDN, reference, or
 * any provider body.</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.payments.reconciliation.enabled", havingValue = "true",
        matchIfMissing = true)
public class TopUpReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(TopUpReconciliationJob.class);

    /** Redacted machine reason recorded on a top-up the job expires (never PII). */
    static final String EXPIRED_REASON = "RECONCILE_EXPIRED";

    private final TopUpRepository topUps;
    private final ReconciliationService reconciliation;
    private final PaymentsReconciliationProperties config;
    private final ClockPort clock;

    /**
     * @param topUps         top-up persistence (the locked stale-row claim).
     * @param reconciliation the idempotent, provider-verified settlement → credit path (reused verbatim).
     * @param config         the job tunables (batch size, min-age, expiry window).
     * @param clock          the clock port so the tick instant + age windows are testable.
     */
    public TopUpReconciliationJob(TopUpRepository topUps, ReconciliationService reconciliation,
                                  PaymentsReconciliationProperties config, ClockPort clock) {
        this.topUps = topUps;
        this.reconciliation = reconciliation;
        this.config = config;
        this.clock = clock;
    }

    /**
     * One reconciliation tick: claim a bounded batch of stale non-terminal top-ups and, per row, either
     * confirm-and-credit (if the provider now reports settlement) or expire it (if it is older than the
     * expiry window). Driven by {@code @Scheduled(fixedDelay)} so ticks never overlap on a slow batch.
     *
     * <p>The interval is read from {@code taarifu.payments.reconciliation.poll-interval-ms} (default 5min) on
     * the annotation, because annotation attributes must be constant expressions and cannot read a bound
     * bean (the same constraint the outbox relay works under).</p>
     *
     * @return the number of rows this tick claimed (0 when none are due) — useful for tests/metrics.
     */
    @Scheduled(fixedDelayString = "${taarifu.payments.reconciliation.poll-interval-ms:300000}")
    @Transactional
    public int reconcileStale() {
        Instant now = clock.now();
        Instant recheckCutoff = now.minus(config.minAge());
        Instant expiryCutoff = now.minus(config.expireAfter());

        List<TopUp> batch = topUps.claimStaleNonTerminalForUpdate(
                recheckCutoff, PageRequest.ofSize(config.batchSize()));
        if (batch.isEmpty()) {
            return 0;
        }

        int settled = 0;
        int expired = 0;
        int mismatched = 0;
        for (TopUp row : batch) {
            if (tryConfirm(row)) {
                settled++;
            } else if (isExpirable(row, expiryCutoff)) {
                row.markFailed(EXPIRED_REASON);
                topUps.save(row);
                expired++;
            } else {
                // Still un-settled and not yet old enough to expire — a benign mismatch this tick.
                mismatched++;
            }
        }

        log.info("Reconciliation tick: claimed={}, settled={}, expired={}, stillPending(mismatch)={}",
                batch.size(), settled, expired, mismatched);
        return batch.size();
    }

    /**
     * Re-confirms one stale row against the provider via the shared {@link ReconciliationService}. Returns
     * whether the row reached SUCCEEDED on this tick.
     *
     * <p>A row without a {@code providerRef} (initiation never accepted by the rail) cannot be reconciled by
     * reference and is left for the expiry path. Reusing {@code reconcile} keeps the credit, idempotency, and
     * fence guarantees identical to the webhook path — there is exactly one settlement code path.</p>
     *
     * @param row the claimed stale top-up.
     * @return {@code true} if the provider confirmed settlement and the wallet was credited this tick.
     */
    private boolean tryConfirm(TopUp row) {
        String providerRef = row.getProviderRef();
        if (providerRef == null || providerRef.isBlank()) {
            return false;
        }
        TopUp result = reconciliation.reconcile(row.getProvider(), providerRef);
        return result != null && result.isTerminal() && result.getCreditEventId() != null;
    }

    /**
     * @param row          the claimed stale top-up.
     * @param expiryCutoff rows created at or before this instant are old enough to expire.
     * @return {@code true} if the (still non-terminal) row is older than the expiry window.
     */
    private boolean isExpirable(TopUp row, Instant expiryCutoff) {
        Instant createdAt = row.getCreatedAt();
        return !row.isTerminal() && createdAt != null && !createdAt.isAfter(expiryCutoff);
    }
}
