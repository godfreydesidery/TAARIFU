package com.taarifu.communications.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.communications.domain.model.enums.DevicePlatform;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

/**
 * A registered push device token: the binding "this profile can be reached on this device" that the push
 * path fans out to (PRD §13, EI-5, US-5.1; ADR-0014 §5a fan-out).
 *
 * <p>Responsibility: gives the registry-less push stub a real target. A citizen's app registers its FCM
 * registration token on login (and re-registers on rotation); the {@code PushSender} adapter resolves a
 * recipient's <i>live</i> tokens and delivers to each device. A profile may have several live tokens (a
 * phone + a tablet), so the recipient→token relationship is one-to-many. The owning profile and the token
 * are referenced only by value — the profile is an {@code identity} profile, referenced by bare public
 * {@code UUID}, never FK-joined (ARCHITECTURE §3.2, parallel-build isolation).</p>
 *
 * <p><b>Privacy / secret handling (PRD §18, CLAUDE.md §12)</b>: an FCM registration token is a sensitive
 * routing credential — it lets its holder push to the device — so it is treated like a secret: <b>never
 * logged</b> (the registry and adapter log only the owning profile {@code UUID} and a token <i>presence/
 * count</i>, never the token string), and it never enters an event payload, a DTO returned to other users,
 * or an audit line. It is not field-encrypted at rest (it is not citizen PII and rotates frequently), but
 * the no-log discipline is absolute.</p>
 *
 * <p>WHY a unique constraint on {@code token} scoped to live rows: one physical device-token is registered
 * to exactly one profile at a time — registering an already-known token is an idempotent upsert (re-bind /
 * refresh {@code lastSeenAt}), never a duplicate row, so a push never double-delivers to one device. JPA
 * cannot express "live rows only" partial uniqueness, so the Flyway migration owns a partial unique index
 * ({@code WHERE deleted = false}); the table-level constraint here documents the intent. Unregister
 * (logout) and invalid-token pruning are <b>soft-deletes</b> so the registration history stays auditable
 * (PRD §9) and a later re-register inserts a fresh row.</p>
 */
@Entity
@Table(name = "device_token",
        uniqueConstraints = {
                @UniqueConstraint(name = "ux_device_token_token", columnNames = {"token"})
        },
        indexes = {
                // The push fan-out's hot read: all live tokens for a recipient profile.
                @Index(name = "ix_device_token_profile", columnList = "profile_id"),
                // Unregister / prune by raw token value.
                @Index(name = "ix_device_token_value", columnList = "token")
        })
@SQLRestriction("deleted = false")
public class DeviceToken extends BaseEntity {

    /** Max stored token length; FCM registration tokens are well under this (headroom for APNs/WebPush). */
    public static final int MAX_TOKEN_LENGTH = 512;

    /** Public id of the owning profile ({@code identity}). Bare {@code UUID}, never a FK. */
    @Column(name = "profile_id", nullable = false)
    private UUID profileId;

    /**
     * The opaque push registration token (FCM/APNs/WebPush). Treated as a secret: never logged, never
     * returned to another user, never placed in an event. Unique among live rows (migration-owned partial
     * index).
     */
    @Column(name = "token", nullable = false, length = MAX_TOKEN_LENGTH)
    private String token;

    /** The client platform this token belongs to (descriptive; FCM delivers all in this MVP). */
    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 16)
    private DevicePlatform platform;

    /**
     * When the token was last (re-)registered or confirmed reachable. Refreshed on every idempotent
     * re-register so a future retention job can prune long-stale tokens without a delivery attempt.
     */
    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    /** JPA requires a no-arg constructor; not for application use. */
    protected DeviceToken() {
    }

    /**
     * Registers a new device token for a profile.
     *
     * @param profileId the owning profile's public id.
     * @param token     the opaque push registration token (secret; never logged).
     * @param platform  the client platform.
     * @param at        the registration instant (stamped into {@link #lastSeenAt}).
     * @return the populated, transient device token.
     */
    public static DeviceToken register(UUID profileId, String token, DevicePlatform platform, Instant at) {
        DeviceToken d = new DeviceToken();
        d.profileId = profileId;
        d.token = token;
        d.platform = platform;
        d.lastSeenAt = at;
        return d;
    }

    /**
     * Re-binds an existing token row to a profile/platform and refreshes its last-seen — the idempotent
     * upsert path when the same physical token is registered again (app relaunch, token rotation that
     * resolves to the same value, or a device handed to a new account).
     *
     * <p>WHY re-bind rather than reject: a token uniquely identifies a device install; if it surfaces again
     * (possibly for a different profile after a logout/login on the same device), the latest registration
     * is authoritative. This keeps the registry a faithful "who is reachable where" without orphaned rows.</p>
     *
     * @param profileId the (possibly new) owning profile's public id.
     * @param platform  the (possibly updated) client platform.
     * @param at        the re-registration instant.
     */
    public void refresh(UUID profileId, DevicePlatform platform, Instant at) {
        this.profileId = profileId;
        this.platform = platform;
        this.lastSeenAt = at;
    }

    /** @return the owning profile's public id. */
    public UUID getProfileId() {
        return profileId;
    }

    /** @return the opaque push registration token (secret — do not log). */
    public String getToken() {
        return token;
    }

    /** @return the client platform. */
    public DevicePlatform getPlatform() {
        return platform;
    }

    /** @return when the token was last (re-)registered or confirmed reachable. */
    public Instant getLastSeenAt() {
        return lastSeenAt;
    }
}
