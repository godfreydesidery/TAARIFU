package com.taarifu.admin.api.controller;

import com.taarifu.admin.api.dto.FailedEventDto;
import com.taarifu.admin.api.dto.ReplayOutboxRequest;
import com.taarifu.admin.api.dto.ReplayOutboxResultDto;
import com.taarifu.admin.application.service.OutboxAdminService;
import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.api.dto.PageMeta;
import com.taarifu.common.pagination.PageRequestFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The admin console's <b>transactional-outbox dead-letter-queue (DLQ)</b> ops surface (M14; ADR-0014 revisit
 * trigger (c); outbox review P3-1/P4-4).
 *
 * <p>Responsibility: a thin HTTP layer over {@link OutboxAdminService} that lets an operator <b>see</b> the
 * terminally FAILED outbox events (PII-free) and <b>replay</b> them — re-queue them to PENDING — by a single
 * event id or a bounded window, after the underlying failure cause has been fixed. It holds no business logic
 * and no {@code @Transactional} (ARCHITECTURE §3.3); the DLQ read/replay is sourced through the shared-kernel
 * {@code OutboxReplayService} (the admin module never reaches into {@code common.outbox} internals).</p>
 *
 * <p><b>Authorization (deny-by-default, ARCHITECTURE §6.2; PRD §7.1, §18):</b>
 * {@code hasAnyRole('ADMIN','ROOT')} on every method. Draining the DLQ re-fires domain effects (feed fan-out,
 * notifications, SLA clocks); it is a high-trust back-office power, so a citizen/responder/moderator token is
 * forbidden (403) and an anonymous request is unauthenticated (401). The security-gate test fails closed if a
 * {@code @PreAuthorize} were removed.</p>
 *
 * <p><b>Staff second factor (MFA):</b> the MFA gate is upstream — an {@code ADMIN}/{@code ROOT} access token
 * is only minted after the TOTP step (AUTH-DESIGN §14.1), so reaching these handlers already implies MFA was
 * satisfied. Requiring the staff role IS the MFA-gated path (identical to the other admin surfaces).</p>
 *
 * <p><b>Privacy (PRD §18, ADR-0014 §1):</b> the list returns ids/eventType/attempts/age only — never the
 * event payload or the redacted {@code last_error} text; replays are audited by references + counts only,
 * never PII. The acting admin is always the authenticated caller (the service reads it from the security
 * context), never a request body field.</p>
 */
@RestController
@RequestMapping(path = "/admin/outbox")
@Tag(name = "Admin Outbox DLQ", description = "Back-office dead-letter-queue list + replay (ADR-0014).")
public class AdminOutboxController {

    private final OutboxAdminService outboxAdmin;
    private final PageRequestFactory pageRequests;
    private final ResponseFactory responses;

    /**
     * @param outboxAdmin  the DLQ ops workflow service.
     * @param pageRequests reuses the kernel's page-size cap/defaults (DoS/data-budget guard, PRD §15).
     * @param responses    envelope builder.
     */
    public AdminOutboxController(OutboxAdminService outboxAdmin,
                                 PageRequestFactory pageRequests,
                                 ResponseFactory responses) {
        this.outboxAdmin = outboxAdmin;
        this.pageRequests = pageRequests;
        this.responses = responses;
    }

    /**
     * Lists the dead-letter queue — terminally FAILED outbox events, oldest first — as PII-free rows.
     *
     * @param page zero-based page index (defaults 0).
     * @param size page size (capped at the kernel's {@code MAX_SIZE}).
     * @return {@code 200} + a paged list of {@link FailedEventDto} with pagination {@code meta}.
     */
    @GetMapping("/failed")
    @PreAuthorize("hasAnyRole('ADMIN','ROOT')")
    @Operation(summary = "List the outbox dead-letter queue (admin/root)",
            description = "Terminally FAILED events, oldest first; id/eventType/attempts/age only — no payload "
                    + "or error text (PRD §18, ADR-0014).")
    public ApiResponse<List<FailedEventDto>> listFailed(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        // Reuse the kernel page-size cap (PRD §15). Order is fixed server-side (oldest FAILED-time first) by
        // the kernel query, so the client cannot pass an arbitrary sort property into the DLQ read.
        Pageable pageable = pageRequests.of(page, size, null);
        Page<FailedEventDto> result = outboxAdmin.listFailed(pageable);
        PageMeta meta = new PageMeta(result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
        return responses.paged(result.getContent(), meta);
    }

    /**
     * Re-queues FAILED outbox events back to PENDING — by a single event id or a bounded window — and audits
     * the action. {@code requeued=0} is a normal idempotent outcome (already replayed / nothing matched).
     *
     * @param request the replay command (by-id when {@code eventId} is set, else a bounded window).
     * @return {@code 200} + the {@link ReplayOutboxResultDto} (mode + count actually re-queued).
     */
    @PostMapping("/replay")
    @PreAuthorize("hasAnyRole('ADMIN','ROOT')")
    @Operation(summary = "Replay FAILED outbox events to PENDING (admin/root)",
            description = "By event id or a bounded (eventType/time) window; idempotent and capped; audited "
                    + "by refs + count only (ADR-0014, P3-1).")
    public ApiResponse<ReplayOutboxResultDto> replay(@Valid @RequestBody ReplayOutboxRequest request) {
        return responses.ok(outboxAdmin.replay(request));
    }
}
