package com.taarifu.reporting.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.responders.api.event.ResponderAssignedEvent;
import com.taarifu.responders.api.event.ResponderEventTypes;
import com.taarifu.responders.domain.model.enums.AssignmentRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ResponderAssignedHandler} — the reporting consumer that closes the routing
 * round-trip on the report side (A2; D21, ADR-0014 §5b).
 *
 * <p>Responsibility: pins the integrity rules a reviewer must never see regress —
 * <ul>
 *   <li>an <b>OWNER</b> {@code RESPONDER_ASSIGNED} delegates to {@code ReportService.applySystemAssignment}
 *       with the report + responder ids decoded off the payload tree;</li>
 *   <li>a <b>COLLABORATOR</b> assignment is a no-op (it must not touch the report's accountable pointer nor
 *       drive the lifecycle, §24.3);</li>
 *   <li>delivery is idempotent: the handler simply re-delegates, and the service's status guard makes the
 *       repeat a no-op (asserted here by re-invoking with the same payload).</li>
 * </ul>
 *
 * <p>The payload is built as a Jackson {@link JsonNode} tree — exactly what the outbox relay delivers
 * (payload-agnostic, ADR-0014 §3) — so the handler's string-coded-enum decode (which keeps reporting free of
 * any {@code responders.domain} import in production) is exercised on the real wire shape. Mockito only; the
 * service's own idempotency is covered by {@code ReportServiceTest}.</p>
 */
class ResponderAssignedHandlerTest {

    // findAndRegisterModules() pulls in jackson-datatype-jsr310 so Instant serialises to the same tree the
    // production (Spring-configured) mapper produces on the wire — the shape the relay delivers (ADR-0014 §3).
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private ReportService reportService;
    private ResponderAssignedHandler handler;

    private final UUID reportId = UUID.randomUUID();
    private final UUID responderId = UUID.randomUUID();
    private final UUID assignmentId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-06-23T10:00:00Z");

    @BeforeEach
    void setUp() {
        reportService = mock(ReportService.class);
        handler = new ResponderAssignedHandler(reportService);
        when(reportService.applySystemAssignment(any(), any())).thenReturn(true);
    }

    @Test
    void ownerAssignment_appliesSystemAssignment_withDecodedIds() {
        handler.handle(envelope(AssignmentRole.OWNER));

        // The handler decodes reportId + responderId off the tree and delegates to the service (which owns the
        // guarded NEW → ASSIGNED transition). This FAILS if the round-trip leg is removed (CLAUDE.md §10).
        verify(reportService).applySystemAssignment(reportId, responderId);
    }

    @Test
    void collaboratorAssignment_isNoOp() {
        // A COLLABORATOR is added on a multisectoral split; it must NOT set the report's OWNER pointer nor
        // re-drive the lifecycle (§24.3) — so the handler never calls the service.
        handler.handle(envelope(AssignmentRole.COLLABORATOR));

        verify(reportService, never()).applySystemAssignment(any(), any());
    }

    @Test
    void redelivery_reDelegates_serviceMakesItIdempotent() {
        // At-least-once delivery (ADR-0014 §3): the same envelope can arrive twice. The handler delegates both
        // times; the service's status guard (covered in ReportServiceTest) makes the second a no-op. We assert
        // the handler does not swallow/branch on its own — it consistently re-delegates the OWNER event.
        EventEnvelope<?> envelope = envelope(AssignmentRole.OWNER);

        handler.handle(envelope);
        handler.handle(envelope);

        verify(reportService, org.mockito.Mockito.times(2)).applySystemAssignment(reportId, responderId);
    }

    /**
     * Builds a dispatched envelope whose payload is a {@link JsonNode} tree (the relay's wire shape) carrying
     * the given role, with the test's fixed report/responder/assignment ids.
     */
    private EventEnvelope<?> envelope(AssignmentRole role) {
        ResponderAssignedEvent payload =
                new ResponderAssignedEvent(assignmentId, reportId, responderId, role, now, now);
        JsonNode tree = objectMapper.valueToTree(payload);
        return new EventEnvelope<>(UUID.randomUUID(), ResponderEventTypes.RESPONDER_ASSIGNED,
                ResponderEventTypes.AGGREGATE_RESPONDER_ASSIGNMENT, assignmentId, tree, now);
    }
}
