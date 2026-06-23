package com.taarifu.responders.api.event;

/**
 * The responders module's published outbox <b>taxonomy keys</b> — the {@code eventType} /
 * {@code aggregateType} string constants stamped onto an {@code EventEnvelope} for responder-sourced events
 * (ADR-0014 §1/§4; D21).
 *
 * <p>Responsibility: one source of truth for the responders event taxonomy so the producer (the responders
 * routing handler / admin service) and any consumer (e.g. a reporting handler that sets the report's
 * assigned-responder reference) reference the <b>same</b> literal (DRY; CLAUDE.md §3). It lives in
 * {@code responders.api.event} (the module's public contract) so a sibling may import the constants across
 * the boundary — a sanctioned cross-module {@code ..api..} reference (ADR-0013 §3).</p>
 */
public final class ResponderEventTypes {

    private ResponderEventTypes() {
        // Constants holder — not instantiable.
    }

    /**
     * The {@code aggregateType} for responder-assignment-sourced events: the producing aggregate is a
     * {@code ResponderAssignment} (ADR-0014 §1).
     */
    public static final String AGGREGATE_RESPONDER_ASSIGNMENT = "RESPONDER_ASSIGNMENT";

    /**
     * The {@code eventType} taxonomy key for "a responder was assigned to a report" (D21; ADR-0014 §5b).
     * Emitted by the responders routing handler after it creates the OWNER assignment; a reporting handler
     * consumes it to set the report's assigned-responder reference asynchronously — closing the
     * routing loop without a synchronous {@code responders → reporting} write.
     */
    public static final String RESPONDER_ASSIGNED = "RESPONDER_ASSIGNED";
}
