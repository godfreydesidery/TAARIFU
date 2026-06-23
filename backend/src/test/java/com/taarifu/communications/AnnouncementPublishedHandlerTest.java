package com.taarifu.communications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.communications.api.event.AnnouncementPublished;
import com.taarifu.communications.application.service.AnnouncementPublishedHandler;
import com.taarifu.communications.application.service.NotificationDispatchService;
import com.taarifu.communications.domain.model.enums.Channel;
import com.taarifu.communications.domain.model.enums.NotificationType;
import com.taarifu.communications.domain.model.enums.SubscriptionTargetType;
import com.taarifu.communications.domain.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AnnouncementPublishedHandler} — the outbox fan-out consumer (ADR-0014 §5a, PRD §13).
 *
 * <p>Responsibility: prove the handler does what the relay needs of it, without a DB (Mockito only):</p>
 * <ul>
 *   <li>it registers on exactly {@link AnnouncementPublished#EVENT_TYPE} (the dispatcher routes by it);</li>
 *   <li>it deserialises the relay's {@code JsonNode} payload back into {@link AnnouncementPublished} and
 *       dispatches to the resolved followers (the relay delivers a tree, not the typed record);</li>
 *   <li>it dispatches with the announcement id as the dispatcher's {@code sourceId}, so the effect is
 *       <b>idempotent</b> across a redelivery (the at-least-once contract) — a second delivery uses the
 *       same idempotency-key inputs and the dispatcher (its own unique index) does not double-send;</li>
 *   <li>it never notifies the author about their own announcement (no self-notification);</li>
 *   <li>no followers ⇒ no dispatch (cheap no-op).</li>
 * </ul>
 *
 * <p>The handler's idempotency rests on {@link NotificationDispatchService}'s per-source key (proven in
 * {@link NotificationDispatchServiceTest}); here we assert the handler always feeds it the <b>same</b>
 * stable inputs on redelivery, which is the handler's half of the contract.</p>
 */
class AnnouncementPublishedHandlerTest {

    private static final Instant PUBLISHED_AT = Instant.parse("2026-06-23T12:00:00Z");

    /** A real mapper — the handler deserialises the JsonNode the relay hands it, exactly as in production. */
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    private SubscriptionRepository subscriptionRepository;
    private NotificationDispatchService dispatchService;
    private AnnouncementPublishedHandler handler;

    private final UUID announcementId = UUID.randomUUID();
    private final UUID author = UUID.randomUUID();
    private final UUID area = UUID.randomUUID();
    private final UUID follower = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        dispatchService = mock(NotificationDispatchService.class);
        handler = new AnnouncementPublishedHandler(subscriptionRepository, dispatchService, objectMapper);
    }

    @Test
    void registersOnAnnouncementPublishedEventType() {
        assertThat(handler.handledEventTypes()).containsExactly(AnnouncementPublished.EVENT_TYPE);
    }

    @Test
    void deserialisesJsonNodePayload_andDispatchesToAreaFollowers() {
        when(subscriptionRepository.findFollowerProfileIds(SubscriptionTargetType.AREA, Set.of(area)))
                .thenReturn(List.of(follower));

        handler.handle(envelopeAsRelayDelivers(payload(Set.of(area), null, Set.of("FEED", "PUSH"))));

        // The announcement id is the dispatcher's sourceId → the idempotency anchor on redelivery.
        verify(dispatchService).dispatch(eq(follower), eq(NotificationType.NEW_ANNOUNCEMENT),
                any(), eq(announcementId.toString()), eq(announcementId), any(), any());
    }

    @Test
    void redelivery_dispatchesWithSameStableInputs_soEffectIsIdempotent() {
        when(subscriptionRepository.findFollowerProfileIds(SubscriptionTargetType.AREA, Set.of(area)))
                .thenReturn(List.of(follower));
        EventEnvelope<JsonNode> envelope = envelopeAsRelayDelivers(payload(Set.of(area), null, Set.of("FEED")));

        // At-least-once: the same envelope is delivered twice (the relay crash window).
        handler.handle(envelope);
        handler.handle(envelope);

        // The handler feeds the dispatcher the SAME (recipient, type, sourceId=announcementId) both times;
        // the dispatcher's per-source idempotency key (its unique index) makes the actual send happen once.
        verify(dispatchService, times(2)).dispatch(eq(follower), eq(NotificationType.NEW_ANNOUNCEMENT),
                any(), eq(announcementId.toString()), eq(announcementId), any(), any());
    }

    @Test
    void unionsAreaAndCategoryFollowers_andNeverNotifiesTheAuthor() {
        UUID category = UUID.randomUUID();
        UUID categoryFollower = UUID.randomUUID();
        when(subscriptionRepository.findFollowerProfileIds(SubscriptionTargetType.AREA, Set.of(area)))
                // The author follows their own area — must be filtered out (no self-notification).
                .thenReturn(List.of(follower, author));
        when(subscriptionRepository.findFollowerProfileIds(SubscriptionTargetType.CATEGORY, Set.of(category)))
                .thenReturn(List.of(categoryFollower));

        handler.handle(envelopeAsRelayDelivers(payload(Set.of(area), category, Set.of("FEED"))));

        verify(dispatchService).dispatch(eq(follower), any(), any(), any(), any(), any(), any());
        verify(dispatchService).dispatch(eq(categoryFollower), any(), any(), any(), any(), any(), any());
        // The author is never dispatched to, even though they follow the targeted area.
        verify(dispatchService, never()).dispatch(eq(author), any(), any(), any(), any(), any(), any());
    }

    @Test
    void feedChannelIsAlwaysIncluded_evenIfAuthorOmittedIt() {
        when(subscriptionRepository.findFollowerProfileIds(SubscriptionTargetType.AREA, Set.of(area)))
                .thenReturn(List.of(follower));

        // Author selected only PUSH — FEED must still be added (an item is never lost, EI-5).
        handler.handle(envelopeAsRelayDelivers(payload(Set.of(area), null, Set.of("PUSH"))));

        @SuppressWarnings("unchecked")
        var channelsCaptor = org.mockito.ArgumentCaptor.forClass(Set.class);
        verify(dispatchService).dispatch(eq(follower), any(), channelsCaptor.capture(),
                any(), any(), any(), any());
        assertThat((Set<Channel>) channelsCaptor.getValue()).contains(Channel.FEED, Channel.PUSH);
    }

    @Test
    void noFollowers_isACheapNoOp() {
        when(subscriptionRepository.findFollowerProfileIds(any(), any())).thenReturn(List.of());

        handler.handle(envelopeAsRelayDelivers(payload(Set.of(area), null, Set.of("FEED"))));

        verifyNoInteractions(dispatchService);
    }

    /** Builds the typed event payload for a published announcement. */
    private AnnouncementPublished payload(Set<UUID> areaIds, UUID categoryId, Set<String> channels) {
        return new AnnouncementPublished(announcementId, author, areaIds, categoryId, null, channels, PUBLISHED_AT);
    }

    /**
     * Wraps the payload exactly as the {@code OutboxRelay} delivers it: the payload is round-tripped through
     * JSON to a {@link JsonNode} (the relay re-reads the stored {@code jsonb} as a tree, not the record),
     * so this exercises the handler's deserialisation path, not a shortcut.
     */
    private EventEnvelope<JsonNode> envelopeAsRelayDelivers(AnnouncementPublished payload) {
        JsonNode tree = objectMapper.valueToTree(payload);
        return new EventEnvelope<>(UUID.randomUUID(), AnnouncementPublished.EVENT_TYPE,
                AnnouncementPublished.AGGREGATE_TYPE, announcementId, tree, PUBLISHED_AT);
    }
}
