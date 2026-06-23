package com.taarifu.reporting.api.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published domain event: a report was filed and is ready to be routed to a responder OWNER
 * (PRD §24.3, D21; ADR-0013 §1/§4a, ADR-0014 §5b).
 *
 * <p>Responsibility: the immutable, cross-module <b>async</b> contract reporting emits — on the
 * {@code outboxWriter.append(...)} inside the report-create transaction — so the responders module can
 * create the OWNER {@link com.taarifu.responders.domain.model.ResponderAssignment} <i>asynchronously</i>,
 * off the citizen's request thread. WHY async (not a synchronous call into responders): a synchronous
 * {@code reporting → responders} edge would be a dependency cycle (responders already reads
 * {@code reporting} synchronously for category/report validation — ADR-0013 §1). Splitting routing onto
 * the outbox keeps the graph acyclic and means a responders outage never rolls back the citizen's filing
 * (PRD §15 DI3).</p>
 *
 * <p><b>🔒 ids only — never PII</b> (PRD §18, ADR-0014 §1): the payload carries the report, category, and
 * ward public ids and nothing else. The responders routing handler re-reads anything more it needs (e.g.
 * the report's scope) through reporting's published {@code *QueryApi} (ADR-0013) — it never expects the
 * reporter, title, description, or geo-point on this envelope.</p>
 *
 * <p>The taxonomy key handlers register on is {@link ReportEventTypes#REPORT_ROUTED}; the aggregate type
 * is {@link ReportEventTypes#AGGREGATE_REPORT}. The event's idempotency key is the outbox row's
 * {@code public_id} (carried as {@code EventEnvelope.eventId} on dispatch) — the responders handler is
 * idempotent on it via the existing single-OWNER guard (ADR-0014 §5b).</p>
 *
 * @param reportId   the filed report's public id (the routing target; consumers re-read by this id).
 * @param categoryId the report's issue category public id (drives {@code RoutingRule} resolution, §24.2).
 * @param wardId     the report's resolved ward (Kata) public id (narrows the responder by coverage area).
 * @param occurredAt domain-time the report was filed/routed (UTC).
 */
public record ReportRouted(
        UUID reportId,
        UUID categoryId,
        UUID wardId,
        Instant occurredAt
) {
}
