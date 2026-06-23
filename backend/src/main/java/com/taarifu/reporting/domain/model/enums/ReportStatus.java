package com.taarifu.reporting.domain.model.enums;

import java.util.Set;

/**
 * The lifecycle state of a {@code Report}/case and the <b>server-enforced</b> transition rules between
 * states (PRD §12.1, M3 US-3.4).
 *
 * <p>Responsibility: encodes the report state machine and, crucially, the set of <b>allowed next
 * states</b> for each state. The transition guard lives <i>in the enum itself</i> (not scattered across
 * services) so the rule is single-sourced, greppable, and testable — an illegal transition is rejected
 * the same way no matter which call site attempts it (CLAUDE.md §3 DRY; PRD §12.1 "enforced
 * server-side and audited").</p>
 *
 * <p>The lifecycle (PRD §12.1):</p>
 * <pre>
 * NEW ──assign──► ASSIGNED ──start──► IN_PROGRESS ──need info──► AWAITING_INFO ──reply──► IN_PROGRESS
 *   │                                  ├─resolve──► RESOLVED ──confirm──► CLOSED
 *   │                                  │                  └─dispute──► REOPENED ──► ASSIGNED
 *   ├─reject(invalid/dup)──► REJECTED / DUPLICATE
 *   └─(SLA breach at any active state)──► ESCALATED (stays active)
 * REOPENED / RESOLVED ──auto-timeout──► CLOSED
 * </pre>
 *
 * <p>WHY {@code ESCALATED} is modelled as a status here yet "stays active": per PRD §12.1 escalation does
 * not freeze a case — it flags it and notifies a supervisor while work continues. We therefore treat
 * {@code ESCALATED} as an active state from which the normal resolution path (resolve → RESOLVED) is
 * still reachable, plus the routing/assignment transitions. Actual SLA-breach detection and supervisor
 * notification are the responders/notifications increments' job (routing is DEFERRED here); this module
 * owns only the state-transition legality.</p>
 *
 * <p>WHY {@code DUPLICATE} and {@code REJECTED} are terminal: a report closed as invalid or merged into
 * another never re-enters the work queue under its own identity (the canonical report carries the
 * weight). Re-opening a wrongly-rejected report is a moderation action that creates a fresh transition
 * record, not a hidden back-edge here.</p>
 */
public enum ReportStatus {

    /** Freshly filed, not yet assigned to a responder. The intake state (PRD §12.1, UC-D01 step 7). */
    NEW,

    /** Assigned to a responder office/agent but not yet actively worked. */
    ASSIGNED,

    /** A responder is actively working the case. */
    IN_PROGRESS,

    /** The responder asked the reporter for more information; the SLA clock pauses by policy. */
    AWAITING_INFO,

    /** The responder marked the case resolved (requires a resolution note, US-3.4); awaits citizen confirm. */
    RESOLVED,

    /** Citizen confirmed the resolution, or the auto-timeout fired — the terminal happy-path state. */
    CLOSED,

    /** Citizen disputed the resolution; the case re-enters the queue (→ ASSIGNED) for fresh work. */
    REOPENED,

    /** Triaged as invalid/spam/out-of-remit — terminal (PRD §12.1 "reject"). */
    REJECTED,

    /** Merged into a canonical report ({@code duplicateOfId}) — terminal (US-3.7/US-3.8). */
    DUPLICATE,

    /** SLA-breached and flagged to a supervisor; remains active (work continues, PRD §12.1). */
    ESCALATED;

    /**
     * Returns the states this status may legally transition <b>to</b>.
     *
     * <p>WHY a method over a static map: keeps the rule co-located with the state it governs and lets
     * {@link #canTransitionTo(ReportStatus)} be a one-liner the service and tests both call. A state with
     * an empty set is terminal.</p>
     *
     * @return the set of permitted successor states (empty for terminal states).
     */
    public Set<ReportStatus> allowedNext() {
        return switch (this) {
            // From intake we can assign, or triage out as invalid/duplicate, or escalate.
            case NEW -> Set.of(ASSIGNED, REJECTED, DUPLICATE, ESCALATED);
            // An assigned case can start work, be re-triaged out, escalate, or (re)assign elsewhere.
            case ASSIGNED -> Set.of(IN_PROGRESS, REJECTED, DUPLICATE, ESCALATED, ASSIGNED);
            // Active work can request info, resolve, escalate, or be re-assigned.
            case IN_PROGRESS -> Set.of(AWAITING_INFO, RESOLVED, ESCALATED, ASSIGNED);
            // Awaiting the reporter: a reply resumes work; it can still resolve/escalate.
            case AWAITING_INFO -> Set.of(IN_PROGRESS, RESOLVED, ESCALATED);
            // Resolved awaits the citizen: confirm→CLOSED, dispute→REOPENED, or auto-timeout→CLOSED.
            case RESOLVED -> Set.of(CLOSED, REOPENED);
            // A reopened case goes back into the queue for fresh assignment, or auto-closes on timeout.
            case REOPENED -> Set.of(ASSIGNED, CLOSED, ESCALATED);
            // Escalated stays active — the normal resolution path remains reachable.
            case ESCALATED -> Set.of(IN_PROGRESS, RESOLVED, ASSIGNED, AWAITING_INFO);
            // Terminal states.
            case CLOSED, REJECTED, DUPLICATE -> Set.of();
        };
    }

    /**
     * @param target the proposed next state.
     * @return {@code true} if moving from {@code this} to {@code target} is a legal transition.
     */
    public boolean canTransitionTo(ReportStatus target) {
        return allowedNext().contains(target);
    }

    /**
     * @return {@code true} if this is a terminal state (no further transitions) — {@code CLOSED},
     *         {@code REJECTED}, {@code DUPLICATE}.
     */
    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }

    /**
     * @return {@code true} if a case in this state is still "open" for SLA/queue purposes — i.e. not
     *         terminal. {@code ESCALATED} and {@code AWAITING_INFO} are open (work is ongoing/pending).
     */
    public boolean isActive() {
        return !isTerminal();
    }
}
