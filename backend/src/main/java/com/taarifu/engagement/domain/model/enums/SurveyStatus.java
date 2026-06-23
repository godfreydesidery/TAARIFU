package com.taarifu.engagement.domain.model.enums;

/**
 * Lifecycle state of a {@link com.taarifu.engagement.domain.model.Survey} (PRD §12.2 survey/poll
 * lifecycle).
 *
 * <p>Responsibility: the survey/poll state machine
 * {@code DRAFT → SCHEDULED → OPEN → CLOSED → ARCHIVED} (PRD §12.2). Only an {@code OPEN} survey
 * accepts responses; only an {@code OPEN}/{@code CLOSED} (non-draft) survey is publicly visible (drafts
 * excluded — PRD §22.6).</p>
 */
public enum SurveyStatus {

    /** Authoring; not yet scheduled. Not publicly visible. */
    DRAFT,

    /** Approved and scheduled with a future {@code startsAt}; not yet accepting responses. */
    SCHEDULED,

    /** Live within its {@code startsAt}/{@code endsAt} window; accepting responses. */
    OPEN,

    /** Window ended; no longer accepting responses; results may be shown per visibility rules. */
    CLOSED,

    /** Terminal: archived for the record. */
    ARCHIVED
}
