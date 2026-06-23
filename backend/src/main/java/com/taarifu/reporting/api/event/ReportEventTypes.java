package com.taarifu.reporting.api.event;

/**
 * The reporting module's published outbox <b>taxonomy keys</b> — the {@code eventType} / {@code aggregateType}
 * string constants a producer stamps onto an {@code EventEnvelope} and a handler registers on
 * (ADR-0014 §1/§4; D21).
 *
 * <p>Responsibility: a single source of truth for the reporting event taxonomy so the producer (reporting's
 * {@code ReportService}) and the consumer (responders' {@code RoutingHandler}) reference the <b>same</b>
 * literal — never two drifting copies of {@code "REPORT_ROUTED"} (DRY; CLAUDE.md §3). It lives in
 * {@code reporting.api.event} (the module's public contract) so the responders module may import the
 * constants across the boundary — a sanctioned cross-module {@code ..api..} reference (ADR-0013 §3), not a
 * reach into reporting's internals.</p>
 *
 * <p>WHY plain {@code String} constants (not an enum): the {@code DomainEventHandler} SPI and the
 * {@code OutboxEvent.event_type} column are both {@code String}-typed (ADR-0014 §4), so the dispatcher
 * routes by exact string match; exposing the keys as constants keeps both ends type-checked against one
 * declaration without forcing the generic outbox to know any module's enum.</p>
 */
public final class ReportEventTypes {

    private ReportEventTypes() {
        // Constants holder — not instantiable.
    }

    /**
     * The {@code aggregateType} for report-sourced events: the producing aggregate is a {@code Report}
     * (ADR-0014 §1 — used for replay/diagnostics by aggregate, never as an FK).
     */
    public static final String AGGREGATE_REPORT = "REPORT";

    /**
     * The {@code eventType} taxonomy key for "a report was filed and is ready to route to an OWNER"
     * (ADR-0014 §5b; D21). Emitted by reporting on file; consumed by the responders {@code RoutingHandler}
     * which creates the single OWNER {@code ResponderAssignment} idempotently.
     */
    public static final String REPORT_ROUTED = "REPORT_ROUTED";
}
