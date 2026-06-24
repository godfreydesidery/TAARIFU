package com.taarifu.responders.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.analytics.api.event.AnalyticsEventTypes;
import com.taarifu.analytics.api.event.CivicActivityRecorded;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.outbox.DomainEventHandler;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.common.outbox.OutboxWriter;
import com.taarifu.reporting.api.event.ReportEventTypes;
import com.taarifu.reporting.api.event.ReportRouted;
import com.taarifu.responders.api.event.ResponderAssignedEvent;
import com.taarifu.responders.api.event.ResponderEventTypes;
import com.taarifu.responders.domain.model.Responder;
import com.taarifu.responders.domain.model.ResponderAssignment;
import com.taarifu.responders.domain.model.RoutingRule;
import com.taarifu.responders.domain.model.enums.AssignmentRole;
import com.taarifu.responders.domain.model.enums.ResponderStatus;
import com.taarifu.responders.domain.repository.ResponderAssignmentRepository;
import com.taarifu.responders.domain.repository.ResponderRepository;
import com.taarifu.responders.domain.repository.RoutingRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The responders module's outbox handler that turns a {@link ReportEventTypes#REPORT_ROUTED} event into the
 * report's single accountable OWNER {@link ResponderAssignment} (PRD §24.2/§24.3, D21; ADR-0014 §5b).
 *
 * <p>Responsibility: when reporting files a report and appends {@code REPORT_ROUTED} to the outbox (ids
 * only — report/category/ward), this handler runs <b>asynchronously</b> on the outbox relay's thread,
 * resolves the responsible responder for the report's category + ward from this module's directory + routing
 * table, and creates the OWNER assignment. WHY async (not a synchronous {@code reporting → responders} call):
 * a synchronous edge that direction would be a dependency cycle and would couple the citizen's filing to a
 * responders outage (ADR-0013 §1; PRD §15 DI3). The handler reads nothing from reporting's internals — the
 * event carries the two ids it needs (category, ward); it never touches the reporter, title, or any PII
 * (ADR-0014 §1).</p>
 *
 * <h3>Idempotency (at-least-once delivery — ADR-0014 §3)</h3>
 * The relay can deliver the same {@code eventId} more than once. This handler is idempotent on the
 * <b>existing single-OWNER guard</b> (ADR-0014 §5b "naturally-idempotent write"): it (1) no-ops if the report
 * already has a live OWNER, and (2) the DB partial-unique index {@code ux_responder_assignment_one_owner}
 * (one live OWNER per report) is the hard backstop — a racing redelivery that slips past the read check hits
 * the constraint, which is caught and treated as "already routed" (a no-op), never a double-OWNER and never a
 * permanent failure. So exactly one OWNER assignment exists per report regardless of redelivery.
 *
 * <h3>Resolution (PRD §24.2, §25.2 fallback ladder)</h3>
 * <ol>
 *   <li>Evaluate active {@link RoutingRule}s for the category, in ascending {@code priority} (most specific /
 *       earliest first).</li>
 *   <li>For each rule, prefer its pinned {@code preferredResponder} when it is ACTIVE and covers the ward;
 *       otherwise pick the first ACTIVE responder of the rule's {@code responderType} that handles the
 *       category and covers the ward.</li>
 *   <li>If no rule resolves a responder, <b>no OWNER is created</b> — the report stays unrouted for manual
 *       assignment (the {@code ResponderAdminService.assignResponder} path). This is a <b>no-op success</b>,
 *       not a failure: a missing routing rule must not push the event to the DLQ and must not block filing.</li>
 * </ol>
 *
 * <p><b>Integrity fence (D18):</b> routing reads only the responder directory, routing rules, and coverage —
 * <b>never the token ledger</b>. Token balance must never influence official routing/SLA/priority (PRD §23.5).</p>
 */
