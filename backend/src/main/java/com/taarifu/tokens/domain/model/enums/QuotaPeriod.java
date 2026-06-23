package com.taarifu.tokens.domain.model.enums;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/**
 * The recurrence period of a free quota allowance (PRD §23.1 — "recurring, role-based allowance e.g.
 * daily/weekly/monthly that refreshes automatically").
 *
 * <p>Responsibility: defines the calendar window over which a metered action's free uses are counted,
 * and computes the deterministic UTC start-instant of the window containing a given instant. The metering
 * service keys per-wallet quota consumption by this window start, so the allowance "refreshes" simply by
 * the window rolling over — no scheduled job is required (the next action in a new window starts a fresh
 * counter).</p>
 *
 * <p>WHY the window start is computed in <b>UTC</b> and stored, rather than tracking a "resets-at" timer:
 * a stored window-start key is idempotent and race-safe under concurrent spends (two requests in the same
 * window resolve to the same key and contend on one counter row), and it never drifts. Local-time refresh
 * (e.g. "midnight in Dar es Salaam") is a later refinement; UTC keeps MVP correctness simple and uniform
 * (KISS, CLAUDE.md §3).</p>
 */
public enum QuotaPeriod {

    /** No recurrence — a single lifetime allowance (window start is the epoch). */
    LIFETIME,

    /** Resets at the start of each UTC day. */
    DAILY,

    /** Resets at the start of each ISO week (Monday) in UTC. */
    WEEKLY,

    /** Resets at the start of each calendar month in UTC. */
    MONTHLY;

    /**
     * Computes the inclusive UTC start instant of the quota window that contains {@code at}.
     *
     * <p>WHY this is the quota's identity: per-wallet free-quota consumption is recorded against this
     * window start (see {@code WalletFreeQuotaState.windowStart}); equal starts mean "same window",
     * so the counter is shared and the allowance is honoured exactly once per window.</p>
     *
     * @param at the instant being metered (typically "now" from the clock port).
     * @return the UTC start of the containing window; {@link Instant#EPOCH} for {@link #LIFETIME}.
     */
    public Instant windowStart(Instant at) {
        var utc = at.atZone(ZoneOffset.UTC);
        return switch (this) {
            case LIFETIME -> Instant.EPOCH;
            case DAILY -> utc.truncatedTo(ChronoUnit.DAYS).toInstant();
            case WEEKLY -> utc.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .truncatedTo(ChronoUnit.DAYS).toInstant();
            case MONTHLY -> utc.with(TemporalAdjusters.firstDayOfMonth())
                    .truncatedTo(ChronoUnit.DAYS).toInstant();
        };
    }
}
