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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * The admin console's <b>dead-letter-queue (DLQ) operations</b> workflow (M14; ADR-0014 revisit trigger (c);
 * outbox review P3-1/P4-4).
 *
 * <p>Responsibility: own the operator-side rules for inspecting and draining the transactional-outbox DLQ —
 * list the terminally FAILED events (PII-free) and re-queue them to PENDING by a single id or a bounded
 * window — then delegate the actual read/state-change to the shared-kernel {@link OutboxReplayService}
 * (which pins {@code WHERE status='FAILED'} so a replay can never re-fire an already-delivered effect, and is
 * idempotent). The admin module thus consumes the outbox <b>only through that published service seam</b>,
 * never reaching into the {@code common.outbox} repository or any {@code OutboxEvent} entity (ARCHITECTURE
 * §3.2 — the shared kernel is the dependency-free base every module may use). No {@code @Transactional} here:
 * the replay service owns its own transaction over the outbox table; this service orchestrates and audits.</p>
 *
 * <p><b>Audit (P3-1; CLAUDE.md §12 — admin actions are audited):</b> every replay is recorded to the
 * append-only audit store with the acting admin (from the security context, <b>never</b> a body field), the
 * count actually re-queued, and a machine reason that names the mode and reference (the event id or the
 * {@code eventType} window) — references/counts only, <b>never</b> PII or an event payload (PRD §18, L-1). A
 * by-id replay records the target event id as the audit {@code subject}; a window replay records the
 * {@code eventType} (or {@code ALL}) in the reason code. The DLQ <b>list</b> is a read and is not audited
 * (it changes nothing), consistent with the other admin read surfaces.</p>
 *
 * <p>WHY the acting-admin id comes from {@link CurrentUser#requirePublicId()} (not a parameter/body): the
 * controller's deny-by-default {@code ADMIN}/{@code ROOT} method security has already authenticated the
 * caller, and the audit trail must attribute the replay to the real operator — a body can never name a
 * different actor (mirrors {@code UserAdminService}).</p>
 */
@Service
public class OutboxAdminService {

    /** Audit reason marker for the whole-DLQ (unscoped) window replay — no {@code eventType} filter set. */
    private static final String WINDOW_ALL = "ALL";

    private final OutboxReplayService replayService;
    private final AuditEventService audit;

    /**
     * @param replayService the shared-kernel DLQ read/replay seam — the only outbox surface this module
     *                      touches (ADR-0014; ARCHITECTURE §3.2).
     * @param audit         append-only audit writer; records every replay (refs + counts only, never PII).
     */
    public OutboxAdminService(OutboxReplayService replayService, AuditEventService audit) {
        this.replayService = replayService;
        this.audit = audit;
    }

    /**
     * Pages the DLQ (terminal FAILED outbox rows) as PII-free {@link FailedEventDto} rows, oldest first.
     *
     * <p>A read — not audited (it changes nothing). Maps the kernel's
     * {@link OutboxReplayService.FailedOutboxView} 1:1 to the admin DTO; the payload and {@code last_error}
     * are never surfaced (the projection already excludes them).</p>
     *
     * @param pageable the bounded page request (the controller caps the size).
     * @return a page of PII-free FAILED-event views.
     */
    public Page<FailedEventDto> listFailed(Pageable pageable) {
        return replayService.listFailed(pageable)
                .map(v -> new FailedEventDto(
                        v.eventId(), v.eventType(), v.attempts(), v.failedAt(), v.ageSeconds()));
    }

    /**
     * Re-queues FAILED outbox rows to PENDING — by a single event id or a bounded window — and audits the
     * action with the acting admin and the count actually moved.
     *
     * <p>Idempotent and bounded by construction (the kernel pins {@code status='FAILED'} and caps the batch):
     * a {@code requeued=0} result is the normal "already replayed / nothing matched" outcome, still audited so
     * the operator action is on record.</p>
     *
     * @param request the replay command (by-id when {@code eventId} is set, else a bounded window).
     * @return the mode that ran and the number of rows actually re-queued.
     */
    public ReplayOutboxResultDto replay(ReplayOutboxRequest request) {
        UUID actingAdmin = CurrentUser.requirePublicId();
        if (request.isById()) {
            return replayById(actingAdmin, request.eventId());
        }
        return replayWindow(actingAdmin, request);
    }

    /** Single-id replay: re-queue exactly one FAILED row and audit it (subject = the event id). */
    private ReplayOutboxResultDto replayById(UUID actingAdmin, UUID eventId) {
        int requeued = replayService.replayById(eventId);
        recordReplay(actingAdmin, eventId, "BY_ID:" + eventId, requeued);
        return new ReplayOutboxResultDto("BY_ID", requeued);
    }

    /** Bounded-window replay: re-queue one capped batch of FAILED rows and audit it (reason = the scope). */
    private ReplayOutboxResultDto replayWindow(UUID actingAdmin, ReplayOutboxRequest request) {
        OutboxReplayService.ReplayFilter filter = new OutboxReplayService.ReplayFilter(
                blankToNull(request.eventType()), request.failedFrom(), request.failedTo(), request.limit());
        int requeued = replayService.replayBatch(filter);
        String scope = filter.eventType() == null ? WINDOW_ALL : filter.eventType();
        // Subject is null for a window replay (it targets a set, not a single addressable event).
        recordReplay(actingAdmin, null, "BY_WINDOW:" + scope, requeued);
        return new ReplayOutboxResultDto("BY_WINDOW", requeued);
    }

    /**
     * Records an outbox-replay audit row (refs + count only, never PII).
     *
     * @param actingAdmin the operator from the security context (the audit {@code actor}).
     * @param subject     the target event public id for a by-id replay, or {@code null} for a window replay.
     * @param reasonCode  a machine reason naming the mode + reference/scope and never PII (e.g.
     *                    {@code BY_ID:<uuid>}, {@code BY_WINDOW:REPORT_ROUTED}, {@code BY_WINDOW:ALL}).
     * @param requeued    the number of rows actually re-queued (appended to the reason for the trail).
     */
    private void recordReplay(UUID actingAdmin, UUID subject, String reasonCode, int requeued) {
        AuditEvent.Builder builder = AuditEvent.Builder.of(
                        AuditEventType.OUTBOX_DLQ_REPLAYED, AuditOutcome.SUCCESS)
                .actor(actingAdmin)
                .reason(reasonCode + ":n=" + requeued);
        if (subject != null) {
            builder.subject(subject);
        }
        audit.record(builder.build());
    }

    /** @return {@code null} for a blank/absent string, else the trimmed value (a blank filter = no filter). */
    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
