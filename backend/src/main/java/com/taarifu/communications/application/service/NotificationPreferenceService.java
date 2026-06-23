package com.taarifu.communications.application.service;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.communications.domain.model.NotificationPreference;
import com.taarifu.communications.domain.model.enums.Channel;
import com.taarifu.communications.domain.model.enums.NotificationType;
import com.taarifu.communications.domain.repository.NotificationPreferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Manages a citizen's notification preferences (PRD §13, UC-G08, M5).
 *
 * <p>Responsibility: the use-case orchestration for reading and upserting per-(type,channel) preferences.
 * It owns the transaction boundary, validates the type/channel enums, enforces the "always-on" rule
 * (a citizen cannot opt out of {@code SYSTEM}/{@code MODERATION_OUTCOME} — PRD §13), and upserts on the
 * {@code (caller, type, channel)} identity (the DB unique constraint is the backstop). A citizen manages
 * only their own preferences.</p>
 */
@Service
public class NotificationPreferenceService {

    /** Types a citizen cannot silence (PRD §13 "always"). */
    private static final Set<NotificationType> ALWAYS_ON =
            EnumSet.of(NotificationType.SYSTEM, NotificationType.MODERATION_OUTCOME);

    private final NotificationPreferenceRepository preferenceRepository;

    /**
     * @param preferenceRepository preference persistence.
     */
    public NotificationPreferenceService(NotificationPreferenceRepository preferenceRepository) {
        this.preferenceRepository = preferenceRepository;
    }

    /**
     * Lists the caller's preferences.
     *
     * @param callerProfileId the authenticated caller's profile public id.
     * @return the caller's preferences (possibly empty → channel-matrix defaults apply at dispatch).
     */
    @Transactional(readOnly = true)
    public List<NotificationPreference> listMyPreferences(UUID callerProfileId) {
        return preferenceRepository.findByProfileId(callerProfileId);
    }

    /**
     * Upserts a single preference for the caller.
     *
     * @param callerProfileId the authenticated caller's profile public id.
     * @param typeName        the notification-type enum name (validated).
     * @param channelName     the channel enum name (validated).
     * @param enabled         the opt-in state (rejected as a no-op opt-out for always-on types).
     * @param quietFrom       quiet window start (local), or {@code null}.
     * @param quietTo         quiet window end (local), or {@code null}.
     * @param language        preferred language tag, or {@code null}.
     * @return the upserted preference.
     * @throws ApiException {@link ErrorCode#VALIDATION_FAILED} if type/channel is unknown, or
     *                      {@link ErrorCode#BAD_REQUEST} if attempting to disable an always-on type.
     */
    @Transactional
    public NotificationPreference upsert(UUID callerProfileId, String typeName, String channelName,
                                         boolean enabled, LocalTime quietFrom, LocalTime quietTo,
                                         String language) {
        NotificationType type = parseType(typeName);
        Channel channel = parseChannel(channelName);

        // Always-on types may not be disabled (PRD §13). Reject rather than silently honour.
        if (ALWAYS_ON.contains(type) && !enabled) {
            throw new ApiException(ErrorCode.BAD_REQUEST);
        }

        NotificationPreference pref = preferenceRepository
                .findByProfileIdAndTypeAndChannel(callerProfileId, type, channel)
                .orElseGet(() -> NotificationPreference.of(callerProfileId, type, channel, enabled));
        pref.update(enabled, quietFrom, quietTo, language);
        return preferenceRepository.save(pref);
    }

    /** Parses the type name; an unknown value is a localised validation failure. */
    private NotificationType parseType(String name) {
        try {
            return NotificationType.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
    }

    /** Parses the channel name; an unknown value is a localised validation failure. */
    private Channel parseChannel(String name) {
        try {
            return Channel.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
