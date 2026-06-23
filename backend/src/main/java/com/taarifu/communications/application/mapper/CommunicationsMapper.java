package com.taarifu.communications.application.mapper;

import com.taarifu.communications.api.dto.AnnouncementDto;
import com.taarifu.communications.api.dto.FeedItemDto;
import com.taarifu.communications.api.dto.NotificationDto;
import com.taarifu.communications.api.dto.NotificationPreferenceDto;
import com.taarifu.communications.api.dto.SubscriptionDto;
import com.taarifu.communications.domain.model.Announcement;
import com.taarifu.communications.domain.model.Notification;
import com.taarifu.communications.domain.model.NotificationPreference;
import com.taarifu.communications.domain.model.Subscription;
import com.taarifu.communications.domain.model.enums.Channel;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Maps communications entities to their boundary DTOs (ARCHITECTURE §3.3, CLAUDE.md §8).
 *
 * <p>Responsibility: the single translation layer from {@link Announcement}/{@link Subscription}/
 * {@link Notification}/{@link NotificationPreference} entities to {@code api.dto} records, ensuring
 * <b>entities never leave the module</b> and only {@code publicId} is exposed (ADR-0006). A hand-written
 * {@code @Component} mapper (not MapStruct) keeps the foundation slice annotation-processor-free, matching
 * the geography module's choice (ARCHITECTURE §2).</p>
 *
 * <p>The feed snippet honours the recipient's locale (Swahili default — ADR-0010): Swahili body unless
 * the recipient locale is English and an English body exists.</p>
 */
@Component
public class CommunicationsMapper {

    /** Max characters of body included in a lean feed snippet (data-budget economy, PRD §15). */
    private static final int SNIPPET_LENGTH = 160;

    /**
     * @param a the announcement.
     * @return the full {@link AnnouncementDto} (author/management/read view).
     */
    public AnnouncementDto toAnnouncementDto(Announcement a) {
        return new AnnouncementDto(
                a.getPublicId(),
                a.getAuthorProfileId(),
                a.getTitle(),
                a.getBodySw(),
                a.getBodyEn(),
                a.getCategoryId(),
                a.getAudienceRole(),
                a.getStatus().name(),
                a.isModerationHeld(),
                a.getAudienceAreaIds(),
                a.getChannels().stream().map(Channel::name).collect(Collectors.toCollection(java.util.LinkedHashSet::new)),
                a.getAttachmentRefs(),
                a.getPublishAt(),
                a.getExpireAt());
    }

    /**
     * Maps an announcement to a lean feed item in the recipient's locale.
     *
     * @param a      the in-feed announcement.
     * @param locale the recipient's request locale (drives SW/EN body selection).
     * @return the {@link FeedItemDto} with a localised snippet.
     */
    public FeedItemDto toFeedItemDto(Announcement a, Locale locale) {
        String body = chooseBody(a, locale);
        String snippet = body == null ? null
                : (body.length() <= SNIPPET_LENGTH ? body : body.substring(0, SNIPPET_LENGTH));
        return new FeedItemDto(a.getPublicId(), a.getTitle(), snippet, a.getAuthorProfileId(),
                a.getPublishAt());
    }

    /** Selects the body in the recipient's locale: English only when locale is EN and an EN body exists. */
    private String chooseBody(Announcement a, Locale locale) {
        boolean english = locale != null && "en".equalsIgnoreCase(locale.getLanguage());
        if (english && a.getBodyEn() != null && !a.getBodyEn().isBlank()) {
            return a.getBodyEn();
        }
        return a.getBodySw();
    }

    /**
     * @param s the subscription.
     * @return the {@link SubscriptionDto}.
     */
    public SubscriptionDto toSubscriptionDto(Subscription s) {
        return new SubscriptionDto(s.getPublicId(), s.getTargetType().name(), s.getTargetId());
    }

    /**
     * @param n the notification.
     * @return the {@link NotificationDto} (no recipient id — the caller is the recipient).
     */
    public NotificationDto toNotificationDto(Notification n) {
        return new NotificationDto(
                n.getPublicId(),
                n.getType().name(),
                n.getChannel().name(),
                n.getStatus().name(),
                n.getPayloadRef(),
                n.getCreatedAt(),
                n.getReadAt());
    }

    /**
     * @param p the preference.
     * @return the {@link NotificationPreferenceDto}.
     */
    public NotificationPreferenceDto toPreferenceDto(NotificationPreference p) {
        return new NotificationPreferenceDto(
                p.getPublicId(),
                p.getType().name(),
                p.getChannel().name(),
                p.isEnabled(),
                p.getQuietFrom(),
                p.getQuietTo(),
                p.getLanguage());
    }
}
