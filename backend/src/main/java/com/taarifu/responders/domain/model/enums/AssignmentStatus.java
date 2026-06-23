package com.taarifu.responders.domain.model.enums;

/**
 * Lifecycle of a {@link com.taarifu.responders.domain.model.ResponderAssignment} — a responder's
 * work on its slice of a report (PRD §24.3, §12.1 lifecycle, §25.2 SLA).
 *
 * <p>Responsibility: tracks each responder's progress independently so the parent report can compute
 * an aggregated status and only close when all assignments resolve (§24.3 — "the parent aggregates
 * child statuses and only closes when children resolve"). The platform's SLA/escalation engine
 * (§25.2) reads this to detect breaches per responder.</p>
 *
 * <p>WHY assignment status is separate from the report's own status: a multisectoral report has one
 * citizen-facing aggregated status but several internal work states (the bank may be {@link #RESOLVED}
 * while the roads agency is still {@link #IN_PROGRESS}). Conflating them loses the per-responder
 * accountability §24.3 requires.</p>
 */
public enum AssignmentStatus {

    /** Assigned but not yet accepted/acknowledged by the responder (SLA acceptance clock runs, §25.2). */
    PENDING,

    /** The responder has accepted the assignment and taken ownership of its slice. */
    ACCEPTED,

    /** Work is underway on the responder's slice. */
    IN_PROGRESS,

    /** The responder's slice is resolved; contributes to the parent's aggregated closure (§24.3). */
    RESOLVED,

    /** Reassigned/withdrawn from this responder (e.g. mis-routed) — retained for audit, not deleted. */
    REASSIGNED,

    /** The assignment was rejected by the responder (out of scope/capacity); routing falls back (§24.2). */
    REJECTED
}
