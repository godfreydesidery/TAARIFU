package com.taarifu.reporting.domain.model.enums;

/**
 * The operational priority of a report, which can shorten its effective SLA (PRD Appendix D.2 note:
 * "Priority can shorten effective SLA").
 *
 * <p>Responsibility: a coarse triage band used by responder queues to order work and by the SLA engine
 * to tighten the due date for emergencies. On file, priority defaults to {@link #NORMAL}; safety/active-
 * threat sub-categories may be filed/escalated as {@link #EMERGENCY}.</p>
 *
 * <p>WHY a small fixed band (not a free integer): a bounded vocabulary keeps queue sorting and SLA rules
 * simple and consistent across responders (KISS, CLAUDE.md §3). Fine-grained ordering within a band is
 * by case age, not by an unbounded priority number.</p>
 */
public enum ReportPriority {

    /** Routine issue; the category's default SLA applies unchanged. */
    NORMAL,

    /** Elevated by a responder/moderator; queued ahead of normal work. */
    HIGH,

    /** Safety/active-threat (e.g. fallen live wire, GBV); inherits the tightest SLA in the category tree. */
    EMERGENCY
}
