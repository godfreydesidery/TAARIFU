package com.taarifu.moderation.domain.model.enums;

/**
 * Lifecycle of a {@link com.taarifu.moderation.domain.model.ModerationItem} (queue entry)
 * (PRD §18, US-12.2, UC-H01/H02).
 *
 * <p>Responsibility: drives the moderator queue state machine — an item is {@link #PENDING} on the
 * prioritised queue, {@link #IN_REVIEW} once a moderator claims it, and reaches a terminal
 * {@link #ACTIONED} (a {@link com.taarifu.moderation.domain.model.ModerationAction} was taken) or
 * {@link #DISMISSED} (reviewed, no violation). Terminal states are append-only outcomes; reopening
 * happens via a new item, never by mutating a closed one back.</p>
 */
public enum ModerationItemStatus {

    /** On the queue, not yet claimed by a moderator. */
    PENDING,

    /** Claimed/assigned to a moderator and under review. */
    IN_REVIEW,

    /** Terminal: a moderation action was taken on the subject. */
    ACTIONED,

    /** Terminal: reviewed and dismissed (no violation found). */
    DISMISSED
}
