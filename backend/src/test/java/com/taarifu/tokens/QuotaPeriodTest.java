package com.taarifu.tokens;

import com.taarifu.tokens.domain.model.enums.QuotaPeriod;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link QuotaPeriod#windowStart(Instant)} — the deterministic UTC window key that makes the
 * free-quota allowance refresh by rolling over (PRD §23.1). No Spring context / Docker needed.
 *
 * <p>Responsibility: proves two instants in the same window resolve to the same key (so they share one
 * counter and the allowance is honoured exactly once), and an instant in the next window resolves to a later
 * key (the automatic refresh). Correct windowing is what guarantees no citizen is over- or under-charged on
 * the free path.</p>
 */
class QuotaPeriodTest {

    // 2026-06-23T14:30:00Z is a Tuesday (used to assert ISO-week starts on Monday).
    private final Instant tuesdayAfternoon = Instant.parse("2026-06-23T14:30:00Z");

    @Test
    void daily_windowStartsAtUtcMidnight_andIsStableWithinTheDay() {
        Instant sameDayLater = Instant.parse("2026-06-23T23:59:59Z");
        assertThat(QuotaPeriod.DAILY.windowStart(tuesdayAfternoon))
                .isEqualTo(Instant.parse("2026-06-23T00:00:00Z"))
                .isEqualTo(QuotaPeriod.DAILY.windowStart(sameDayLater));
    }

    @Test
    void daily_nextDayIsANewWindow() {
        Instant nextDay = Instant.parse("2026-06-24T00:00:01Z");
        assertThat(QuotaPeriod.DAILY.windowStart(nextDay))
                .isAfter(QuotaPeriod.DAILY.windowStart(tuesdayAfternoon))
                .isEqualTo(Instant.parse("2026-06-24T00:00:00Z"));
    }

    @Test
    void weekly_windowStartsOnIsoMonday() {
        // The Monday of the week containing Tue 2026-06-23 is 2026-06-22.
        assertThat(QuotaPeriod.WEEKLY.windowStart(tuesdayAfternoon))
                .isEqualTo(Instant.parse("2026-06-22T00:00:00Z"));
    }

    @Test
    void monthly_windowStartsOnFirstOfMonth() {
        assertThat(QuotaPeriod.MONTHLY.windowStart(tuesdayAfternoon))
                .isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
    }

    @Test
    void lifetime_windowIsAlwaysTheEpoch() {
        assertThat(QuotaPeriod.LIFETIME.windowStart(tuesdayAfternoon)).isEqualTo(Instant.EPOCH);
        assertThat(QuotaPeriod.LIFETIME.windowStart(Instant.parse("2030-01-01T00:00:00Z")))
                .isEqualTo(Instant.EPOCH);
    }
}
