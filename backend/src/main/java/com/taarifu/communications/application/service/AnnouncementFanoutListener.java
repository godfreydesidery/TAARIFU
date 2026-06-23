package com.taarifu.communications.application.service;

import com.taarifu.communications.api.event.AnnouncementPublished;
import com.taarifu.communications.domain.model.enums.Channel;
import com.taarifu.communications.domain.model.enums.NotificationType;
import com.taarifu.communications.domain.model.enums.SubscriptionTargetType;
import com.taarifu.communications.domain.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Fans an {@link AnnouncementPublished} event out to followers' feeds and notifications (PRD §13/§16, M5).
 *
 * <p>Responsibility: the asynchronous consumer that, when an announcement goes live, (1) resolves the
 * recipient set — the profiles following any of the announcement's audience areas or its tagged category
 * — and (2) for each recipient, asks {@link NotificationDispatchService} to queue+send per the
 * recipient's preferences and the channel matrix (PRD §13). FEED is always retained; PUSH degrades to SMS
 * (EI-5). The feed itself is a <i>pull</i> read (assembled by {@link FeedQueryService} from the
 * announcement table + the caller's follows), so this listener does not materialise per-user feed rows —
 * it only drives the push/SMS/email side and the per-recipient FEED notification.</p>
 *
 * <p>WHY {@code @TransactionalEventListener(AFTER_COMMIT)}: fan-out must run only once the announcement is
 * durably committed — never on a rolled-back publish (DI3). Running after commit also keeps the author's
 * publish response fast (PRD §15); the heavy fan-out happens off the response's critical section. This is
 * the in-process MVP transport (ARCHITECTURE §8); TODO(wiring): when the transactional outbox + bus land,
 * this consumer moves behind the relay unchanged (it already depends only on the public event record).</p>
 *
 * <p>WHY the recipient set is de-duplicated: a citizen who follows both a targeted area and the tagged
 * category must receive exactly one notification per channel, not two. The dispatcher's idempotency key
 * is the hard backstop, but de-duping here avoids the wasted work (DI4).</p>
 */
@Component
public class AnnouncementFanoutListener {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementFanoutListener.class);

    private final SubscriptionRepository subscriptionRepository;
    private final NotificationDispatchService dispatchService;

    /**
     * @param subscriptionRepository follower resolution (this module's own table).
     * @param dispatchService        preference-aware per-recipient dispatch.
     */
    public AnnouncementFanoutListener(SubscriptionRepository subscriptionRepository,
                                      NotificationDispatchService dispatchService) {
        this.subscriptionRepository = subscriptionRepository;
        this.dispatchService = dispatchService;
    }

    /**
     * Handles a published announcement: resolve followers, dispatch notifications.
     *
     * @param event the published-announcement event (public ids + audience + channels).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAnnouncementPublished(AnnouncementPublished event) {
        Set<UUID> recipients = resolveRecipients(event);
        if (recipients.isEmpty()) {
            log.debug("Announcement {} published with no followers to notify", event.announcementId());
            return;
        }
        Set<Channel> channels = parseChannels(event.channels());
        for (UUID recipient : recipients) {
            // Per-recipient idempotent dispatch; FEED always retained, PUSH→SMS fallback (EI-5).
            // Title/body are placeholder refs here — the dispatcher's adapter renders the localised
            // template from the source by payloadRef. TODO(wiring): pass rendered SW/EN title/body once
            // the i18n notification-template service exists; payloadRef carries the announcement id.
            dispatchService.dispatch(recipient, NotificationType.NEW_ANNOUNCEMENT, channels,
                    event.announcementId().toString(), event.announcementId(),
                    null, null);
        }
        log.info("Announcement {} fanned out to {} follower(s)", event.announcementId(), recipients.size());
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
        if (names == null) {
            return channels;
        }
        for (String name : names) {
            try {
                channels.add(Channel.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // Defensive: an unknown channel name in the event is skipped, not fatal.
            }
        }
        // FEED is the durable default even if the author omitted it — an item is never lost (EI-5).
        channels.add(Channel.FEED);
        return channels;
    }
}
