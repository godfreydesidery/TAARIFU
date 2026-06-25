package com.taarifu.communications;

import com.taarifu.communications.application.service.DigestService;
import com.taarifu.communications.application.service.NotificationDispatchService;
import com.taarifu.communications.domain.model.enums.Channel;
import com.taarifu.communications.domain.model.enums.NotificationType;
import com.taarifu.communications.domain.model.enums.SubscriptionTargetType;
import com.taarifu.communications.domain.repository.AnnouncementRepository;
import com.taarifu.communications.domain.repository.SubscriptionRepository;
import com.taarifu.communications.infrastructure.config.DigestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DigestService} — the scheduled area-activity digest (PRD §13 "digest", EI-6, M5).
 *
 * <p>Responsibility: pins the load-bearing digest invariants without a DB (Mockito only):</p>
 * <ul>
 *   <li>a recipient WITH in-window activity in a followed area gets exactly one {@code DIGEST} dispatch over
 *       FEED only (never SMS/PUSH — the digest is a lean, cost-free nudge);</li>
 *   <li>a recipient WITHOUT activity gets no dispatch (no empty nudges);</li>
 *   <li>the dispatch {@code sourceId} is STABLE for a window (idempotency anchor: a re-run for the same
 *       window feeds the dispatcher the same key, so its unique index makes the send happen once);</li>
 *   <li>a per-recipient failure is isolated — the run continues for the others (degrade, never crash);</li>
 *   <li>recipients are streamed across pages (a multi-page follower population is fully covered).</li>
 * </ul>
 */
class DigestServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-25T07:00:00Z");

    private SubscriptionRepository subscriptionRepository;
    private AnnouncementRepository announcementRepository;
    private NotificationDispatchService dispatchService;
    private FixedClock clock;
    private DigestService service;

    private final UUID active = UUID.randomUUID();
    private final UUID quiet = UUID.randomUUID();
    private final UUID area = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        announcementRepository = mock(AnnouncementRepository.class);
        dispatchService = mock(NotificationDispatchService.class);
        clock = new FixedClock(NOW);
        // enabled=true here only binds the record; the @ConditionalOnProperty gate is a Spring concern, not
        // exercised by a direct unit construction. lookback=1d, batch-size=2 to exercise paging.
        DigestProperties props = new DigestProperties(true, null, null, Duration.ofDays(1), 2);
        service = new DigestService(subscriptionRepository, announcementRepository, dispatchService, props, clock);
    }

    @Test
    void recipientWithActivity_getsOneFeedOnlyDigest() {
        onePageOfFollowers(List.of(active));
        when(subscriptionRepository.findFollowedAreaIds(active)).thenReturn(Set.of(area));
        when(announcementRepository.countAreaActivitySince(eq(Set.of(area)), any(), eq(NOW))).thenReturn(3L);

        int sent = service.runDigest(NOW);

        assertThat(sent).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<Channel>> channels = ArgumentCaptor.forClass(Set.class);
        verify(dispatchService).dispatch(eq(active), eq(NotificationType.DIGEST), channels.capture(),
                any(), any(), any(), any());
        // FEED only — a digest never charges SMS or fires an interruptive 7am push by default.
        assertThat(channels.getValue()).containsExactly(Channel.FEED);
    }

    @Test
    void recipientWithoutActivity_getsNoDigest() {
        onePageOfFollowers(List.of(quiet));
        when(subscriptionRepository.findFollowedAreaIds(quiet)).thenReturn(Set.of(area));
        when(announcementRepository.countAreaActivitySince(any(), any(), any())).thenReturn(0L);

        int sent = service.runDigest(NOW);

        assertThat(sent).isZero();
        verify(dispatchService, never()).dispatch(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void sourceIdIsStablePerWindow_soReRunIsIdempotent() {
        onePageOfFollowers(List.of(active));
        when(subscriptionRepository.findFollowedAreaIds(active)).thenReturn(Set.of(area));
        when(announcementRepository.countAreaActivitySince(any(), any(), any())).thenReturn(1L);

        service.runDigest(NOW);
        service.runDigest(NOW); // same window → same sourceId fed to the dispatcher.

        ArgumentCaptor<UUID> sourceId = ArgumentCaptor.forClass(UUID.class);
        verify(dispatchService, times(2)).dispatch(eq(active), eq(NotificationType.DIGEST), any(),
                any(), sourceId.capture(), any(), any());
        // Both runs use the identical window source id — the dispatcher's per-source key de-dupes the send.
        assertThat(sourceId.getAllValues().get(0)).isEqualTo(sourceId.getAllValues().get(1));
    }

    @Test
    void perRecipientFailure_isIsolated_runContinues() {
        onePageOfFollowers(List.of(active, quiet));
        when(subscriptionRepository.findFollowedAreaIds(active)).thenThrow(new RuntimeException("boom"));
        when(subscriptionRepository.findFollowedAreaIds(quiet)).thenReturn(Set.of(area));
        when(announcementRepository.countAreaActivitySince(any(), any(), any())).thenReturn(5L);

        int sent = service.runDigest(NOW);

        // The first recipient blew up; the run still delivered to the second (degrade, never crash a fan-out).
        assertThat(sent).isEqualTo(1);
        verify(dispatchService).dispatch(eq(quiet), eq(NotificationType.DIGEST), any(),
                any(), any(), any(), any());
    }

    @Test
    void streamsAcrossPages_coveringEveryFollower() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        // batch-size=2 → page 0 = [a,b] (hasNext), page 1 = [c] (last).
        when(subscriptionRepository.findDistinctFollowerProfileIds(eq(SubscriptionTargetType.AREA), any()))
                .thenAnswer(inv -> {
                    Pageable p = inv.getArgument(1);
                    if (p.getPageNumber() == 0) {
                        return new PageImpl<>(List.of(a, b), PageRequest.of(0, 2), 3);
                    }
                    return new PageImpl<>(List.of(c), PageRequest.of(1, 2), 3);
                });
        when(subscriptionRepository.findFollowedAreaIds(any())).thenReturn(Set.of(area));
        when(announcementRepository.countAreaActivitySince(any(), any(), any())).thenReturn(1L);

        int sent = service.runDigest(NOW);

        assertThat(sent).isEqualTo(3);
        verify(dispatchService).dispatch(eq(a), any(), any(), any(), any(), any(), any());
        verify(dispatchService).dispatch(eq(b), any(), any(), any(), any(), any(), any());
        verify(dispatchService).dispatch(eq(c), any(), any(), any(), any(), any(), any());
    }

    /** Stubs a single page of AREA-followers (last page) for the simple single-page cases. */
    private void onePageOfFollowers(List<UUID> followers) {
        Page<UUID> page = new PageImpl<>(followers, PageRequest.of(0, 2), followers.size());
        when(subscriptionRepository.findDistinctFollowerProfileIds(eq(SubscriptionTargetType.AREA), any()))
                .thenReturn(page);
    }
}
