package com.taarifu.reporting.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.taarifu.common.outbox.DomainEventHandler;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.responders.api.event.ResponderEventTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * The reporting module's outbox handler that closes the routing round-trip on the <b>report side</b>: it
 * consumes the responders module's {@link ResponderEventTypes#RESPONDER_ASSIGNED} back-event and sets the
 * report's denormalised {@code assignedResponderId} + transitions it {@code NEW → ASSIGNED} (PRD §12.1,
 * §24.3, D21; ADR-0014 §5b; ADR-0013 §2).
 *
 * <p><b>Why this handler exists (the round-trip):</b> filing emits {@code REPORT_ROUTED}; the responders
 * {@code RoutingHandler} creates the single accountable OWNER {@code ResponderAssignment} and emits
 * {@code RESPONDER_ASSIGNED} back on the outbox. Without a reporting consumer the OWNER exists on the
 * responders side but {@code Report.assignedResponderId} stays {@code null} and the report stays {@code NEW}
 * — the "official triages" loop is broken on the citizen-facing report row. This handler is that consumer:
 * it runs <b>asynchronously</b> on the outbox relay thread and applies the report-side leg. WHY async (not a
 * synchronous {@code responders → reporting} write back into the lifecycle): a synchronous back-edge would
 * be a dependency cycle (reporting already exposes the synchronous {@code responders → reporting}
 * {@code ReportLifecycleApi} forward edge); routing both legs over the outbox keeps the module graph acyclic
 * (ADR-0013 §1, §2).</p>
 *
 * <h3>Boundary (ADR-0013) — why the payload is read as a JSON tree, not the typed record</h3>
 * The published contract record {@code responders.api.event.ResponderAssignedEvent} exposes its
 * {@code role} as the {@code responders.domain.model.enums.AssignmentRole} enum — a {@code domain} type.
 * Importing it here (even transitively, by calling {@code event.role()}) would be a forbidden reach into a
 * sibling's {@code domain} layer ({@code ModuleBoundaryTest}). So this handler reads the
 * {@code role}/{@code reportId}/{@code responderId} fields straight off the relay's {@code JsonNode} payload
 * as strings/ids — the same string-coded-enum pattern the other consumers use (e.g.
 * {@code AnnouncementPublishedHandler} parses channel <i>names</i>; {@code AnalyticsEventHandler} parses
 * dimension <i>codes</i>). The only responders type imported is {@link ResponderEventTypes} — the taxonomy
 * key constants in {@code responders.api.event} (a sanctioned cross-module {@code ..api..} reference,
 * ADR-0013 §3). No PII is read or logged (PRD §18, ADR-0014 §1): the payload carries ids/enums only.
 *
 * <h3>OWNER only — why a COLLABORATOR assignment is a no-op here</h3>
 * A report carries exactly one accountable OWNER and zero-or-more COLLABORATORs (§24.3). Only the OWNER
 * assignment is the report's accountable assignee and the trigger for {@code NEW → ASSIGNED}; a
 * COLLABORATOR being added (a later multisectoral split) must not overwrite the OWNER pointer nor re-drive
 * the lifecycle. So this handler acts <b>only</b> on {@code role == OWNER} and treats any other role as a
 * no-op success.
 *
 * <h3>Idempotency (at-least-once delivery — ADR-0014 §3)</h3>
 * The relay may redeliver the same {@code eventId}. The transition {@code NEW → ASSIGNED} is applied through
 * {@link ReportService#applySystemAssignment} which is idempotent on the report's current status: it no-ops
 * unless the report is still {@code NEW}. So a redelivery (or an event that arrives after an operator already
 * moved the case past {@code NEW} via the {@code ReportLifecycleApi}) does <b>not</b> double-transition and
 * does not append a duplicate timeline entry. A missing report (e.g. soft-deleted before the relay caught up)
 * is also a no-op success — never a DLQ for a benign race.
 */
@Component
public class ResponderAssignedHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ResponderAssignedHandler.class);

    /** The OWNER role name as it appears on the {@code RESPONDER_ASSIGNED} payload (string-coded enum). */
    private static final String ROLE_OWNER = "OWNER";

    private final ReportService reportService;

    /**
     * @param reportService the reporting application service that owns the §12.1 state machine; this handler
     *                      delegates the guarded {@code NEW → ASSIGNED} transition + assignee write to its
     *                      idempotent {@link ReportService#applySystemAssignment} so the lifecycle stays
     *                      single-owned (the handler never mutates report status directly).
     */
    public ResponderAssignedHandler(ReportService reportService) {
        this.reportService = reportService;
    }

    /** {@inheritDoc} Registers for the responders {@code RESPONDER_ASSIGNED} taxonomy key (ADR-0014 §5b). */
    @Override
    public Set<String> handledEventTypes() {
        return Set.of(ResponderEventTypes.RESPONDER_ASSIGNED);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads {@code role}/{@code reportId}/{@code responderId} off the payload tree; for an OWNER
     * assignment, delegates to {@link ReportService#applySystemAssignment} (idempotent). A COLLABORATOR
     * assignment, a missing report, or a report already past {@code NEW} are all no-op successes — see the
     * class Javadoc. A genuinely malformed payload (no {@code reportId}/{@code responderId}) surfaces as an
     * {@link IllegalStateException} so the relay records the failure rather than silently dropping it.</p>
     */
    @Override
    public void handle(EventEnvelope<?> event) {
        if (!(event.payload() instanceof JsonNode payload)) {
            // Defensive: the relay always delivers a JsonNode tree (ADR-0014 §3). An in-process producer test
            // that hands the concrete record through is read positionally below via the record accessors' field
            // names, so reaching here means a payload shape we cannot interpret — fail loudly, do not drop.
            throw new IllegalStateException("RESPONDER_ASSIGNED payload is not a JSON tree: " + event.eventId());
        }

        // OWNER only: a COLLABORATOR assignment must not set the report's accountable pointer nor drive the
        // lifecycle (§24.3). String-coded enum read keeps reporting free of any responders.domain import.
        String role = text(payload, "role");
        if (!ROLE_OWNER.equals(role)) {
            log.debug("RESPONDER_ASSIGNED eventId={} role={} is not OWNER; skipping (no-op)",
                    event.eventId(), role);
            return;
        }

        UUID reportId = uuid(payload, "reportId");
        UUID responderId = uuid(payload, "responderId");
        if (reportId == null || responderId == null) {
            throw new IllegalStateException(
                    "RESPONDER_ASSIGNED payload missing reportId/responderId: " + event.eventId());
        }

        // Apply the report-side leg idempotently: set the assignee + transition NEW → ASSIGNED. The service
        // no-ops if the report is absent or already past NEW (redelivery / a prior operator transition), so the
        // OWNER pointer and status are set exactly once. Ids only — no PII logged (PRD §18).
        boolean applied = reportService.applySystemAssignment(reportId, responderId);
        if (applied) {
            log.info("RESPONDER_ASSIGNED eventId={} report={} → ASSIGNED (OWNER set); routing round-trip closed",
                    event.eventId(), reportId);
        } else {
            log.debug("RESPONDER_ASSIGNED eventId={} report={}: no-op (absent or already past NEW; idempotent)",
                    event.eventId(), reportId);
        }
    }

    /** Reads a text field from the payload tree, or {@code null} if absent/null. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * Reads a UUID field from the payload tree, or {@code null} if absent/null/blank.
     *
     * @throws IllegalStateException if present but not a valid UUID (a non-retryable data defect — surfaced
     *                               so the relay records the failure rather than silently dropping).
     */
    private static UUID uuid(JsonNode node, String field) {
        String raw = text(node, field);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("RESPONDER_ASSIGNED payload field '" + field + "' is not a UUID", ex);
        }
    }
}
