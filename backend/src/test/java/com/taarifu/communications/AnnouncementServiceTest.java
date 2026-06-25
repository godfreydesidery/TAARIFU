package com.taarifu.communications;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.common.outbox.OutboxWriter;
import com.taarifu.communications.api.event.AnnouncementPublished;
import com.taarifu.communications.application.service.AnnouncementService;
import com.taarifu.communications.domain.model.Announcement;
import com.taarifu.communications.domain.model.enums.AnnouncementStatus;
import com.taarifu.communications.domain.model.enums.Channel;
import com.taarifu.communications.domain.repository.AnnouncementRepository;
import com.taarifu.search.api.SearchIndexApi;
import com.taarifu.search.api.dto.SearchDocumentUpsert;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import com.taarifu.search.domain.model.enums.SearchVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
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
 * publishes immediately and the {@link AnnouncementPublished} event is <b>appended to the transactional
 * outbox</b> (ADR-0014) in the publish transaction; a new/untrusted author is <b>held for moderation</b>
 * (DRAFT + held) and <b>no</b> event is appended; a future {@code publishAt} yields {@code SCHEDULED} and
 * appends nothing; an inverted schedule window is rejected. The held-author test fails if the moderation
 * gate is removed — the integrity guarantee the PRD demands for new authors. The outbox-append assertions
 * pin the atomicity wiring: removing the {@code outboxWriter.append} fails them.</p>
 */
class AnnouncementServiceTest {

    private AnnouncementRepository announcementRepository;
    private OutboxWriter outboxWriter;
    private SearchIndexApi searchIndex;
    private FixedClock clock;
    private AnnouncementService service;

