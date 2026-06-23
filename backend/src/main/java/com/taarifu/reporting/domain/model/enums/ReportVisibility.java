package com.taarifu.reporting.domain.model.enums;

/**
 * Whether a report is publicly discoverable or restricted to the reporter + handling responders
 * (PRD §10 US-3.7, §25.3, Appendix D.4; D-Q1).
 *
 * <p>Responsibility: the privacy axis of a report. A {@link #PUBLIC} report may appear in the public
 * near-me list/map and be upvoted/followed; a {@link #PRIVATE} report never does and is never indexed
 * for public search.</p>
 *
 * <p>WHY this is a hard server-side gate, not a UI preference: sensitive-category reports (GBV,
 * corruption, and partial sub-cases) are <b>forced PRIVATE</b> regardless of what the client requests
 * (Appendix D.4, D-Q1) — exposing a GBV report publicly could endanger a victim. The category's
 * {@code sensitive}/{@code forcePrivate} flags override the citizen's choice in the file-report service;
 * this enum is only the resulting value.</p>
 */
public enum ReportVisibility {

    /** Discoverable in public lists/map; upvotable/followable; indexed for public search. */
    PUBLIC,

    /** Visible only to the reporter and the authorised handling responders; never public (PRD §25.3). */
    PRIVATE
}
