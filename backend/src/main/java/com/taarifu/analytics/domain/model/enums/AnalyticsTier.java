package com.taarifu.analytics.domain.model.enums;

/**
 * The trust tier (T0–T3) carried on an analytics event — the dimension behind the verification funnel
 * (PRD §7.3 tiers; Appendix E.0 envelope {@code trust_tier}; §3.3 Verification ≥40% ID-verified).
 *
 * <p>Responsibility: a coarse, non-identifying segment on each
 * {@link com.taarifu.analytics.domain.model.AnalyticsEvent} so the dashboards can express the
 * T0→T3 funnel and segment any metric by trust level. It is a <b>tier label, never an identity</b> —
 * it cannot re-identify a person, consistent with the no-PII analytics rule (Appendix E.4).</p>
 *
 * <p>WHY a dedicated analytics enum (not a reach-around into identity's tier type): the analytics
 * module must not import another module's domain (ADR-0013); the caller passes the tier as this
 * module's own enum across the {@code api} boundary.</p>
 */
public enum AnalyticsTier {

    /** T0 — guest / unverified (no account or pre-auth). */
    T0,

    /** T1 — phone/OTP verified account. */
    T1,

    /** T2 — completed profile + primary location. */
    T2,

    /** T3 — NIDA/voter-ID verified (electoral-authoritative for binding actions). */
    T3
}
