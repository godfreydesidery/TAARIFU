package com.taarifu.responders.api.event;

import com.taarifu.responders.domain.model.enums.AssignmentRole;

import java.time.Instant;
import java.util.UUID;

/**
 * Published domain event: a responder was assigned to a report (PRD §24.3, ARCHITECTURE.md §8).
 *
 * <p>Responsibility: the immutable, cross-module async contract emitted when a
 * {@link com.taarifu.responders.domain.model.ResponderAssignment} is created. The reporting module
 * (SLA clocks, citizen timeline) and communications module (notify the responder) subscribe to it via
 * the transactional outbox/bus — never by reaching into this module's tables (ARCHITECTURE.md §3.2/§8).</p>
 *
 * <p>WHY an event record in {@code api.event} (not a direct call into reporting): reporting is a peer
 * feature module; the only permitted cross-feature coupling is via events. This record carries ids/enums
 * only (no entities) so subscribers stay decoupled from this module's model.</p>
 *
 * <p>Published on the transactional outbox under {@link ResponderEventTypes#RESPONDER_ASSIGNED} by both
 * producers that create an OWNER assignment: the system {@code RoutingHandler} (on {@code REPORT_ROUTED})
 * and the manual {@code ResponderAdminService.assignResponder} path. Reporting's
 * {@code ResponderAssignedHandler} consumes it to set {@code Report.assignedResponderId} and transition the
 * report {@code NEW → ASSIGNED}, closing the routing round-trip asynchronously — no synchronous
 * {@code responders → reporting} write (ADR-0013 §2; ADR-0014 §5b, D21).</p>
 *
 * @param assignmentId   the new assignment's public id.
 * @param reportId       the report the responder was assigned to (loose reference).
 * @param responderId    the assigned responder's public id.
 * @param role           OWNER or COLLABORATOR.
 * @param assignedAt     when the assignment was made (UTC).
 * @param occurredAt     when this event was raised (UTC).
 */
public record ResponderAssignedEvent(
        UUID assignmentId,
        UUID reportId,
        UUID responderId,
        AssignmentRole role,
        Instant assignedAt,
        Instant occurredAt
) {
}
