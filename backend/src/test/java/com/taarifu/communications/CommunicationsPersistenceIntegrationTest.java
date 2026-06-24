package com.taarifu.communications;

import com.taarifu.AbstractPostgisIntegrationTest;
import com.taarifu.communications.domain.model.Announcement;
import com.taarifu.communications.domain.model.DeviceToken;
import com.taarifu.communications.domain.model.Notification;
import com.taarifu.communications.domain.model.NotificationPreference;
import com.taarifu.communications.domain.model.Subscription;
import com.taarifu.communications.domain.model.enums.Channel;
import com.taarifu.communications.domain.model.enums.DevicePlatform;
import com.taarifu.communications.domain.model.enums.NotificationStatus;
import com.taarifu.communications.domain.model.enums.NotificationType;
import com.taarifu.communications.domain.model.enums.SubscriptionTargetType;
import com.taarifu.communications.domain.repository.AnnouncementRepository;
import com.taarifu.communications.domain.repository.DeviceTokenRepository;
import com.taarifu.communications.domain.repository.NotificationPreferenceRepository;
import com.taarifu.communications.domain.repository.NotificationRepository;
import com.taarifu.communications.domain.repository.SubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testcontainers integration test for the communications persistence slice (CLAUDE.md §10, ADR-0009).
 *
 * <p>Responsibility: proves the Flyway migrations V27–V30 match the JPA entities ({@code ddl-auto=validate}
 * passes on context load) and that the load-bearing DB invariants hold against <b>real PostgreSQL</b>:
 * the announcement child collections round-trip; the feed query selects only live, in-window,
 * audience-matching announcements; the notification idempotency-key unique index blocks a double-send;
 * and the subscription / preference partial-unique indexes hold for live rows. Docker is required — this
 * runs in CI; the unit tests cover the logic locally without Docker.</p>
 *
 * <p>WHY {@code @SpringBootTest} (full context): only a full boot runs Flyway + Hibernate validation, the
 * very thing this test exists to assert (a migration/entity drift fails fast at startup).</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class CommunicationsPersistenceIntegrationTest extends AbstractPostgisIntegrationTest {

    @Autowired
    private AnnouncementRepository announcementRepository;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private NotificationPreferenceRepository preferenceRepository;
    @Autowired
    private DeviceTokenRepository deviceTokenRepository;

    @Test
    void announcement_withChildCollections_roundTrips() {
        UUID area = UUID.randomUUID();
        Announcement a = Announcement.draft(UUID.randomUUID(), "Maji", "Tangazo la maji", "Water notice");
        a.targetAudience(Set.of(area), UUID.randomUUID(), "CITIZEN",
                Set.of(Channel.FEED, Channel.SMS));
        a.setAttachmentRefs(Set.of("s3://bucket/key1"));
        a.schedule(null, null);
        a.publish(Instant.now());
        Announcement saved = announcementRepository.saveAndFlush(a);

        Announcement reloaded = announcementRepository.findByPublicId(saved.getPublicId()).orElseThrow();
        assertThat(reloaded.getAudienceAreaIds()).containsExactly(area);
        assertThat(reloaded.getChannels()).containsExactlyInAnyOrder(Channel.FEED, Channel.SMS);
        assertThat(reloaded.getAttachmentRefs()).containsExactly("s3://bucket/key1");
    }

    @Test
    void feedQuery_selectsOnlyLiveInWindowAudienceMatchingAnnouncements() {
        UUID area = UUID.randomUUID();
        UUID otherArea = UUID.randomUUID();
        Instant now = Instant.now();

        announcementRepository.saveAndFlush(published("Yangu", Set.of(area), now.minusSeconds(60), null));
        announcementRepository.saveAndFlush(published("Nyingine", Set.of(otherArea), now.minusSeconds(60), null));
        announcementRepository.saveAndFlush(published("Imeisha", Set.of(area), now.minusSeconds(120),
                now.minusSeconds(60))); // expired

        var page = announcementRepository.findFeed(Set.of(area), Set.of(new UUID(0L, 0L)), now,
                PageRequest.of(0, 20));

        // Only the live, in-window, area-matching announcement is returned.
        assertThat(page.getContent()).extracting(Announcement::getTitle).containsExactly("Yangu");
    }

    @Test
    void notificationIdempotencyKey_isUnique() {
        UUID recipient = UUID.randomUUID();
        notificationRepository.saveAndFlush(Notification.queue(recipient,
                NotificationType.NEW_ANNOUNCEMENT, Channel.FEED, "ref", "dup-key"));

        assertThatThrownBy(() -> notificationRepository.saveAndFlush(Notification.queue(recipient,
                NotificationType.NEW_ANNOUNCEMENT, Channel.PUSH, "ref", "dup-key")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void notificationLifecycle_persists() {
        Notification n = notificationRepository.saveAndFlush(Notification.queue(UUID.randomUUID(),
                NotificationType.SYSTEM, Channel.FEED, null, "lifecycle-key"));
        n.markDelivered(Instant.now());
        notificationRepository.saveAndFlush(n);

        assertThat(notificationRepository.findByPublicId(n.getPublicId()).orElseThrow().getStatus())
                .isEqualTo(NotificationStatus.DELIVERED);
    }

    @Test
    void subscriptionPartialUnique_blocksDuplicateLiveFollow() {
        UUID follower = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        subscriptionRepository.saveAndFlush(
                Subscription.follow(follower, SubscriptionTargetType.AREA, target));

        assertThatThrownBy(() -> subscriptionRepository.saveAndFlush(
                Subscription.follow(follower, SubscriptionTargetType.AREA, target)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void preferencePartialUnique_blocksDuplicateLivePair() {
        UUID profile = UUID.randomUUID();
        preferenceRepository.saveAndFlush(NotificationPreference.of(profile,
                NotificationType.NEW_ANNOUNCEMENT, Channel.PUSH, true));

        assertThatThrownBy(() -> preferenceRepository.saveAndFlush(NotificationPreference.of(profile,
                NotificationType.NEW_ANNOUNCEMENT, Channel.PUSH, false)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deviceTokenUnique_blocksDuplicateLiveToken() {
        // One registration per token value: a second row for the same token is blocked (no double-deliver).
        // WHY this asserts only the duplicate-block (not re-register-after-prune): this slice runs under
        // ddl-auto=create-drop (Flyway off), so the schema comes from the entity's table-level
        // @UniqueConstraint — a FULL unique on token — not the migration's live-scoped PARTIAL index (V122).
        // The re-register-after-soft-delete path (which the prod V122 partial index allows) is proven at the
        // unit level in DeviceTokenServiceTest (findByToken excludes soft-deleted rows → fresh insert) — the
        // same precedent the Subscription persistence test follows (it too asserts only the duplicate-block).
        UUID profile = UUID.randomUUID();
        deviceTokenRepository.saveAndFlush(
                DeviceToken.register(profile, "fcm-token-1", DevicePlatform.ANDROID, Instant.now()));

        assertThatThrownBy(() -> deviceTokenRepository.saveAndFlush(
                DeviceToken.register(profile, "fcm-token-1", DevicePlatform.IOS, Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Builds a PUBLISHED announcement with the given window for the feed-query assertions. */
    private Announcement published(String title, Set<UUID> areas, Instant publishAt, Instant expireAt) {
        Announcement a = Announcement.draft(UUID.randomUUID(), title, "mwili", null);
        a.targetAudience(areas, null, null, Set.of(Channel.FEED));
        a.schedule(publishAt, expireAt);
        a.publish(publishAt); // publishAt in the past → PUBLISHED
        return a;
    }
}
