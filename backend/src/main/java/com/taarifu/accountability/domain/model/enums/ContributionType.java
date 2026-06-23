package com.taarifu.accountability.domain.model.enums;

/**
 * The kind of parliamentary/representative contribution recorded for accountability (PRD §10 Epic M6,
 * US-6.1; EI-11).
 *
 * <p>Responsibility: classifies a {@code RepresentativeContribution} so citizens can filter a
 * representative's record by activity type ("show me their bills" / "show me their votes"). Stored as a
 * {@code @Enumerated(STRING)} so the wire/DB value is the stable name, never an ordinal (renaming or
 * reordering must never silently change persisted meaning — CLAUDE.md §8).</p>
 *
 * <p>WHY a closed enum (not free text): the set of parliamentary activity types is small and well-known,
 * and a fixed vocabulary lets the UI offer reliable filter chips and lets curated imports (EI-11) map a
 * Hansard feed deterministically. New types are an append-only change here + a matching CHECK-constraint
 * migration.</p>
 */
public enum ContributionType {

    /** A speech/intervention on the floor of the House. */
    SPEECH,

    /** A motion moved or co-sponsored by the representative. */
    MOTION,

    /** A bill introduced, sponsored, or co-sponsored. */
    BILL,

    /** A question put to a minister/the House (oral or written). */
    QUESTION,

    /** A recorded vote cast (for/against/abstain captured in the summary). */
    VOTE,

    /** Committee work (membership activity, report, hearing participation). */
    COMMITTEE
}
