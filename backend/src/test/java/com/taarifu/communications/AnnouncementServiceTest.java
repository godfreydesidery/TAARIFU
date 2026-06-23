package com.taarifu.communications;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.communications.api.event.AnnouncementPublished;
import com.taarifu.communications.application.service.AnnouncementService;
import com.taarifu.communications.domain.model.Announcement;
import com.taarifu.communications.domain.model.enums.AnnouncementStatus;
import com.taarifu.communications.domain.repository.AnnouncementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AnnouncementService} — the publish + moderation-hold invariants of M4
 * (PRD §12, US-4.1).
 *
 * <p>Responsibility: pins the load-bearing publish rules without a DB (Mockito only): a trusted author
 * publishes immediately and the {@link AnnouncementPublished} event fires; a new/untrusted author is
 * <b>held for moderation</b> (DRAFT + held) and <b>no</b> event fires; a future {@code publishAt} yields
 * {@code SCHEDULED}; an inverted schedule window is rejected. The held-author test fails if the
 * moderation gate is removed — the integrity guarantee the PRD demands for new authors.</p>
 */
class AnnouncementServiceTest {

    private AnnouncementRepository announcementRepository;
    private ApplicationEventPublisher events;
    private FixedClock clock;
    private AnnouncementService service;

    private final UUID author = UUID.randomUUID();
    private final UUID area = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        announcementRepository = mock(AnnouncementRepository.class);
        events = mock(ApplicationEventPublisher.class);
        clock = new FixedClock(Instant.parse("2026-06-23T12:00:00Z"));
        service = new AnnouncementService(announcementRepository, events, clock);
        when(announcementRepository.save(any(Announcement.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void trustedAuthor_publishesImmediately_andEmitsEvent() {
        Announcement a = service.publish(author, true, "Title", "Mwili", null,
                Set.of(area), null, null, Set.of("FEED", "PUSH"), null, null, null);

        assertThat(a.getStatus()).isEqualTo(AnnouncementStatus.PUBLISHED);
        assertThat(a.isModerationHeld()).isFalse();
        verify(events).publishEvent(any(AnnouncementPublished.class));
    }

    @Test
    void newAuthor_isHeldForModeration_andEmitsNoEvent() {
        Announcement a = service.publish(author, false, "Title", "Mwili", null,
                Set.of(area), null, null, Set.of("FEED"), null, null, null);

        // Held → stays DRAFT, flagged, NOT published (US-4.1). This is the integrity gate.
        assertThat(a.getStatus()).isEqualTo(AnnouncementStatus.DRAFT);
        assertThat(a.isModerationHeld()).isTrue();
        verify(events, never()).publishEvent(any());
    }

    @Test
    void futurePublishAt_yieldsScheduled() {
        Instant future = clock.now().plusSeconds(3600);
        Announcement a = service.publish(author, true, "Title", "Mwili", null,
                Set.of(area), null, null, Set.of("FEED"), null, future, future.plusSeconds(7200));

        assertThat(a.getStatus()).isEqualTo(AnnouncementStatus.SCHEDULED);
        // A scheduled (not-yet-live) announcement does not fan out yet.
        verify(events, never()).publishEvent(any());
    }

    @Test
    void invertedWindow_isRejected() {
        Instant publishAt = clock.now().plusSeconds(7200);
        Instant expireAt = clock.now().plusSeconds(3600); // before publish → invalid
        assertThatThrownBy(() -> service.publish(author, true, "Title", "Mwili", null,
                Set.of(area), null, null, Set.of("FEED"), null, publishAt, expireAt))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void unknownChannel_isRejected() {
        assertThatThrownBy(() -> service.publish(author, true, "Title", "Mwili", null,
                Set.of(area), null, null, Set.of("CARRIER_PIGEON"), null, null, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void approveAndPublish_clearsHoldAndEmitsEvent() {
        Announcement held = Announcement.draft(author, "T", "Mwili", null);
        held.targetAudience(Set.of(area), null, null,
                Set.of(com.taarifu.communications.domain.model.enums.Channel.FEED));
        held.hold();
        when(announcementRepository.findByPublicId(any())).thenReturn(java.util.Optional.of(held));

        Announcement out = service.approveAndPublish(UUID.randomUUID());

        assertThat(out.isModerationHeld()).isFalse();
        assertThat(out.getStatus()).isEqualTo(AnnouncementStatus.PUBLISHED);
        verify(events).publishEvent(any(AnnouncementPublished.class));
    }
}
