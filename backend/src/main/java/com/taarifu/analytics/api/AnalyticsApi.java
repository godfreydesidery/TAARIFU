package com.taarifu.analytics.api;

/**
 * The analytics module's <b>public, in-process API</b> for other modules to record product-analytics
 * facts (M15; PRD Appendix E; ARCHITECTURE.md §3.2 — modules talk only through each other's published
 * {@code api} package; the same shape as {@code tokens.api.TokenLedgerApi}).
 *
 * <p>Responsibility: the single contract any feature module (reporting, identity, engagement,
 * communications, moderation) depends on to record "something measurable happened", without importing
 * analytics' internals or touching the {@code analytics_event} table. Recording is <b>idempotent</b> on
 * {@link RecordEventCommand#eventId} so a replayed/duplicated outbox emission is a no-op (no double-count,
 * Appendix E.0/E.3).</p>
 *
 * <p><b>Where this is called from (live emission — {@code // TODO(wiring)}):</b> per ADR-0013 §2, analytics
 * is an event-driven sink fed by the transactional outbox (the same source as feed/notification fan-out).
 * That outbox increment is <b>not yet built</b>, so sibling modules wire their emission to this port later;
 * each emission site is marked {@code // TODO(wiring)} until then. This module ships the contract + the
 * idempotent recorder + the aggregation read surface now, so emission can land independently.</p>
 *
 * <p><b>🔒 No PII (Appendix E.4, binding — §18/PDPA):</b> the only identity this API accepts is the
 * pseudonymous {@code actorRef} on the command; there is no method or field through which a name, phone,
 * national/voter ID, free text, or precise GPS could enter the analytics store. This is the no-PII rule
 * expressed as an API.</p>
 */
public interface AnalyticsApi {

    /**
     * Records one analytics fact, exactly once per {@code eventId}.
     *
     * <p>Idempotent: if a fact with the command's {@code eventId} already exists, this is a no-op and
     * returns {@code false}. Recording is best-effort and <b>must never sit on a citizen's critical path</b>
     * (Appendix E "never on the critical path"); callers invoke it from outbox workers, not inline.</p>
     *
     * @param command the (PII-free) fact to record.
     * @return {@code true} if a new fact was recorded; {@code false} if it was a duplicate (already recorded).
     */
    boolean record(RecordEventCommand command);
}
