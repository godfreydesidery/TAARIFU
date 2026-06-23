package com.taarifu.communications.domain.model.enums;

/**
 * Delivery lifecycle of a single {@link com.taarifu.communications.domain.model.Notification}
 * (PRD §13 "logged with delivery status", EI-3 DLR webhook, M5).
 *
 * <p>Responsibility: tracks one notification's progress from queued to a terminal outcome so the
 * platform can report delivery status, retry the un-delivered, and prune dead push tokens. The
 * progression is {@code QUEUED → SENT → DELIVERED} on success, {@code → READ} once the recipient opens
 * it (feed/push), and {@code → FAILED} on a terminal send error (after retries/fallback exhausted).</p>
 *
 * <p>WHY {@code SENT} and {@code DELIVERED} are separate: SMS/push are asynchronous — the gateway
 * <i>accepts</i> (SENT) before the carrier/device <i>delivers</i> (DELIVERED via the DLR webhook, EI-3).
 * Collapsing them would lose the "accepted but never delivered" signal that drives fallback and
 * provider-quality metrics (PRD §13, DI6).</p>
 */
public enum NotificationStatus {

    /** Persisted and awaiting dispatch by the (idempotent) dispatcher/worker. */
    QUEUED,

    /** Accepted by the channel gateway (SMS/push/email queued upstream); awaiting delivery confirmation. */
    SENT,

    /** Confirmed delivered (DLR webhook for SMS, FCM/APNs receipt, or rendered in-feed). */
    DELIVERED,

    /** The recipient opened/acknowledged it (feed/push read receipt). Terminal, positive. */
    READ,

    /** Terminal failure after retries and any channel fallback were exhausted (logged, not lost). */
    FAILED
}