    private final UUID author = UUID.randomUUID();
    private final UUID area = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        announcementRepository = mock(AnnouncementRepository.class);
        outboxWriter = mock(OutboxWriter.class);
        searchIndex = mock(SearchIndexApi.class);
        clock = new FixedClock(Instant.parse("2026-06-23T12:00:00Z"));
        service = new AnnouncementService(announcementRepository, outboxWriter, clock, searchIndex);
        when(announcementRepository.save(any(Announcement.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void trustedAuthor_publishesImmediately_andAppendsEventToOutbox() {
        Announcement a = service.publish(author, true, "Title", "Mwili", null,
                Set.of(area), null, null, Set.of("FEED", "PUSH"), null, null, null);

        assertThat(a.getStatus()).isEqualTo(AnnouncementStatus.PUBLISHED);
        assertThat(a.isModerationHeld()).isFalse();
        // The event is appended to the outbox in the publish tx (ADR-0014 §5a), not raised in-process —
        // and it carries the right taxonomy key, aggregate, and a PII-free payload.
        ArgumentCaptor<EventEnvelope<?>> captor = envelopeCaptor();
        verify(outboxWriter).append(captor.capture());
        EventEnvelope<?> envelope = captor.getValue();
        assertThat(envelope.eventType()).isEqualTo(AnnouncementPublished.EVENT_TYPE);
        assertThat(envelope.aggregateType()).isEqualTo(AnnouncementPublished.AGGREGATE_TYPE);
        assertThat(envelope.aggregateId()).isEqualTo(a.getPublicId());
        assertThat(envelope.payload()).isInstanceOf(AnnouncementPublished.class);
        AnnouncementPublished payload = (AnnouncementPublished) envelope.payload();
        assertThat(payload.announcementId()).isEqualTo(a.getPublicId());
        assertThat(payload.authorProfileId()).isEqualTo(author);
        assertThat(payload.audienceAreaIds()).containsExactly(area);
    }

    @Test
    void trustedAuthor_publishes_indexesPublicSafeProjectionForDiscovery() {
        // A published announcement is pushed to search as a PUBLIC, public-safe projection (ADR-0017 §1):
        // title + localised snippet + area/category facets only — no full body beyond the snippet, no
        // moderationHeld flag, no attachments/schedule. visibility=PUBLIC (published civic data, PRD §22.6).
        Announcement a = service.publish(author, true, "Maji Tangazo",
                "Maji yatakatika kesho katika kata.", "Water will be cut tomorrow in the ward.",
                Set.of(area), null, null, Set.of("FEED"), null, null, null);

        ArgumentCaptor<SearchDocumentUpsert> captor = ArgumentCaptor.forClass(SearchDocumentUpsert.class);
        verify(searchIndex).upsert(captor.capture());
        verify(searchIndex, never()).remove(any(), any());
        SearchDocumentUpsert pushed = captor.getValue();
        assertThat(pushed.entityType()).isEqualTo(SearchEntityType.ANNOUNCEMENT);
        assertThat(pushed.entityPublicId()).isEqualTo(a.getPublicId());
        assertThat(pushed.title()).isEqualTo("Maji Tangazo");
        assertThat(pushed.snippetSw()).isEqualTo("Maji yatakatika kesho katika kata.");
        assertThat(pushed.snippetEn()).isEqualTo("Water will be cut tomorrow in the ward.");
        assertThat(pushed.areaId()).isEqualTo(area);
        assertThat(pushed.visibility()).isEqualTo(SearchVisibility.PUBLIC);
        // authoredByAccountId is the author (visibility-maintenance only); never the full body or a PII field.
        assertThat(pushed.authoredByAccountId()).isEqualTo(author);
    }

    @Test
    void newAuthor_isHeldForModeration_andAppendsNoEvent() {
        Announcement a = service.publish(author, false, "Title", "Mwili", null,
                Set.of(area), null, null, Set.of("FEED"), null, null, null);

        // Held → stays DRAFT, flagged, NOT published (US-4.1). This is the integrity gate.
        assertThat(a.getStatus()).isEqualTo(AnnouncementStatus.DRAFT);
        assertThat(a.isModerationHeld()).isTrue();
        verify(outboxWriter, never()).append(any());
        // A moderation-held draft must NEVER be discoverable — no upsert is pushed (the publish path that
        // would index is not taken because the held author short-circuits before publish()/indexForDiscovery).
        verify(searchIndex, never()).upsert(any());
    }

    @Test
    void futurePublishAt_yieldsScheduled() {
        Instant future = clock.now().plusSeconds(3600);
        Announcement a = service.publish(author, true, "Title", "Mwili", null,
                Set.of(area), null, null, Set.of("FEED"), null, future, future.plusSeconds(7200));

        assertThat(a.getStatus()).isEqualTo(AnnouncementStatus.SCHEDULED);
        // A scheduled (not-yet-live) announcement does not fan out yet.
        verify(outboxWriter, never()).append(any());
        // …and it is not yet publicly visible, so it must not be discoverable: the index call removes (not
        // upserts) any non-PUBLISHED state — idempotent on a never-indexed row (ADR-0017 §1).
        verify(searchIndex, never()).upsert(any());
        verify(searchIndex).remove(SearchEntityType.ANNOUNCEMENT, a.getPublicId());
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
        // Clearing the hold publishes → the event is appended to the outbox in the same tx (ADR-0014).
        ArgumentCaptor<EventEnvelope<?>> captor = envelopeCaptor();
        verify(outboxWriter).append(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(AnnouncementPublished.EVENT_TYPE);
        // The moderator-approved announcement is now public → it is indexed for discovery (ADR-0017 §1).
        ArgumentCaptor<SearchDocumentUpsert> indexCaptor = ArgumentCaptor.forClass(SearchDocumentUpsert.class);
        verify(searchIndex).upsert(indexCaptor.capture());
        assertThat(indexCaptor.getValue().entityType()).isEqualTo(SearchEntityType.ANNOUNCEMENT);
        assertThat(indexCaptor.getValue().visibility()).isEqualTo(SearchVisibility.PUBLIC);
    }

    @Test
    void expire_removesStaleDiscoveryRow_neverLingersAsPublic() {
        // FIX-2 (the wave-3 gap): an expired/unpublished announcement must leave the discovery index so it does
        // not linger as a stale PUBLIC search row. expire() transitions to EXPIRED and removes the projection.
        // This assertion FAILS if the searchIndex.remove call is dropped from the expiry path.
        Announcement published = published(null, null); // currently live/PUBLISHED
        when(announcementRepository.findByPublicId(any())).thenReturn(Optional.of(published));

        Announcement out = service.expire(UUID.randomUUID());

        assertThat(out.getStatus()).isEqualTo(AnnouncementStatus.EXPIRED);
        verify(searchIndex).remove(SearchEntityType.ANNOUNCEMENT, out.getPublicId());
        verify(searchIndex, never()).upsert(any());
        // Expiry is not a publish — no fan-out event is appended.
        verify(outboxWriter, never()).append(any());
    }

    /**
     * Builds a captor for {@link EventEnvelope} appends. The wildcard generic of {@code OutboxWriter.append}
     * lets {@code forClass} capture cleanly; confining it here keeps the assertions above readable.
     *
     * @return an {@link ArgumentCaptor} capturing the envelope passed to {@link OutboxWriter#append}.
     */
    private static ArgumentCaptor<EventEnvelope<?>> envelopeCaptor() {
        return ArgumentCaptor.forClass(EventEnvelope.class);
    }

    // --- getPublicDetail: the public citizen-readable visibility gate (PRD §22.6, SR-3) -------------------

    @Test
    void getPublicDetail_returnsLivePublishedAnnouncement() {
        Announcement live = published(null, null); // no window bounds → live
        when(announcementRepository.findByPublicId(any())).thenReturn(Optional.of(live));

        Announcement out = service.getPublicDetail(UUID.randomUUID());

        assertThat(out).isSameAs(live);
        assertThat(out.getStatus()).isEqualTo(AnnouncementStatus.PUBLISHED);
    }

    @Test
    void getPublicDetail_missingId_is404() {
        when(announcementRepository.findByPublicId(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPublicDetail(UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void getPublicDetail_draft_is404_neverLeaked() {
        // A DRAFT (e.g. moderation-held) must be indistinguishable from a missing id — the no-leak gate.
        Announcement draft = Announcement.draft(author, "Rasimu", "Mwili", null);
        draft.targetAudience(Set.of(area), null, null, Set.of(Channel.FEED));
        when(announcementRepository.findByPublicId(any())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.getPublicDetail(UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void getPublicDetail_notYetLive_is404() {
        // SCHEDULED-equivalent: PUBLISHED status but a future publishAt → not yet citizen-visible.
        Instant future = clock.now().plusSeconds(3600);
        Announcement notYet = published(future, future.plusSeconds(7200));
        when(announcementRepository.findByPublicId(any())).thenReturn(Optional.of(notYet));

        assertThatThrownBy(() -> service.getPublicDetail(UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void getPublicDetail_expired_is404() {
        // expireAt in the past → no longer an "active announcement" (SR-3) → hidden.
        Instant pastStart = clock.now().minusSeconds(7200);
        Announcement expired = published(pastStart, clock.now().minusSeconds(3600));
        when(announcementRepository.findByPublicId(any())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.getPublicDetail(UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void getPublicDetail_atExpiryBoundary_is404() {
        // expireAt == now is exclusive (window is [publishAt, expireAt)) → already hidden.
        Instant pastStart = clock.now().minusSeconds(3600);
        Announcement boundary = published(pastStart, clock.now());
        when(announcementRepository.findByPublicId(any())).thenReturn(Optional.of(boundary));

        assertThatThrownBy(() -> service.getPublicDetail(UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    /**
     * Builds a {@code PUBLISHED} announcement with the given window for the public-detail gate assertions.
     *
     * <p>The lifecycle status is forced to {@code PUBLISHED} <b>independently of the window</b> so that the
     * service's own visibility predicate — not the domain {@code publish()} transition — is what's under
     * test: {@code getPublicDetail} must hide a row that is {@code PUBLISHED} yet has a future
     * {@code publishAt} or a past {@code expireAt} on its own. We therefore drive the transition with an
     * instant at/after {@code publishAt} (so {@code publish()} chooses {@code PUBLISHED}) and only then set
     * the real window via {@code schedule()}.</p>
     *
     * @param publishAt the publish instant stored on the row (may be future to model not-yet-live), or
     *                  {@code null} for "immediately".
     * @param expireAt  the expiry instant, or {@code null} for no expiry.
     * @return a PUBLISHED announcement targeting {@code area} over {@code FEED}.
     */
    private Announcement published(Instant publishAt, Instant expireAt) {
        Announcement a = Announcement.draft(author, "Tangazo", "Mwili", "Body");
        a.targetAudience(Set.of(area), null, null, Set.of(Channel.FEED));
        // Transition to PUBLISHED using an instant that is not before publishAt, so publish() picks
        // PUBLISHED (not SCHEDULED) — the lifecycle is forced live regardless of the window we then set.
        Instant transitionAt = publishAt == null ? clock.now() : publishAt;
        a.schedule(publishAt, expireAt);
        a.publish(transitionAt);
        return a;
    }
}
