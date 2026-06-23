package com.taarifu.accountability.domain.model.enums;

/**
 * The lifecycle status of a representative's tracked {@code Promise} (PRD §10 Epic M6, US-6.3).
 *
 * <p>Responsibility: lets citizens see whether a public commitment has been honoured. Stored as a
 * {@code @Enumerated(STRING)} (stable name, never ordinal). The status is curated/evidence-backed
 * (D-Q4): a promise is moved to {@link #KEPT} or {@link #BROKEN} by an authorised author, optionally
 * linked to concrete projects ({@code linkedProjectIds}) and an {@code evidenceRef}.</p>
 *
 * <p>WHY {@code BROKEN} is an explicit terminal state (not just "not KEPT"): accountability requires the
 * platform to record a deliberate, evidence-backed judgement of failure, with provenance — neutrality
 * and fairness demand it be an authored decision, not an inferred default (PRD §10, election-period
 * neutrality).</p>
 */
public enum PromiseStatus {

    /** The promise has been made/captured but no progress is yet recorded. */
    MADE,

    /** Work toward the promise is under way (partial evidence). */
    IN_PROGRESS,

    /** The promise has been fulfilled (terminal; evidence expected). */
    KEPT,

    /** The promise was not fulfilled (terminal; evidence/justification expected). */
    BROKEN
}
