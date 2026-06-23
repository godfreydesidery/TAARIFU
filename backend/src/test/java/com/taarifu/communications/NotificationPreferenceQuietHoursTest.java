package com.taarifu.communications;

import com.taarifu.communications.domain.model.NotificationPreference;
import com.taarifu.communications.domain.model.enums.Channel;
import com.taarifu.communications.domain.model.enums.NotificationType;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NotificationPreference#isQuietAt(LocalTime)} — the quiet-window arithmetic (M5).
 *
 * <p>Responsibility: pins the quiet-hours predicate, including the <b>midnight-wrapping</b> window that
 * is the common case for "do not disturb at night" (e.g. 22:00–06:00). A bug here would either spam a
 * citizen at night or silence them by day, so the boundaries are asserted explicitly.</p>
 */
class NotificationPreferenceQuietHoursTest {

    private NotificationPreference pref(LocalTime from, LocalTime to) {
        NotificationPreference p =
                NotificationPreference.of(UUID.randomUUID(), NotificationType.NEW_ANNOUNCEMENT, Channel.PUSH, true);
        p.update(true, from, to, "sw");
        return p;
    }

    @Test
    void noWindow_isNeverQuiet() {
        assertThat(pref(null, null).isQuietAt(LocalTime.NOON)).isFalse();
    }

    @Test
    void sameDayWindow_isQuietInsideOnly() {
        NotificationPreference p = pref(LocalTime.of(14, 0), LocalTime.of(16, 0));
        assertThat(p.isQuietAt(LocalTime.of(13, 59))).isFalse();
        assertThat(p.isQuietAt(LocalTime.of(14, 0))).isTrue();   // inclusive start
        assertThat(p.isQuietAt(LocalTime.of(15, 0))).isTrue();
        assertThat(p.isQuietAt(LocalTime.of(16, 0))).isFalse();  // exclusive end
    }

    @Test
    void midnightWrappingWindow_isQuietAcrossMidnight() {
        NotificationPreference p = pref(LocalTime.of(22, 0), LocalTime.of(6, 0));
        assertThat(p.isQuietAt(LocalTime.of(23, 30))).isTrue();  // before midnight
        assertThat(p.isQuietAt(LocalTime.of(2, 0))).isTrue();    // after midnight
        assertThat(p.isQuietAt(LocalTime.of(6, 0))).isFalse();   // exclusive end
        assertThat(p.isQuietAt(LocalTime.NOON)).isFalse();       // daytime
    }
}
