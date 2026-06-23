package com.taarifu.analytics.domain.model.enums;

/**
 * The delivery/origin channel an analytics event was recorded through — the dimension behind the
 * "channel mix" dashboard (PRD Appendix E.0 envelope {@code channel}; Appendix C, §3.3 Reach).
 *
 * <p>Responsibility: a low-cardinality dimension on every {@link com.taarifu.analytics.domain.model.AnalyticsEvent}
 * so dashboards can compute "% sessions via USSD/SMS" and the feature-phone reach KPIs that are
 * first-class for an inclusive, low-connectivity audience (PRD §15). Stored as a {@code VARCHAR} enum
 * name so {@code ddl-auto=validate} matches exactly.</p>
 *
 * <p>WHY {@link #UNKNOWN} exists: an event recorded by a server-side worker that cannot attribute a
 * channel must still be counted (data minimisation never blocks a metric); it lands as {@code UNKNOWN}
 * rather than being dropped, keeping totals honest.</p>
 */
public enum AnalyticsChannel {

    /** Native mobile app (Flutter citizen app). */
    APP,

    /** Desktop/mobile web. */
    WEB,

    /** Progressive web app / offline-capable web client. */
    PWA,

    /** USSD session (feature-phone reach — PRD §14, §15). */
    USSD,

    /** SMS keyword/fallback flow. */
    SMS,

    /** Admin/console-originated action. */
    ADMIN,

    /** Server-to-server / programmatic API. */
    API,

    /** Channel could not be attributed (still counted, never dropped). */
    UNKNOWN
}
