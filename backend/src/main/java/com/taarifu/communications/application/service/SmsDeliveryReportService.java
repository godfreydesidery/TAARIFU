package com.taarifu.communications.application.service;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.communications.domain.model.Notification;
import com.taarifu.communications.domain.model.enums.NotificationStatus;
import com.taarifu.communications.domain.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Applies an inbound SMS <b>delivery-report (DLR)</b> to the matching {@link Notification} dispatch row —
 * idempotent, replay-safe, and PII-free (PRD §13 "logged with delivery status", EI-3 DLR webhook, DI4, M5).
 *
 * <p>Responsibility: take an authenticated, parsed delivery report — a {@code (reference, delivered)} pair,
 * where {@code reference} is the aggregator's echo of the value the outbound {@code HttpSmsGateway} sent as
 * {@code reference} (= the dispatch row's {@code idempotencyKey}) — correlate it to the {@code Notification}
 * by that key, and advance the row's lifecycle: {@code → DELIVERED} on a delivered report,
 * {@code → FAILED} on a terminal-failure report. This closes the {@code SENT → DELIVERED} gap the outbound
 * adapter deliberately deferred ("the DLR arrives asynchronously on a separate webhook").</p>
 *
 * <p><b>WHY correlate on the idempotency key (not a provider message id)</b>: the outbound
 * {@code HttpSmsGateway} sends our {@code idempotencyKey} as the aggregator {@code reference}, and the
 * aggregator echoes it on the DLR. That key is the {@code Notification.idempotencyKey} (unique), so a single
 * {@link NotificationRepository#findByIdempotencyKey} resolves the exact row with no extra correlation
 * column or migration. The aggregator's own message id is opaque and provider-specific; the {@code reference}
 * round-trip is the portable, vendor-neutral correlator (DI1).</p>
 *
 * <p><b>Idempotency &amp; out-of-order delivery (DI4, EI-3).</b> DLRs arrive at-least-once, can be replayed,
 * and can arrive out of order (a late {@code DELIVERED} after we already gave up, or a duplicate). This
 * handler is replay-safe by construction:</p>
 * <ul>
 *   <li>an <b>unknown reference</b> (no matching row — a stale/foreign callback) is a benign no-op;</li>
 *   <li>a row already in a <b>terminal positive</b> state ({@code DELIVERED}/{@code READ}) is never regressed
 *       — a duplicate {@code DELIVERED} or a late {@code FAILED} after the recipient already read it does
 *       <b>not</b> overwrite the better outcome;</li>
 *   <li>a {@code FAILED} row that later receives a genuine {@code DELIVERED} <b>is</b> promoted (a real
 *       late-success signal), since {@code DELIVERED} strictly dominates {@code FAILED}.</li>
 * </ul>
 * The transition methods are the domain's; this service only decides whether the reported outcome is an
 * advance worth applying.
 *
 * <p><b>🔒 Privacy (PRD §18, S-4).</b> A DLR is correlated purely by the opaque, non-PII {@code reference} —
 * no MSISDN is read here. The service logs the (non-PII) reference and the applied outcome only; never a
 * phone, body, or provider error text. The webhook authentication (shared secret) lives in the controller.</p>
 */
@Service
public class SmsDeliveryReportService {

    private static final Logger log = LoggerFactory.getLogger(SmsDeliveryReportService.class);

    private final NotificationRepository notificationRepository;
    private final ClockPort clock;

    /**
     * @param notificationRepository this module's dispatch-row store (the idempotency-key correlation).
     * @param clock                  injectable "now" for the delivery timestamp (testability).
     */
    public SmsDeliveryReportService(NotificationRepository notificationRepository, ClockPort clock) {
        this.notificationRepository = notificationRepository;
        this.clock = clock;
    }

    /**
     * Applies one delivery report to the correlated notification row.
     *
     * @param reference the aggregator's echo of our {@code reference} (= the dispatch row's idempotency
     *                  key); the sole correlator. A {@code null}/blank or unmatched value is a benign no-op.
     * @param delivered {@code true} for a positive delivery report (→ {@code DELIVERED}); {@code false} for a
     *                  terminal failure report (→ {@code FAILED}, unless the row already reached a better,
     *                  non-regressable state).
     * @param reason    a short, non-PII provider reason code for a failure report (e.g. {@code EXPIRED},
     *                  {@code REJECTED}), or {@code null}; truncated to the row's reason column width.
     * @return {@code true} iff a row was found and its state advanced (false for an unknown reference or a
     *         no-op duplicate/regression) — useful to tests/metrics; the webhook response never reveals it.
     */
    @Transactional
    public boolean apply(String reference, boolean delivered, String reason) {
        if (reference == null || reference.isBlank()) {
            // No correlator → nothing to do. A DLR with no reference is unmatchable (benign, EI-3).
            return false;
        }
        Optional<Notification> match = notificationRepository.findByIdempotencyKey(reference.strip());
        if (match.isEmpty()) {
            // Unknown/stale reference (a foreign or replayed-after-prune callback) → benign no-op (DI4).
            log.info("SMS DLR ignored (no matching dispatch row): reference={}, delivered={}",
                    reference, delivered);
            return false;
        }
        Notification n = match.get();
        boolean advanced = advance(n, delivered, reason);
        log.info("SMS DLR applied: reference={}, delivered={}, advanced={}, status={}",
                reference, delivered, advanced, n.getStatus());
        return advanced;
    }

    /**
     * Decides whether the reported outcome is a non-regressing advance and applies the transition.
     *
     * <p>Monotonicity rule (out-of-order/replay safety): {@code READ} and {@code DELIVERED} are positive
     * terminal/near-terminal states that a later report must not overwrite — a duplicate {@code DELIVERED}
     * or a late {@code FAILED} after a {@code DELIVERED}/{@code READ} is a no-op. A {@code DELIVERED} report
     * promotes any non-positive state (including a prior {@code FAILED} — a genuine late success). A
     * {@code FAILED} report is applied only to a not-yet-positive, not-already-{@code FAILED} row.</p>
     *
     * @param n         the correlated row.
     * @param delivered the reported outcome.
     * @param reason    the non-PII failure reason (used only on the failure branch).
     * @return {@code true} iff a transition was applied.
     */
    private boolean advance(Notification n, boolean delivered, String reason) {
        NotificationStatus current = n.getStatus();
        if (delivered) {
            // DELIVERED dominates everything except READ (the recipient already opened it) and an existing
            // DELIVERED (idempotent duplicate). Promote QUEUED/SENT/FAILED → DELIVERED.
            if (current == NotificationStatus.READ || current == NotificationStatus.DELIVERED) {
                return false;
            }
            n.markDelivered(clock.now());
            return true;
        }
        // A failure report: never regress a positive outcome, never re-fail an already-FAILED row.
        if (current == NotificationStatus.DELIVERED || current == NotificationStatus.READ
                || current == NotificationStatus.FAILED) {
            return false;
        }
        n.markFailed(failureReason(reason));
        return true;
    }

    /**
     * Normalises a provider failure reason to a short, non-PII code for the row's {@code failure_reason}
     * column (length-bounded). A {@code null}/blank reason becomes the generic {@code DLR_FAILED}.
     *
     * @param reason the raw provider reason, or {@code null}.
     * @return a non-PII reason code, clipped to 128 chars (the column width).
     */
    private static String failureReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "DLR_FAILED";
        }
        String stripped = reason.strip();
        return stripped.length() > 128 ? stripped.substring(0, 128) : stripped;
    }
}
