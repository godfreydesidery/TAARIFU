package com.taarifu.communications.domain.model.enums;

/**
 * A delivery channel an announcement may target and a notification may be dispatched over
 * (PRD §13 channel matrix, §9.1, M5).
 *
 * <p>Responsibility: the closed set of ways a message reaches a citizen. {@code FEED} is the in-app
 * timeline (always retained even if a real-time channel fails — EI-5); {@code PUSH} is FCM/APNs;
 * {@code SMS} reaches feature phones with no data (the inclusion channel, PRD §14). {@code EMAIL} is
 * carried for staff/role notifications and digests (PRD §13).</p>
 *
 * <p>WHY this lives in {@code communications} (not {@code common}): channels are this module's domain
 * vocabulary; other modules name a channel only by referencing this type through the module's public
 * API, never by redefining it (DRY, ARCHITECTURE §3.2).</p>
 */
public enum Channel {

    /** In-app personalised feed/timeline — the durable channel; an item is never lost here (EI-5). */
    FEED,

    /** Mobile push (FCM HTTP v1 / APNs); falls back to SMS when no valid device token (US-5.1, EI-5). */
    PUSH,

    /** SMS over the procured shortcode/sender-ID — the feature-phone inclusion channel (PRD §14, EI-3). */
    SMS,

    /** Transactional email — staff/role notifications, digests, and a fallback for OTP (PRD §13, EI-6). */
    EMAIL
}
