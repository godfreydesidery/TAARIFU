package com.taarifu.privacy.application.service;

import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.common.outbox.OutboxWriter;
import com.taarifu.identity.api.UserAdminQueryApi;
import com.taarifu.identity.api.dto.UserAdminDetail;
import com.taarifu.identity.api.dto.UserRoleGrant;
import com.taarifu.privacy.api.dto.DsrDto;
import com.taarifu.privacy.api.event.ErasureRequested;
import com.taarifu.privacy.domain.model.DataSubjectRequest;
import com.taarifu.privacy.domain.model.enums.DsrType;
import com.taarifu.privacy.domain.repository.DataSubjectRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DataSubjectRequestService} — the DSR intake, the atomic erasure publish, and the
 * active-role erasure fence (PDPA §25.1, note ᵇ; ADR-0016 §3/§5/§6).
 *
 * <p>Proves: (a) erasure opens a tracked request AND publishes {@code ERASURE_REQUESTED} to the outbox with
 * an <b>ids-only</b> payload (no PII) — the atomicity guarantee; (b) the active-role holder is blocked from
 * self-erasure ({@code CONFLICT}) and NO outbox event is published (accountability preserved); (c) erasure is
 * idempotent (an already-open request is returned, no second fan-out); (d) the request is audited. No Docker.</p>
 */
@ExtendWith(MockitoExtension.class)
class DataSubjectRequestServiceTest {

    @Mock
    private DataSubjectRequestRepository requestRepository;
    @Mock
    private OutboxWriter outboxWriter;
    @Mock
    private UserAdminQueryApi userQuery;
    @Mock
    private AuditEventService audit;

    private final UUID subject = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-06-25T10:00:00Z");
    private final ClockPort clock = () -> now;

    private DataSubjectRequestService service() {
        return new DataSubjectRequestService(requestRepository, outboxWriter, userQuery, audit, clock);
    }

    /**
     * Mirrors what JPA's {@code persist()} does on {@code save()} — assigns the {@code publicId} (the
     * {@code @PrePersist} on {@code BaseEntity}) so the saved entity carries one before flush, exactly as in
     * production. Reflection here is a test-only seam (the field has no setter by design).
     */
    private static DataSubjectRequest assignPublicId(DataSubjectRequest r) {
        try {
            java.lang.reflect.Field f = com.taarifu.common.domain.model.BaseEntity.class
                    .getDeclaredField("publicId");
            f.setAccessible(true);
            if (f.get(r) == null) {
                f.set(r, UUID.randomUUID());
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return r;
    }

    /** A citizen with no active staff/rep role. */
    private void citizenOnly() {
        when(userQuery.getUser(subject)).thenReturn(detailWithRoles(
                new UserRoleGrant(UUID.randomUUID(), "CITIZEN", "ACTIVE",
                        List.of(), List.of(), null, null, null)));
    }

    private UserAdminDetail detailWithRoles(UserRoleGrant... grants) {
        return new UserAdminDetail(subject, "Asha", "+2557****1234", null, "T2", "ACTIVE",
                false, List.of(grants), 1L, now, null);
    }

    @Test
    void requestErasure_citizen_opensRequest_andPublishesIdsOnlyEvent_atomically() {
        citizenOnly();
        when(requestRepository.findBySubjectPublicIdAndTypeAndStatusIn(eq(subject), eq(DsrType.ERASURE), anyList()))
                .thenReturn(List.of());
        // Simulate Spring Data save(): persist() runs @PrePersist synchronously, assigning the publicId — so
        // request.getPublicId() is non-null when the service builds the outbox event (as in production).
        when(requestRepository.save(any(DataSubjectRequest.class)))
                .thenAnswer(inv -> assignPublicId(inv.getArgument(0)));

        DsrDto dto = service().requestErasure(subject);

        assertThat(dto.type()).isEqualTo("ERASURE");

        // The ERASURE_REQUESTED event is published with an IDS-ONLY payload (no PII) — the atomicity contract.
        ArgumentCaptor<EventEnvelope<?>> ev = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(outboxWriter).append(ev.capture());
        EventEnvelope<?> envelope = ev.getValue();
        assertThat(envelope.eventType()).isEqualTo(ErasureRequested.EVENT_TYPE);
        assertThat(envelope.aggregateType()).isEqualTo(ErasureRequested.AGGREGATE_TYPE);
        assertThat(envelope.payload()).isInstanceOf(ErasureRequested.class);
        ErasureRequested payload = (ErasureRequested) envelope.payload();
        assertThat(payload.subjectPublicId()).isEqualTo(subject);
        assertThat(payload.dsrPublicId()).isNotNull();

        // Audited as an erasure-request.
        ArgumentCaptor<AuditEvent> audited = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(audited.capture());
        assertThat(audited.getValue().getEventType()).isEqualTo(AuditEventType.PRIVACY_ERASURE_REQUESTED);
    }

    @Test
    void requestErasure_activeStaffRole_isBlocked_noEventPublished() {
        // An ACTIVE MODERATOR grant blocks self-erasure (note ᵇ — accountability trail preserved).
        when(userQuery.getUser(subject)).thenReturn(detailWithRoles(
                new UserRoleGrant(UUID.randomUUID(), "MODERATOR", "ACTIVE",
                        List.of(), List.of(), null, null, null)));

        assertThatThrownBy(() -> service().requestErasure(subject))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(outboxWriter, never()).append(any());
        verify(requestRepository, never()).save(any());
    }

    @Test
    void requestErasure_idempotent_returnsExistingOpenRequest_noSecondFanout() {
        citizenOnly();
        DataSubjectRequest existing = DataSubjectRequest.open(subject, DsrType.ERASURE, now,
                java.time.Duration.ofDays(30));
        when(requestRepository.findBySubjectPublicIdAndTypeAndStatusIn(eq(subject), eq(DsrType.ERASURE), anyList()))
                .thenReturn(List.of(existing));

        DsrDto dto = service().requestErasure(subject);

        assertThat(dto.type()).isEqualTo("ERASURE");
        // No new request, no second fan-out event.
        verify(requestRepository, never()).save(any());
        verify(outboxWriter, never()).append(any());
    }

    @Test
    void requestAccess_opensRequest_andAudits_noOutboxEvent() {
        when(requestRepository.findBySubjectPublicIdAndTypeAndStatusIn(eq(subject), eq(DsrType.ACCESS), anyList()))
                .thenReturn(List.of());
        when(requestRepository.save(any(DataSubjectRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        DsrDto dto = service().requestAccess(subject);

        assertThat(dto.type()).isEqualTo("ACCESS");
        // Access is synchronous export — no erasure fan-out event.
        verify(outboxWriter, never()).append(any());
        ArgumentCaptor<AuditEvent> audited = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(audited.capture());
        assertThat(audited.getValue().getEventType()).isEqualTo(AuditEventType.PRIVACY_DSR_RECEIVED);
    }
}
