package com.taarifu.analytics.api.event;

/**
 * The analytics module's published outbox <b>taxonomy keys</b> — the {@code eventType} / {@code aggregateType}
 * string constants a sibling module stamps onto an {@code EventEnvelope} when it emits a civic-activity fact
 * for the analytics sink (ADR-0013 §2 "analytics is event-driven"; ADR-0014 §1/§4; PRD Appendix E).
 *
 * <p>Responsibility: one source of truth for the analytics emission taxonomy so every producer (reporting,
 * moderation, responders, engagement) and the single consumer (the analytics
 * {@code AnalyticsEventHandler}) reference the <b>same</b> literal — never two drifting copies of
 * {@code "CIVIC_ACTIVITY_RECORDED"} (DRY; CLAUDE.md §3). It lives in {@code analytics.api.event} (the
 * module's public contract) so sibling feature modules may import the constants across the boundary — a
 * sanctioned cross-module {@code ..api..} reference (ADR-0013 §3), not a reach into analytics' internals.</p>
 *
 * <p><b>WHY a single outbox {@code eventType} (not one per civic event):</b> every civic-activity fact rides
 * the same {@link CivicActivityRecorded} envelope and is consumed by the same analytics handler; the
 * <i>specific</i> kind of occurrence travels inside the payload as {@link CivicActivityRecorded#analyticsEventType()}
 * (a string drawn from the analytics catalogue), which the handler maps to its own
 * {@code AnalyticsEventType} enum. This keeps the dispatcher routing simple (one key → one handler) and
 * means a new civic event type needs <b>no</b> new outbox taxonomy key — only a new catalogue value
 * (additive, Appendix E.0).</p>
 *
 * <p>WHY plain {@code String} constants (not an enum): the {@code DomainEventHandler} SPI and the
 * {@code OutboxEvent.event_type} column are both {@code String}-typed (ADR-0014 §4), so the dispatcher
 * routes by exact string match; exposing the keys as constants keeps both ends type-checked against one
 * declaration without forcing a producer to import the analytics enum (which lives in
 * {@code analytics.domain.model.enums} — off-limits across the boundary, ADR-0013 §3).</p>
 */
public final class AnalyticsEventTypes {

    private AnalyticsEventTypes() {
        // Constants holder — not instantiable.
    }

    /**
     * The {@code aggregateType} for analytics-sourced outbox rows. The producing aggregate is whatever civic
     * record triggered the fact (a report, a petition, …); analytics uses a single coarse aggregate type for
     * replay/diagnostics rather than mirroring each producer's aggregate (ADR-0014 §1 — the aggregate type is
     * for routing/replay, never an FK).
     */
    public static final String AGGREGATE_CIVIC_ACTIVITY = "CIVIC_ACTIVITY";

    /**
     * The single {@code eventType} taxonomy key the analytics {@code AnalyticsEventHandler} registers on. Every
     * emitting module appends a {@link CivicActivityRecorded} payload under this key in its own transaction;
     * the analytics handler consumes it asynchronously off the outbox relay and records one
     * {@code AnalyticsEvent} idempotently (ADR-0013 §2, ADR-0014 §3 — at-least-once + idempotent on the
     * outbox {@code public_id}).
     */
    public static final String CIVIC_ACTIVITY_RECORDED = "CIVIC_ACTIVITY_RECORDED";

    // -----------------------------------------------------------------------------------------------------
    // The analytics catalogue values a producer stamps onto CivicActivityRecorded.analyticsEventType().
    // These mirror the analytics module's own AnalyticsEventType enum NAMES (kept as strings here so a
    // producer never imports analytics.domain.model.enums — ADR-0013 §3). The handler maps the string back
    // to the enum; an unknown value is dropped as a no-op (forward-compatible, Appendix E.0 additive).
    // -----------------------------------------------------------------------------------------------------

    /** A report was filed (reporting). Maps to {@code AnalyticsEventType.REPORT_FILED}. */
    public static final String REPORT_FILED = "REPORT_FILED";

    /** A report's status changed / it was (re)categorised on a lifecycle move (reporting). */
    public static final String REPORT_STATUS_CHANGED = "REPORT_STATUS_CHANGED";

    /** A report reached RESOLVED (reporting). Maps to {@code AnalyticsEventType.REPORT_RESOLVED}. */
    public static final String REPORT_RESOLVED = "REPORT_RESOLVED";

    /** A moderator took an action on content (moderation). Maps to {@code MODERATION_ACTION_TAKEN}. */
    public static final String MODERATION_ACTION_TAKEN = "MODERATION_ACTION_TAKEN";

    /** A responder assignment was created (responders). Recorded as a routing/ops {@code REPORT_ROUTED} fact. */
    public static final String REPORT_ROUTED = "REPORT_ROUTED";

    /** A T3 citizen signed a petition (engagement). Maps to {@code AnalyticsEventType.PETITION_SIGNED}. */
    public static final String PETITION_SIGNED = "PETITION_SIGNED";

    /** A citizen responded to a survey/poll (engagement). Maps to {@code AnalyticsEventType.SURVEY_RESPONDED}. */
    public static final String SURVEY_RESPONDED = "SURVEY_RESPONDED";
}
