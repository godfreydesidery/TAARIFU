package com.taarifu.communications.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.common.outbox.DomainEventHandler;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.communications.api.event.AnnouncementPublished;
import com.taarifu.communications.domain.model.enums.Channel;
import com.taarifu.communications.domain.model.enums.NotificationType;
import com.taarifu.communications.domain.model.enums.SubscriptionTargetType;
import com.taarifu.communications.domain.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Outbox {@link DomainEventHandler} that fans an {@link AnnouncementPublished} event out to followers'
 * feeds and notifications (PRD §13/§16, M5; ADR-0014 §5a).
 *
 * <p>Responsibility: the asynchronous consumer the {@code OutboxRelay} delivers to when an announcement
 * goes live. It (1) resolves the recipient set — the profiles following any of the announcement's audience
 * areas or its tagged category — and (2) for each recipient, asks {@link NotificationDispatchService} to
 * queue+send per the recipient's preferences and the channel matrix (PRD §13). FEED is always retained;
 * PUSH degrades to SMS (EI-5). The feed itself is a <i>pull</i> read (assembled by {@link FeedQueryService}
 * from the announcement table + the caller's follows), so this handler does not materialise per-user feed
 * rows — it only drives the push/SMS/email side and the per-recipient FEED notification.</p>
 *
 * <p>WHY a {@link DomainEventHandler} (not the former {@code @TransactionalEventListener(AFTER_COMMIT)}):
 * ADR-0014 replaced the in-process {@code ApplicationEventPublisher} hop with the durable transactional
 * outbox. The publish service now {@code OutboxWriter.append}s the event in the publish transaction; the
 * relay later delivers it here. This buys crash-safety + retry/DLQ the after-commit listener lacked: an
 * announcement that committed can never lose its fan-out, even if the JVM dies right after the publish
 * (PRD §15 DI3). The trade is up-to-one-poll-interval latency and at-least-once delivery — hence the
 * idempotency note below.</p>
 *
 * <p><b>Idempotency</b> (the at-least-once handler contract, ADR-0014 §3): the relay may redeliver the same
 * {@code eventId}. This handler is idempotent <b>by construction</b> because
 * {@link NotificationDispatchService#dispatch} keys every queued row on
 * {@code type:channel:recipient:sourceId} where {@code sourceId} is the (stable) announcement id — a
 * redelivered event recomputes the identical keys, finds the existing rows, and does <b>not</b> re-create
 * or re-send them (DI4). No per-event dedup table is needed: the existing unique
 * {@code (idempotency_key)} index on {@code notification} is the hard backstop.</p>
 *
 * <p><b>🔒 Boundary/PII (ADR-0013):</b> the payload carries ids/codes only; the handler never reads PII from
 * it and never calls back synchronously into the producing path — recipient resolution uses this module's
 * own {@code subscription} table, and channel delivery goes out through this module's ports.</p>
 *
 * <p>WHY the recipient set is de-duplicated: a citizen who follows both a targeted area and the tagged
 * category must receive exactly one notification per channel, not two. The dispatcher's idempotency key is
 * the hard backstop, but de-duping here avoids the wasted work (DI4).</p>
 */
@Component
public class AnnouncementPublishedHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementPublishedHandler.class);

    private final SubscriptionRepository subscriptionRepository;
    private final NotificationDispatchService dispatchService;
    private final ObjectMapper objectMapper;

    /**
     * @param subscriptionRepository follower resolution (this module's own table).
     * @param dispatchService        preference-aware, idempotent per-recipient dispatch.
     * @param objectMapper           shared Jackson mapper; deserialises the relay's {@code JsonNode}
     *                               payload back into the typed {@link AnnouncementPublished} record (the
     *                               relay is payload-agnostic and hands handlers a tree — ADR-0014 §3).
     */
    public AnnouncementPublishedHandler(SubscriptionRepository subscriptionRepository,
                                        NotificationDispatchService dispatchService,
                                        ObjectMapper objectMapper) {
        this.subscriptionRepository = subscriptionRepository;
        this.dispatchService = dispatchService;
        this.objectMapper = objectMapper;
    }

    /**
     * {@inheritDoc}
     *
     * @return the single taxonomy key {@link AnnouncementPublished#EVENT_TYPE} this handler consumes.
     */
    @Override
    public Set<String> handledEventTypes() {
        return Set.of(AnnouncementPublished.EVENT_TYPE);
    }

    /**
     * Handles a delivered published-announcement event: deserialise the payload, resolve followers, and
     * dispatch notifications idempotently.
     *
     * <p>The relay delivers the payload as a Jackson tree (it does not know the concrete record type), so we
     * convert it back to {@link AnnouncementPublished} via the shared mapper. A malformed payload surfaces
     * as a {@link RuntimeException}, which the relay treats as a dispatch failure (retry → DLQ) — the event
     * is never silently dropped.</p>
     *
     * @param event the delivered envelope; its {@code eventId} is the at-least-once idempotency key (the
     *              effect is made idempotent by the dispatcher's per-source notification keys — see type doc).
     */
    @Override
    public void handle(EventEnvelope<?> event) {
        AnnouncementPublished published = objectMapper.convertValue(event.payload(), AnnouncementPublished.class);

        Set<UUID> recipients = resolveRecipients(published);
        if (recipients.isEmpty()) {
            log.debug("Announcement {} published with no followers to notify", published.announcementId());
            return;
        }
        Set<Channel> channels = parseChannels(published.channels());
        for (UUID recipient : recipients) {
            // Per-recipient idempotent dispatch; FEED always retained, PUSH→SMS fallback (EI-5). The
            // dispatcher keys on the announcement id (sourceId), so a redelivered event never double-sends.
            // Title/body are placeholder refs here — the dispatcher's adapter renders the localised template
            // from the source by payloadRef. TODO(wiring): pass rendered SW/EN title/body once the i18n
            // notification-template service exists; payloadRef carries the announcement id.
            dispatchService.dispatch(recipient, NotificationType.NEW_ANNOUNCEMENT, channels,
                    published.announcementId().toString(), published.announcementId(),
                    null, null);
        }
        log.info("Announcement {} fanned out to {} follower(s)",
                published.announcementId(), recipients.size());
    }

    /** Resolves the de-duplicated set of profiles following any targeted area or the tagged category. */
    private Set<UUID> resolveRecipients(AnnouncementPublished event) {
        Set<UUID> recipients = new LinkedHashSet<>();
        if (event.audienceAreaIds() != null && !event.audienceAreaIds().isEmpty()) {
            recipients.addAll(subscriptionRepository.findFollowerProfileIds(
                    SubscriptionTargetType.AREA, event.audienceAreaIds()));
        }
        if (event.categoryId() != null) {
            recipients.addAll(subscriptionRepository.findFollowerProfileIds(
                    SubscriptionTargetType.CATEGORY, Set.of(event.categoryId())));
        }
        // The author never notifies themselves about their own announcement (no self-notification).
        recipients.remove(event.authorProfileId());
        return recipients;
    }

    /** Parses the event's channel-name strings into the enum set (unknown names skipped defensively). */
    private Set<Channel> parseChannels(Set<String> names) {
        Set<Channel> channels = EnumSet.noneOf(Channel.class);
        if (names != null) {
            for (String name : names) {
                try {
                    channels.add(Channel.valueOf(name));
                } catch (IllegalArgumentException ignored) {
                    // Defensive: an unknown channel name in the event is skipped, not fatal.
                }
            }
        }
        // FEED is the durable default even if the author omitted it — an item is never lost (EI-5).
        channels.add(Channel.FEED);
        return channels;
    }
}
