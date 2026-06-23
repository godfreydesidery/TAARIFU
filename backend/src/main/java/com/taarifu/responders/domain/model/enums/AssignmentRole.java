package com.taarifu.responders.domain.model.enums;

/**
 * The role a responder plays on a multisectoral report (PRD §24.3, D21).
 *
 * <p>Responsibility: the core of the "one accountable owner + collaborators" model. A
 * {@link com.taarifu.responders.domain.model.Report} (referenced here by report id only — the
 * reporting module is built in parallel) carries exactly <b>one</b> {@link #OWNER}
 * {@link com.taarifu.responders.domain.model.ResponderAssignment} (accountable for closure) and
 * <b>zero-or-more</b> {@link #COLLABORATOR}s, each acting on its own slice (§24.3).</p>
 *
 * <p>WHY a strict single-owner invariant (enforced by a partial unique index in the migration, not
 * just by this enum): accountability is meaningless if two responders both believe they own closure;
 * the citizen must track <b>one</b> issue with an aggregated status (§24.3). The DB owns "at most one
 * OWNER per report" so the rule cannot be violated by a race (ARCHITECTURE.md §4.3).</p>
 */
public enum AssignmentRole {

    /**
     * The single accountable responder for the report's closure; aggregates child statuses if the
     * report is split into sub-cases (PRD §24.3). Exactly one per report (DB-enforced).
     */
    OWNER,

    /**
     * A responder acting on its own slice of a multisectoral report; sees the report's public content
     * + its assigned slice, but never another responder's internal notes (PRD §24.3). Zero-or-more.
     */
    COLLABORATOR
}
