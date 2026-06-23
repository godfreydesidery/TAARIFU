package com.taarifu.communications.domain.model.enums;

/**
 * The kind of event a {@link com.taarifu.communications.domain.model.Notification} informs about,
 * and the axis a {@link com.taarifu.communications.domain.model.NotificationPreference} opts in/out of
 * (PRD §13 channel matrix, M5).
 *
 * <p>Responsibility: a stable, append-only catalogue of notifiable event types so a citizen can opt in
 * or out per type and the dispatcher can pick the channel matrix row (PRD §13). It is the i18n template
 * key axis too — each type resolves a Swahili-first message (ADR-0010).</p>
 *
 * <p>WHY only a subset this increment: notifications for reports/petitions/Q&A/ratings are emitted by
 * the modules that own those events (reporting/engagement/accountability) via domain events; their
 * concrete {@code payloadRef} is produced there. This module owns the dispatch substrate and the types
 * its own M4/M5 surface produces — {@code NEW_ANNOUNCEMENT} — plus the always-on {@code SYSTEM} and
 * {@code MODERATION_OUTCOME} types the matrix marks "always". Other modules reference an existing value
 * by name; new types are appended here (never repurposed — clients/preferences depend on the name).</p>
 */
public enum NotificationType {

    /** A new announcement matched the recipient's area/representative/category follows (PRD §13). */
    NEW_ANNOUNCEMENT,

    /** A report the recipient filed/follows changed state (emitted by reporting; type reserved here). */
    REPORT_STATUS,

    /** A moderation decision on the recipient's content — channel matrix marks this "always" (PRD §13). */
    MODERATION_OUTCOME,

    /** A generic system notice (account/security); "always" on — cannot be silenced by preference. */
    SYSTEM
}
