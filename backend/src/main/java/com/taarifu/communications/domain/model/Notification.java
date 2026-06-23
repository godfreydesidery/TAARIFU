package com.taarifu.communications.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.communications.domain.model.enums.Channel;
import com.taarifu.communications.domain.model.enums.NotificationStatus;
import com.taarifu.communications.domain.model.enums.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

/**
 * One delivery of one event to one recipient over one channel (PRD §13 channel matrix, M5).
 *
 * <p>Responsibility: the per-recipient, per-channel dispatch record. A single domain event (e.g. a new
 * announcement) fans out to many {@code Notification} rows — one per recipient × selected channel — each
 * tracking its own {@link #status} from {@code QUEUED} to a terminal {@code DELIVERED}/{@code READ}/
 * {@code FAILED} (PRD §13 "logged with delivery status"). The recipient is referenced by public
 * {@code UUID}; the rich content is referenced by {@link #payloadRef} (an object-store / source-entity
 * key), <b>not</b> inlined, so this row carries no PII and stays lean (PRD §18).</p>
 *
 * <p>WHY an {@link #idempotencyKey} unique per row: SMS/push webhooks and outbox relays deliver
 * at-least-once and can replay (EI-3). A stable key derived from {@code (event, recipient, channel)} +
 * the source id, made unique by the database, guarantees no double-send and lets a retry find the
 * existing row rather than create a duplicate (DI4, PRD §13 "all sends idempotent"). JPA uniqueness is
 * declared here; the Flyway migration owns the matching constraint.</p>
 *
 * <p>WHY status transitions are methods (not a public setter): the lifecycle is a domain invariant; a
 * {@code FAILED} or {@code READ} notification must not silently revert. The mutators encode the legal
 * forward transitions and stamp the relevant timestamp.</p>
 */
@Entity
@Table(name = "notification", indexes = {
        // The recipient's notification list / unread count — the hot read.
        @Index(name = "ix_notification_recipient", columnList = "recipient_profile_id, created_at"),
        @Index(name = "ix_notification_status", columnList = "status"),
        @Index(name = "ix_notification_idempotency", columnList = "idempotency_key", unique = true)
})
@SQLRestriction("deleted = false")
public class Notification extends BaseEntity {

    /** Public id of the recipient profile ({@code identity}). Bare {@code UUID}, never a FK. */
    @Column(name = "recipient_profile_id", nullable = false)
    private UUID recipientProfileId;

    /** What kind of event this notifies about (drives the i18n template + preference axis). */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private NotificationType type;

    /**
     * Reference to the source content (e.g. the announcement public id, or an object-store key for a
     * rendered payload). Never the content itself — keeps the row PII-free and small (PRD §18).
     */
    @Column(name = "payload_ref", length = 512)
    private String payloadRef;

    /** The channel this particular row is delivered over (one row per recipient × channel). */
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    private Channel channel;

    /** Delivery lifecycle state. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private NotificationStatus status = NotificationStatus.QUEUED;

    /**
     * Stable de-duplication key (unique) so an at-least-once relay/webhook never double-sends (DI4).
     * Derived from the event + recipient + channel + source id; opaque, non-PII.
     */
    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    /** When the channel gateway accepted the send (UTC), or {@code null} while {@code QUEUED}. */
    @Column(name = "sent_at")
    private Instant sentAt;

    /** When delivery was confirmed (UTC), or {@code null}. */
    @Column(name = "delivered_at")
    private Instant deliveredAt;

    /** When the recipient read it (UTC), or {@code null}. */
    @Column(name = "read_at")
    private Instant readAt;

    /** Non-PII reason for a {@code FAILED} status (e.g. {@code NO_PUSH_TOKEN}), or {@code null}. */
    @Column(name = "failure_reason", length = 128)
    private String failureReason;

    /** JPA requires a no-arg constructor; not for application use. */
    protected Notification() {
    }

    /**
     * Queues a notification for dispatch.
     *
     * @param recipientProfileId the recipient profile's public id.
     * @param type               the event type.
     * @param channel            the channel to deliver over.
     * @param payloadRef         a reference to the source content, or {@code null}.
     * @param idempotencyKey     the stable de-dup key (unique).
     * @return the populated, transient notification in {@code QUEUED}.
     */
    public static Notification queue(UUID recipientProfileId, NotificationType type, Channel channel,
                                     String payloadRef, String idempotencyKey) {
        Notification n = new Notification();
        n.recipientProfileId = recipientProfileId;
        n.type = type;
        n.channel = channel;
        n.payloadRef = payloadRef;
        n.idempotencyKey = idempotencyKey;
        n.status = NotificationStatus.QUEUED;
        return n;
    }

    /**
     * Marks the notification accepted by the channel gateway ({@code QUEUED → SENT}).
     *
     * @param at the instant the gateway accepted it.
     */
    public void markSent(Instant at) {
        this.status = NotificationStatus.SENT;
        this.sentAt = at;
    }

    /**
     * Marks delivery confirmed ({@code → DELIVERED}) — e.g. an SMS DLR webhook or push receipt.
     *
     * @param at the delivery instant.
     */
    public void markDelivered(Instant at) {
        this.status = NotificationStatus.DELIVERED;
        this.deliveredAt = at;
    }

    /**
     * Marks the recipient as having read it ({@code → READ}).
     *
     * @param at the read instant.
     */
    public void markRead(Instant at) {
        this.status = NotificationStatus.READ;
        this.readAt = at;
    }

    /**
     * Marks a terminal delivery failure ({@code → FAILED}) after retries/fallback are exhausted.
     *
     * @param reason a non-PII reason code (e.g. {@code GATEWAY_REJECTED}); never log recipient PII.
     */
    public void markFailed(String reason) {
        this.status = NotificationStatus.FAILED;
        this.failureReason = reason;
    }

    /** @return the recipient profile's public id. */
    public UUID getRecipientProfileId() {
        return recipientProfileId;
    }

    /** @return the event type. */
    public NotificationType getType() {
        return type;
    }

    /** @return the source content reference, or {@code null}. */
    public String getPayloadRef() {
        return payloadRef;
    }

    /** @return the delivery channel. */
    public Channel getChannel() {
        return channel;
    }

    /** @return the delivery status. */
    public NotificationStatus getStatus() {
        return status;
    }

    /** @return the de-dup idempotency key. */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /** @return when the gateway accepted it, or {@code null}. */
    public Instant getSentAt() {
        return sentAt;
    }

    /** @return when delivery was confirmed, or {@code null}. */
    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    /** @return when the recipient read it, or {@code null}. */
    public Instant getReadAt() {
        return readAt;
    }

    /** @return the failure reason code, or {@code null}. */
    public String getFailureReason() {
        return failureReason;
    }
}