@Component
public class RoutingHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(RoutingHandler.class);

    private final RoutingRuleRepository routingRuleRepository;
    private final ResponderRepository responderRepository;
    private final ResponderAssignmentRepository assignmentRepository;
    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;
    private final ClockPort clock;

    /**
     * @param routingRuleRepository the routing table (category → responderType/preferred responder, §24.2).
     * @param responderRepository   the responder directory (routing candidates by type + category + coverage).
     * @param assignmentRepository  assignment persistence + the single-OWNER read guard (idempotency).
     * @param outboxWriter          the transactional-outbox port; on a NEW OWNER, this handler appends a
     *                              {@link ResponderEventTypes#RESPONDER_ASSIGNED} event so a reporting handler
     *                              sets the report's assigned-responder reference asynchronously — closing the
     *                              routing loop without a synchronous {@code responders → reporting} write (D21).
     * @param objectMapper          the shared Jackson mapper — deserialises the relay's {@code JsonNode}
     *                              payload into the public {@link ReportRouted} record (ids only).
     * @param clock                 injectable time source for the assignment timestamp (testability).
     */
    public RoutingHandler(RoutingRuleRepository routingRuleRepository,
                          ResponderRepository responderRepository,
                          ResponderAssignmentRepository assignmentRepository,
                          OutboxWriter outboxWriter,
                          ObjectMapper objectMapper,
                          ClockPort clock) {
        this.routingRuleRepository = routingRuleRepository;
        this.responderRepository = responderRepository;
        this.assignmentRepository = assignmentRepository;
        this.outboxWriter = outboxWriter;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** {@inheritDoc} Registers for the reporting {@code REPORT_ROUTED} taxonomy key (ADR-0014 §5b). */
    @Override
    public Set<String> handledEventTypes() {
        return Set.of(ReportEventTypes.REPORT_ROUTED);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Creates the OWNER {@link ResponderAssignment} for the routed report, idempotently. Runs in its own
     * transaction (joins the relay's batch transaction); a thrown exception causes the relay to retry the
     * event (backoff) and eventually FAIL the row — but the only unexpected exceptions here are infrastructure
     * failures, since a missing routing rule / no eligible responder is a deliberate no-op (see the class
     * Javadoc), and a redelivery race on the single-OWNER index is caught as "already routed".</p>
     */
    @Override
    @Transactional
    public void handle(EventEnvelope<?> event) {
        ReportRouted routed = deserialise(event.payload());

        // IDEMPOTENCY (read-side): if this report already has a live OWNER, a redelivery (or a manual prior
        // assignment) means routing is done — no-op. The DB partial-unique index is the hard backstop below.
        if (assignmentRepository.findOwner(routed.reportId(), AssignmentRole.OWNER).isPresent()) {
            log.debug("REPORT_ROUTED eventId={} report={} already has an OWNER; skipping (idempotent)",
                    event.eventId(), routed.reportId());
            return;
        }

        Optional<Responder> responder = resolveResponder(routed.categoryId(), routed.wardId());
        if (responder.isEmpty()) {
            // No routing rule / no eligible responder for this category+ward. The report stays unrouted for
            // manual assignment (§25.2). NO-OP SUCCESS — never DLQ the event for a config gap (the row is
            // marked PROCESSED so it is not retried forever).
            log.info("REPORT_ROUTED eventId={} report={} category={} ward={}: no eligible responder resolved; "
                            + "left unrouted for manual assignment",
                    event.eventId(), routed.reportId(), routed.categoryId(), routed.wardId());
            return;
        }

        createOwnerAssignment(routed, responder.get(), event.eventId());
    }

    /**
     * Persists the OWNER assignment, catching a concurrent single-OWNER constraint violation as "already
     * routed" (idempotent under at-least-once delivery + concurrency — ADR-0014 §5b). The
     * {@code assignedByUserPublicId} is {@code null}: this is <b>system</b> routing, not an operator action.
     *
     * @param reportId    the routed report's public id.
     * @param responder   the resolved responsible responder.
     * @param eventId     the delivering event's idempotency key (for diagnostics only).
     */
    private void createOwnerAssignment(ReportRouted routed, Responder responder, UUID eventId) {
        UUID reportId = routed.reportId();
        Instant assignedAt = clock.now();
        ResponderAssignment assignment = ResponderAssignment.create(
                reportId, responder, AssignmentRole.OWNER, null, assignedAt);
        try {
            assignmentRepository.save(assignment);
            assignmentRepository.flush(); // surface the unique-index violation HERE so we can treat it as a no-op
        } catch (DataIntegrityViolationException ex) {
            // A racing redelivery created the OWNER first; the partial-unique index ux_responder_assignment_one_owner
            // rejected this one. That is the exactly-once-OWNER guarantee working — treat as a no-op, not a failure.
            // Do NOT emit ResponderAssigned (the winning delivery already did) — avoids a duplicate back-event.
            log.debug("REPORT_ROUTED eventId={} report={}: concurrent OWNER already created; skipping (idempotent)",
                    eventId, reportId);
            return;
        }
        // Close the loop ASYNCHRONOUSLY (D21, ADR-0014 §5b): append RESPONDER_ASSIGNED in THIS handler's
        // transaction (same tx as the assignment row → atomic) so a reporting handler sets the report's
        // assigned-responder reference. There is NO synchronous responders → reporting write — the back-edge
        // is also via the outbox, keeping the graph acyclic (ADR-0013 §1). Payload is ids/enums only (no PII).
        outboxWriter.append(EventEnvelope.of(
                ResponderEventTypes.RESPONDER_ASSIGNED,
                ResponderEventTypes.AGGREGATE_RESPONDER_ASSIGNMENT,
                assignment.getPublicId(),
                new ResponderAssignedEvent(assignment.getPublicId(), reportId, responder.getPublicId(),
                        AssignmentRole.OWNER, assignedAt, assignedAt),
                assignedAt));
        // ANALYTICS (Appendix E, M15): the system-routed OWNER assignment is a routing/ops fact. Emitted on the
        // outbox in THIS handler's transaction; recorded ASYNCHRONOUSLY by the analytics sink. We enrich it with
        // the ward + category from the routed payload (ids only — no report PII; ADR-0014 §1) so the routing
        // dashboards can segment by area×category. Idempotent: a redelivery that loses the OWNER race returns
        // above before this emit, so the fact is appended exactly once per successful OWNER creation.
        outboxWriter.append(EventEnvelope.of(
                AnalyticsEventTypes.CIVIC_ACTIVITY_RECORDED,
                AnalyticsEventTypes.AGGREGATE_CIVIC_ACTIVITY,
                assignment.getPublicId(),
                new CivicActivityRecorded(
                        AnalyticsEventTypes.REPORT_ROUTED,
                        assignedAt,
                        null,                       // actorRef: system routing, no human actor
                        routed.wardId(),            // ward-or-coarser geo dimension (id only)
                        routed.categoryId(),        // issue-category dimension (id only)
                        null,                       // tier: n/a (system)
                        null,                       // channel: n/a (server-side)
                        "SYSTEM",                   // activeRole name (string — NOT the analytics enum; ADR-0013 §3)
                        null,                       // latencySeconds: n/a
                        null,                       // breachType: n/a
                        AssignmentRole.OWNER.name()), // outcome = OWNER (controlled vocab)
                assignedAt));
        log.info("REPORT_ROUTED eventId={} report={} → OWNER responder={} (D21); RESPONDER_ASSIGNED emitted",
                eventId, reportId, responder.getPublicId());
    }

    /**
     * Resolves the responsible responder for a report's category + ward via the routing table, applying the
     * §24.2 precedence (lowest {@code priority} first) and the §25.2 fallback ladder (first matching rule
     * with an eligible responder wins).
     *
     * @param categoryId the report's category public id.
     * @param wardId     the report's ward public id (coverage narrowing).
     * @return the resolved responder, or empty if no rule yields an eligible, covering responder.
     */
    private Optional<Responder> resolveResponder(UUID categoryId, UUID wardId) {
        List<RoutingRule> rules = routingRuleRepository.findActiveByCategoryOrderByPriority(categoryId);
        for (RoutingRule rule : rules) {
            Optional<Responder> match = matchForRule(rule, categoryId, wardId);
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }

    /**
     * Picks an eligible responder for one rule: the rule's pinned {@code preferredResponder} if it is ACTIVE
     * and covers the ward, otherwise the first ACTIVE responder of the rule's {@code responderType} that
     * handles the category and covers the ward.
     *
     * @param rule       the routing rule being evaluated.
     * @param categoryId the report's category public id.
     * @param wardId     the report's ward public id.
     * @return the eligible responder for this rule, or empty.
     */
    private Optional<Responder> matchForRule(RoutingRule rule, UUID categoryId, UUID wardId) {
        Responder preferred = rule.getPreferredResponder();
        if (preferred != null && preferred.getStatus() == ResponderStatus.ACTIVE && preferred.coversArea(wardId)) {
            return Optional.of(preferred);
        }
        // Directory fallback: first ACTIVE responder of the rule's type handling the category and covering the
        // ward. coversArea() widens for NATIONWIDE (which a SQL area-IN cannot express), so the area test is
        // applied here in Java over the small candidate set.
        return responderRepository.findRoutingCandidates(rule.getResponderType(), categoryId).stream()
                .filter(r -> r.coversArea(wardId))
                .findFirst();
    }

    /**
     * Deserialises the relay-delivered payload (a Jackson {@link JsonNode} tree — the relay is payload-agnostic,
     * ADR-0014 §3) into the public {@link ReportRouted} record. The record carries ids only; no PII can appear
     * here by the event contract (PRD §18).
     *
     * @param payload the envelope payload as delivered (a {@code JsonNode} from the persisted {@code jsonb}).
     * @return the typed {@link ReportRouted} record.
     * @throws IllegalStateException if the payload cannot be read as {@link ReportRouted} (a non-retryable data
     *                               defect — surfaced so the relay records the failure rather than silently dropping).
     */
    private ReportRouted deserialise(Object payload) {
        try {
            if (payload instanceof JsonNode node) {
                return objectMapper.treeToValue(node, ReportRouted.class);
            }
            // Defensive: an in-process producer test may hand the concrete record straight through.
            return objectMapper.convertValue(payload, ReportRouted.class);
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("REPORT_ROUTED payload is not a valid ReportRouted", ex);
        }
    }
}
