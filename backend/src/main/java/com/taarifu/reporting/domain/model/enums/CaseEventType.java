package com.taarifu.reporting.domain.model.enums;

/**
 * The kind of entry on a report's append-only case timeline (PRD §10 US-3.2/US-3.4, M3).
 *
 * <p>Responsibility: discriminates the events that make up a {@code CaseEvent} timeline so clients can
 * render each appropriately (a status badge, a comment bubble, an attachment chip). The timeline is the
 * citizen-facing history (US-3.2 "timeline of events") <i>and</i> the operational record; each event
 * carries a {@code public}/{@code internal} flag so internal responder notes never leak to the
 * reporter/public (US-3.4 "public vs internal notes").</p>
 *
 * <p>WHY this is the module's own append-only log (distinct from the security {@code AuditEvent}): the
 * security audit store catalogues authn/authz/identity decisions only and is PII-free and hash-chained;
 * the case timeline is a <b>domain</b> record that may quote a citizen's comment text and is shown back
 * to them. They are deliberately separate stores with separate purposes (CLAUDE.md §3, §12).</p>
 */
public enum CaseEventType {

    /** A status transition occurred (from→to recorded in the event payload). */
    STATUS_CHANGE,

    /** The report was assigned/re-assigned to a responder scope (routing is DEFERRED; recorded when wired). */
    ASSIGNMENT,

    /** A free-text comment was added by the reporter or a responder (public or internal). */
    COMMENT,

    /** An attachment reference was added to the case. */
    ATTACHMENT,

    /** The case was escalated (SLA breach / manual) — pairs with a transition to {@code ESCALATED}. */
    ESCALATION
}
