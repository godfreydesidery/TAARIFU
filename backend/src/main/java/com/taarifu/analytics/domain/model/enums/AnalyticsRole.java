package com.taarifu.analytics.domain.model.enums;

/**
 * The "active hat" (§6.4 multi-role context) under which an analytics event occurred — the dimension
 * behind role-segmented dashboards (PRD Appendix E.0 envelope {@code active_role}; §6.4).
 *
 * <p>Responsibility: a low-cardinality role dimension on each
 * {@link com.taarifu.analytics.domain.model.AnalyticsEvent} so e.g. moderation-action counts can be
 * split by actor role, or engagement segmented by whether the actor acted as a CITIZEN vs a
 * REPRESENTATIVE. It mirrors the role catalogue (identity's {@code RoleName}) but is the analytics
 * module's <b>own</b> enum so the module imports no sibling domain (ADR-0013).</p>
 *
 * <p>WHY {@link #SYSTEM} exists: server-side workers (e.g. an SLA-clock breach, an auto-route) record
 * events with no human actor; they are attributed to {@code SYSTEM} so the row is still counted.</p>
 */
public enum AnalyticsRole {

    /** Acting as an unauthenticated visitor / guest. */
    GUEST,

    /** Acting as a citizen (the base hat). */
    CITIZEN,

    /** Acting as an organisation member/admin. */
    ORGANIZATION,

    /** Acting as an elected representative. */
    REPRESENTATIVE,

    /** Acting as a responder staff agent/admin (govt/parastatal/private, D20). */
    RESPONDER,

    /** Acting as a content/safety moderator. */
    MODERATOR,

    /** Acting as a platform administrator. */
    ADMIN,

    /** Acting as super-administrator (root). */
    ROOT,

    /** A server-side worker with no human actor (e.g. SLA breach, auto-route). */
    SYSTEM
}
