package com.taarifu.communications.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.communications.domain.model.enums.Channel;
import com.taarifu.communications.domain.model.enums.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalTime;
import java.util.UUID;

/**
 * A citizen's opt-in/out for a single (type, channel) pair, plus quiet hours and language
 * (PRD §13 "respect per-user NotificationPreference (channel/type, quiet hours, language)", M5, UC-G08).
 *
 * <p>Responsibility: the per-profile, per-{@link NotificationType}, per-{@link Channel} switch the
 * dispatcher consults before sending. Absence of a row means "use the channel-matrix default" (PRD §13)
 * — the dispatcher treats no-row-and-not-always-on as opt-in by default for FEED/PUSH and opt-out for
 * SMS (SMS has a real cost), so a citizen is reachable in-app without configuring anything but is never
 * silently charged SMS. {@code SYSTEM}/{@code MODERATION_OUTCOME} types are "always" and cannot be
 * disabled (PRD §13) — the dispatcher ignores an opt-out for those.</p>
 *
 * <p>Quiet hours ({@link #quietFrom}/{@link #quietTo}) suppress <b>interruptive</b> channels (PUSH/SMS)
 * during the window in the profile's local time; the durable FEED channel is never suppressed (the item
 * is always retained — EI-5). {@link #language} pins the locale templates are rendered in so a citizen
 * who chose Kiswahili always receives Swahili regardless of request locale (ADR-0010, PRD §14).</p>
 *
 * <p>WHY a row-per-pair (not a JSON blob): it is queryable and indexable for the dispatcher's
 * per-recipient lookup at fan-out scale, and a unique constraint on {@code (profile, type, channel)}
 * makes "one preference per pair" a database invariant rather than application hope.</p>
 */
@Entity
@Table(name = "notification_preference",
        uniqueConstraints = {
                @UniqueConstraint(name = "ux_notification_preference_pair",
                        columnNames = {"profile_id", "type", "channel"})
        },
        indexes = {
                @Index(name = "ix_notification_preference_profile", columnList = "profile_id")
        })
@SQLRestriction("deleted = false")
public class NotificationPreference extends BaseEntity {

    /** Public id of the owning profile ({@code identity}). Bare {@code UUID}, never a FK. */
    @Column(name = "profile_id", nullable = false)
    private UUID profileId;

    /** The notification type this preference governs. */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private NotificationType type;

    /** The channel this preference governs. */
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    private Channel channel;

    /** Whether the citizen opted in to this (type, channel). Ignored for "always" types (PRD §13). */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /**
     * Start of the quiet window (local time), or {@code null} for no quiet hours. During the window,
     * interruptive channels (PUSH/SMS) are suppressed; FEED is never suppressed (EI-5).
     */
    @Column(name = "quiet_from")
    private LocalTime quietFrom;

    /** End of the quiet window (local time), or {@code null}. Wraps midnight if {@code quietTo < quietFrom}. */
    @Column(name = "quiet_to")
    private LocalTime quietTo;

    /**
     * BCP-47 / ISO-639 language tag (e.g. {@code sw}, {@code en}) templates are rendered in for this
     * recipient, or {@code null} to follow the request/account default (ADR-0010).
     */
    @Column(name = "language", length = 8)
    private String language;

    /** JPA requires a no-arg constructor; not for application use. */
    protected NotificationPreference() {
    }

    /**
     * Creates a preference for a (type, channel) pair.
     *
     * @param profileId the owning profile's public id.
     * @param type      the governed notification type.
     * @param channel   the governed channel.
     * @param enabled   whether opted in.
     * @return the populated, transient preference.
     */
    public static NotificationPreference of(UUID profileId, NotificationType type, Channel channel,
                                            boolean enabled) {
        NotificationPreference p = new NotificationPreference();
        p.profileId = profileId;
        p.type = type;
        p.channel = channel;
        p.enabled = enabled;
        return p;
    }

    /**
     * Applies the citizen's settings (PATCH-style — only the enabled flag and the quiet/language
     * settings are mutable; the {@code (profile,type,channel)} identity is immutable).
     *
     * @param enabled   the new opt-in state.
     * @param quietFrom quiet window start (local), or {@code null}.
     * @param quietTo   quiet window end (local), or {@code null}.
     * @param language  preferred language tag, or {@code null}.
     */
    public void update(boolean enabled, LocalTime quietFrom, LocalTime quietTo, String language) {
        this.enabled = enabled;
        this.quietFrom = quietFrom;
        this.quietTo = quietTo;
        this.language = language;
    }

    /**
     * Whether the given local time falls within this preference's quiet window. A {@code null} window
     * is never quiet. A window that wraps midnight ({@code from > to}) is handled.
     *
     * @param at the local time to test.
     * @return {@code true} if {@code at} is inside the quiet window.
     */
    public boolean isQuietAt(LocalTime at) {
        if (quietFrom == null || quietTo == null) {
            return false;
        }
        if (quietFrom.isBefore(quietTo)) {
            return !at.isBefore(quietFrom) && at.isBefore(quietTo);
        }
        // Wraps midnight: quiet if at >= from OR at < to.
        return !at.isBefore(quietFrom) || at.isBefore(quietTo);
    }

    /** @return the owning profile's public id. */
    public UUID getProfileId() {
        return profileId;
    }

    /** @return the governed notification type. */
    public NotificationType getType() {
        return type;
    }

    /** @return the governed channel. */
    public Channel getChannel() {
        return channel;
    }

    /** @return whether opted in. */
    public boolean isEnabled() {
        return enabled;
    }

    /** @return quiet window start (local), or {@code null}. */
    public LocalTime getQuietFrom() {
        return quietFrom;
    }

    /** @return quiet window end (local), or {@code null}. */
    public LocalTime getQuietTo() {
        return quietTo;
    }

    /** @return preferred language tag, or {@code null}. */
    public String getLanguage() {
        return language;
    }
}
