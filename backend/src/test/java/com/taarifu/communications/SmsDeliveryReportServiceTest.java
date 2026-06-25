package com.taarifu.communications;

import com.taarifu.communications.application.service.SmsDeliveryReportService;
import com.taarifu.communications.domain.model.Notification;
import com.taarifu.communications.domain.model.enums.Channel;
import com.taarifu.communications.domain.model.enums.NotificationStatus;
import com.taarifu.communications.domain.model.enums.NotificationType;
import com.taarifu.communications.domain.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SmsDeliveryReportService} — proves an inbound DLR is correlated by the dispatch
 * idempotency key and advances the notification's delivery state idempotently and non-regressively, with no
 * DB (Mockito only; CLAUDE.md §10, DI4).
 *
 * <p>Responsibility: pins the replay-safety contract the spec demands of an at-least-once, out-of-order DLR
 * stream — a delivered report promotes {@code SENT → DELIVERED}; a failure report marks {@code SENT → FAILED};
 * an unknown reference is a benign no-op; a duplicate {@code DELIVERED} does not re-apply; a {@code READ} or
 * already-{@code DELIVERED} row is never regressed by a late {@code FAILED}; and a {@code DELIVERED} promotes
 * a prior {@code FAILED} (a genuine late success). These are the exact edge cases an aggregator's DLR webhook
 * produces (EI-3).</p>
 */
class SmsDeliveryReportServiceTest {

    private static final String REF = "NOTIFICATION:SMS:" + "11111111-1111-1111-1111-111111111111" + ":src";

    private NotificationRepository notificationRepository;
    private FixedClock clock;
    private SmsDeliveryReportService service;

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        clock = new FixedClock(Instant.parse("2026-06-25T10:00:00Z"));
        service = new SmsDeliveryReportService(notificationRepository, clock);
    }

    /** Builds an SMS notification row at the given starting status (via the domain transitions). */
    private Notification smsRowAt(NotificationStatus status) {
        Notification n = Notification.queue(UUID.randomUUID(), NotificationType.NEW_ANNOUNCEMENT,
                Channel.SMS, null, REF);
        switch (status) {
            case QUEUED -> { /* already queued */ }
            case SENT -> n.markSent(clock.now());
            case DELIVERED -> n.markDelivered(clock.now());
            case READ -> n.markRead(clock.now());
            case FAILED -> n.markFailed("PRIOR");
        }
        return n;
    }

    private void givenRow(Notification n) {
        when(notificationRepository.findByIdempotencyKey(REF)).thenReturn(Optional.of(n));
    }

    @Test
    void deliveredReport_promotesSentToDelivered() {
        Notification n = smsRowAt(NotificationStatus.SENT);
        givenRow(n);

        boolean advanced = service.apply(REF, true, null);

        assertThat(advanced).isTrue();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.DELIVERED);
        assertThat(n.getDeliveredAt()).isEqualTo(clock.now());
    }

    @Test
    void failureReport_marksSentAsFailed_withNonPiiReason() {
        Notification n = smsRowAt(NotificationStatus.SENT);
        givenRow(n);

        boolean advanced = service.apply(REF, false, "EXPIRED");

        assertThat(advanced).isTrue();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(n.getFailureReason()).isEqualTo("EXPIRED");
    }

    @Test
    void unknownReference_isABenignNoOp() {
        when(notificationRepository.findByIdempotencyKey(REF)).thenReturn(Optional.empty());

        boolean advanced = service.apply(REF, true, null);

        assertThat(advanced).isFalse();
    }

    @Test
    void blankReference_isABenignNoOp() {
        assertThat(service.apply("  ", true, null)).isFalse();
        assertThat(service.apply(null, false, "X")).isFalse();
    }

    @Test
    void duplicateDeliveredReport_doesNotReApply() {
        Notification n = smsRowAt(NotificationStatus.DELIVERED);
        givenRow(n);

        boolean advanced = service.apply(REF, true, null);

        assertThat(advanced).isFalse();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.DELIVERED);
    }

    @Test
    void lateFailure_neverRegressesADeliveredRow() {
        Notification n = smsRowAt(NotificationStatus.DELIVERED);
        givenRow(n);

        boolean advanced = service.apply(REF, false, "EXPIRED");

        assertThat(advanced).isFalse();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.DELIVERED);
    }

    @Test
    void lateFailure_neverRegressesAReadRow() {
        Notification n = smsRowAt(NotificationStatus.READ);
        givenRow(n);

        boolean advanced = service.apply(REF, false, "EXPIRED");

        assertThat(advanced).isFalse();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.READ);
    }

    @Test
    void lateDelivered_promotesAPriorFailedRow() {
        Notification n = smsRowAt(NotificationStatus.FAILED);
        givenRow(n);

        boolean advanced = service.apply(REF, true, null);

        // DELIVERED strictly dominates FAILED — a genuine late-success report is applied.
        assertThat(advanced).isTrue();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.DELIVERED);
    }

    @Test
    void duplicateFailure_doesNotReApply() {
        Notification n = smsRowAt(NotificationStatus.FAILED);
        givenRow(n);

        boolean advanced = service.apply(REF, false, "AGAIN");

        assertThat(advanced).isFalse();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void failureReport_withNoReason_usesGenericCode() {
        Notification n = smsRowAt(NotificationStatus.SENT);
        givenRow(n);

        service.apply(REF, false, null);

        assertThat(n.getFailureReason()).isEqualTo("DLR_FAILED");
    }
}
