package com.taarifu.admin.application.service;

import com.taarifu.admin.api.dto.FailedEventDto;
import com.taarifu.admin.api.dto.ReplayOutboxRequest;
import com.taarifu.admin.api.dto.ReplayOutboxResultDto;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.outbox.OutboxReplayService;
import com.taarifu.common.security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the admin {@link OutboxAdminService} — the DLQ list/replay workflow and its audit trail
 * (M14, P3-1; ADR-0014).
 *
 * <p>Responsibility: proves (a) the DLQ list maps the kernel projection 1:1 to the PII-free admin DTO and is
 * <b>not</b> audited (a read); (b) a by-id replay delegates to {@link OutboxReplayService#replayById(UUID)},
 * returns mode {@code BY_ID} + the kernel count, and audits with the acting admin <b>from the security
 * context</b> and the event id as the audit subject; (c) a window replay delegates to
 * {@link OutboxReplayService#replayBatch} with the mapped filter (blank eventType → {@code null}), returns
 * mode {@code BY_WINDOW}, and audits with the scope in the reason and a {@code null} subject; and (d) a
 * {@code requeued=0} idempotent outcome is still audited. Each replay test fails if the audit call were
 * removed (CLAUDE.md §10/§12 — admin actions are audited). No Docker.</p>
 */
@ExtendWith(MockitoExtension.class)
class OutboxAdminServiceTest {

    @Mock
    private OutboxReplayService replayService;
    @Mock
    private AuditEventService audit;

    private final UUID admin = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Authenticates the acting admin in the security context (the controller's @PreAuthorize already passed). */
    private void authenticateAdmin() {
        CurrentUser principal = new CurrentUser(admin, List.of("ADMIN"), "T3");
        var auth = new UsernamePasswordAuthenticationToken(admin, null, List.of());
        auth.setDetails(principal);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private OutboxAdminService service() {
        return new OutboxAdminService(replayService, audit);
    }

    @Test
    void listFailed_mapsProjectionToDto_andIsNotAudited() {
        UUID eventId = UUID.randomUUID();
        Instant failedAt = Instant.parse("2026-06-24T10:00:00Z");
        OutboxReplayService.FailedOutboxView view =
                new OutboxReplayService.FailedOutboxView(eventId, "REPORT_ROUTED", 5, failedAt, 3600L);
        Pageable pageable = PageRequest.of(0, 20);
        when(replayService.listFailed(pageable))
                .thenReturn(new PageImpl<>(List.of(view), pageable, 1L));

        Page<FailedEventDto> result = service().listFailed(pageable);

        assertThat(result.getTotalElements()).isEqualTo(1L);
        FailedEventDto dto = result.getContent().get(0);
        assertThat(dto.eventId()).isEqualTo(eventId);
        assertThat(dto.eventType()).isEqualTo("REPORT_ROUTED");
        assertThat(dto.attempts()).isEqualTo(5);
        assertThat(dto.failedAt()).isEqualTo(failedAt);
        assertThat(dto.ageSeconds()).isEqualTo(3600L);
        // A read never writes an audit row.
        verify(audit, never()).record(any(AuditEvent.class));
    }

    @Test
    void replayById_delegates_returnsByIdMode_andAuditsWithActingAdmin_subjectEventId() {
        authenticateAdmin();
        UUID eventId = UUID.randomUUID();
        when(replayService.replayById(eventId)).thenReturn(1);
        ReplayOutboxRequest request = new ReplayOutboxRequest(eventId, null, null, null, null);

        ReplayOutboxResultDto result = service().replay(request);

        assertThat(result.mode()).isEqualTo("BY_ID");
        assertThat(result.requeued()).isEqualTo(1);
        verify(replayService).replayById(eventId);
        verify(replayService, never()).replayBatch(any());

        AuditEvent recorded = captureAudit();
        assertThat(recorded.getEventType()).isEqualTo(AuditEventType.OUTBOX_DLQ_REPLAYED);
        assertThat(recorded.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        // Acting admin comes from the security context, never the body.
        assertThat(recorded.getActorPublicId()).isEqualTo(admin);
        assertThat(recorded.getSubjectPublicId()).isEqualTo(eventId);
        assertThat(recorded.getReasonCode()).isEqualTo("BY_ID:" + eventId + ":n=1");
    }

    @Test
    void replayWindow_mapsFilter_blankEventTypeToNull_returnsByWindowMode_subjectNull() {
        authenticateAdmin();
        // Blank eventType must collapse to null (a blank filter = no filter), and the window is bounded.
        ReplayOutboxRequest request = new ReplayOutboxRequest(
                null, "   ", Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-24T00:00:00Z"), 50);
        ArgumentCaptor<OutboxReplayService.ReplayFilter> filterCaptor =
                ArgumentCaptor.forClass(OutboxReplayService.ReplayFilter.class);
        when(replayService.replayBatch(filterCaptor.capture())).thenReturn(12);

        ReplayOutboxResultDto result = service().replay(request);

        assertThat(result.mode()).isEqualTo("BY_WINDOW");
        assertThat(result.requeued()).isEqualTo(12);
        verify(replayService, never()).replayById(any());

        OutboxReplayService.ReplayFilter sent = filterCaptor.getValue();
        assertThat(sent.eventType()).isNull();
        assertThat(sent.processedFrom()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(sent.processedTo()).isEqualTo(Instant.parse("2026-06-24T00:00:00Z"));
        assertThat(sent.limit()).isEqualTo(50);

        AuditEvent recorded = captureAudit();
        // Whole-DLQ window (no eventType) → ALL scope; a window replay targets a set, so no subject.
        assertThat(recorded.getActorPublicId()).isEqualTo(admin);
        assertThat(recorded.getSubjectPublicId()).isNull();
        assertThat(recorded.getReasonCode()).isEqualTo("BY_WINDOW:ALL:n=12");
    }

    @Test
    void replayWindow_withEventType_putsScopeInReason() {
        authenticateAdmin();
        ReplayOutboxRequest request =
                new ReplayOutboxRequest(null, "REPORT_ROUTED", null, null, null);
        when(replayService.replayBatch(any())).thenReturn(3);

        ReplayOutboxResultDto result = service().replay(request);

        assertThat(result.mode()).isEqualTo("BY_WINDOW");
        AuditEvent recorded = captureAudit();
        assertThat(recorded.getReasonCode()).isEqualTo("BY_WINDOW:REPORT_ROUTED:n=3");
    }

    @Test
    void replayById_zeroRequeued_isStillAudited_idempotentOutcome() {
        authenticateAdmin();
        UUID eventId = UUID.randomUUID();
        when(replayService.replayById(eventId)).thenReturn(0);

        ReplayOutboxResultDto result =
                service().replay(new ReplayOutboxRequest(eventId, null, null, null, null));

        assertThat(result.requeued()).isZero();
        AuditEvent recorded = captureAudit();
        assertThat(recorded.getReasonCode()).isEqualTo("BY_ID:" + eventId + ":n=0");
    }

    /** Captures the single audit row written by a replay. */
    private AuditEvent captureAudit() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(captor.capture());
        return captor.getValue();
    }
}
