package com.taarifu.communications.domain.repository;

import com.taarifu.communications.domain.model.NotificationPreference;
import com.taarifu.communications.domain.model.enums.Channel;
import com.taarifu.communications.domain.model.enums.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link NotificationPreference} (ARCHITECTURE §3.3).
 *
 * <p>Responsibility: the persistence port for per-(type,channel) opt-ins. The dispatcher reads a
 * recipient's preferences to decide channel and quiet hours; the management endpoint reads/lists/updates
 * them. Soft-deleted rows are excluded automatically by the entity's {@code @SQLRestriction}.</p>
 */
public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Long> {

    /**
     * @param publicId the preference's public id.
     * @return the matching preference, or empty.
     */
    Optional<NotificationPreference> findByPublicId(UUID publicId);

    /**
     * Lists all of a profile's preferences — the management view and the dispatcher's per-recipient load.
     *
     * @param profileId the owning profile's public id.
     * @return the profile's preferences (possibly empty → channel-matrix defaults apply).
     */
    List<NotificationPreference> findByProfileId(UUID profileId);

    /**
     * Finds the preference governing a specific (profile, type, channel) — the dispatcher's exact lookup.
     *
     * @param profileId the owning profile's public id.
     * @param type      the notification type.
     * @param channel   the channel.
     * @return the matching preference, or empty (→ default applies).
     */
    Optional<NotificationPreference> findByProfileIdAndTypeAndChannel(
            UUID profileId, NotificationType type, Channel channel);
}
