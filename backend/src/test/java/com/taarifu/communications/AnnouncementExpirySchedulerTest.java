package com.taarifu.communications;

import com.taarifu.communications.application.service.AnnouncementExpiryScheduler;
import com.taarifu.communications.application.service.AnnouncementService;
import com.taarifu.communications.domain.model.Announcement;
import com.taarifu.communications.domain.model.enums.AnnouncementStatus;
import com.taarifu.communications.domain.repository.AnnouncementRepository;
import com.taarifu.communications.infrastructure.config.AnnouncementExpiryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AnnouncementExpiryScheduler} — proves the sweep drives the due rows through the
 * single tested expiry path and degrades on a per-row failure, with no DB (Mockito only; CLAUDE.md §10).
 *
 * <p>Responsibility: pins the load-bearing sweep behaviour — it queries {@code PUBLISHED} rows due at the
 * {@code now − grace} cutoff, calls {@link AnnouncementService#expire} once per due row (the path that also
 * removes the search-index projection — ADR-0017 §1), counts the successes, and <b>isolates a per-row
 * failure</b> so one bad row never aborts the run (EI degradation). An empty due set is a clean no-op.</p>
 */
class AnnouncementExpirySchedulerTest {

    private AnnouncementRepository announcementRepository;
    private AnnouncementService announcementService;
    private FixedClock clock;
    private AnnouncementExpiryScheduler scheduler;

    private final Instant now = Instant.parse("2026-06-25T10:00:00Z");

    @BeforeEach
    void setUp() {
        announcementRepository = mock(AnnouncementRepository.class);
        announcementService = mock(AnnouncementService.class);
        clock = new FixedClock(now);
        // grace=0 → cutoff is exactly "now".
        AnnouncementExpiryProperties props = new AnnouncementExpiryProperties(true, null, null, Duration.ZERO);
        scheduler = new AnnouncementExpiryScheduler(announcementRepository, announcementService, props, clock);
    }

    /**
     * Builds a real {@link Announcement} carrying the given public id. The id is normally assigned on
     * persist ({@code @PrePersist}); here it is set via reflection so the sweep can resolve and pass it to
     * {@code expire(publicId)} without a DB. {@code getPublicId()} is final, so the entity cannot be mocked.
     */
    private Announcement dueAnnouncement(UUID publicId) {
        Announcement a = Announcement.draft(UUID.randomUUID(), "title", "habari", null);
        ReflectionTestUtils.setField(a, "publicId", publicId);
        return a;
    }

    @Test
    void sweep_expiresEveryDueRow_throughTheServicePath() {
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        when(announcementRepository.findDueForTransition(eq(AnnouncementStatus.PUBLISHED), eq(now)))
                .thenReturn(List.of(dueAnnouncement(a1), dueAnnouncement(a2)));

        int expired = scheduler.sweep(now);

        assertThat(expired).isEqualTo(2);
        // Each due row is expired via the single tested path (which also removes the search projection).
        verify(announcementService).expire(a1);
        verify(announcementService).expire(a2);
    }

    @Test
    void sweep_appliesGraceToTheCutoff() {
        AnnouncementExpiryProperties graced =
                new AnnouncementExpiryProperties(true, null, null, Duration.ofMinutes(5));
        AnnouncementExpiryScheduler s =
                new AnnouncementExpiryScheduler(announcementRepository, announcementService, graced, clock);
        when(announcementRepository.findDueForTransition(any(), any())).thenReturn(List.of());

        s.sweep(now);

        // The cutoff passed to the query is now − grace (5 minutes earlier).
        verify(announcementRepository)
                .findDueForTransition(AnnouncementStatus.PUBLISHED, now.minus(Duration.ofMinutes(5)));
    }

    @Test
    void sweep_isolatesPerRowFailure_andContinues() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        when(announcementRepository.findDueForTransition(any(), any()))
                .thenReturn(List.of(dueAnnouncement(bad), dueAnnouncement(good)));
        doThrow(new RuntimeException("boom")).when(announcementService).expire(bad);

        int expired = scheduler.sweep(now);

        // The bad row is skipped (not counted), the good row still expires — the run completes (EI degradation).
        assertThat(expired).isEqualTo(1);
        verify(announcementService).expire(bad);
        verify(announcementService).expire(good);
    }

    @Test
    void sweep_withNoDueRows_isANoOp() {
        when(announcementRepository.findDueForTransition(any(), any())).thenReturn(List.of());

        int expired = scheduler.sweep(now);

        assertThat(expired).isZero();
        verify(announcementService, never()).expire(any());
    }
}
