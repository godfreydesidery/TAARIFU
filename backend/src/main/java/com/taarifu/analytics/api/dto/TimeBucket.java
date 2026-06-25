package com.taarifu.analytics.api.dto;

/**
 * The granularity of a time-series (trend) aggregation — day / week / month
 * (PRD §3.3 trends; Appendix C dashboards; ADR-0020 §1; M15).
 *
 * <p>Responsibility: the controlled vocabulary of time buckets a trend endpoint accepts, each mapped to
 * exactly one PostgreSQL {@code date_trunc} field via {@link #truncField()}. The dashboards report volume
 * and SLA breaches <b>over time</b> (not just as a window total), which needs grouping by a truncated
 * {@code occurred_at}; this enum is that grouping key.</p>
 *
 * <p><b>WHY an enum bound to a fixed literal (security — ADR-0020 §1):</b> in the native trend queries the
 * bucket is the one part that is a SQL <i>identifier/field</i> inside {@code date_trunc(?, occurred_at)},
 * not a bind parameter. Accepting it as free-form caller text would be an injection seam. By restricting it
 * to this enum and returning a hard-coded {@code 'day'|'week'|'month'} literal from {@link #truncField()},
 * injection is structurally impossible — the caller can only pick one of three safe values. It is also
 * append-only: never rename a value (historical client links carry it).</p>
 */
public enum TimeBucket {

    /** Calendar-day buckets — {@code date_trunc('day', occurred_at)} (the default, finest grain). */
    DAY("day"),

    /** ISO-week buckets — {@code date_trunc('week', occurred_at)} (Postgres weeks start on Monday). */
    WEEK("week"),

    /** Calendar-month buckets — {@code date_trunc('month', occurred_at)}. */
    MONTH("month");

    /** The safe, fixed PostgreSQL {@code date_trunc} field literal for this bucket. */
    private final String truncField;

    /**
     * @param truncField the hard-coded {@code date_trunc} field literal (never caller-supplied).
     */
    TimeBucket(String truncField) {
        this.truncField = truncField;
    }

    /**
     * @return the safe, fixed {@code date_trunc} field literal ({@code "day"}/{@code "week"}/{@code "month"})
     *         — a constant chosen by the enum, never caller text, so it is safe to splice into native SQL.
     */
    public String truncField() {
        return truncField;
    }
}
