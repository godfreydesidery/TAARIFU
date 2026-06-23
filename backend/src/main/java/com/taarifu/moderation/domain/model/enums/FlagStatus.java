package com.taarifu.moderation.domain.model.enums;

/**
 * Lifecycle of a single {@link com.taarifu.moderation.domain.model.Flag} (PRD §18, US-12.1).
 *
 * <p>Responsibility: tracks one citizen's flag from submission to its disposition. Many flags on the
 * same subject collapse into one {@link com.taarifu.moderation.domain.model.ModerationItem}; when that
 * item is actioned or dismissed, its constituent flags transition to {@link #RESOLVED} or
 * {@link #DISMISSED} so the flagger can be given feedback (US-12.1 "feedback").</p>
 */
public enum FlagStatus {

    /** Newly submitted; awaiting (or already attached to) a queue item. */
    OPEN,

    /** The queue item this flag fed was actioned by a moderator. */
    RESOLVED,

    /** The queue item was dismissed (no violation) — the flag did not stand. */
    DISMISSED
}
