package com.taarifu.moderation.domain.model.enums;

/**
 * Lifecycle of an {@link com.taarifu.moderation.domain.model.Appeal} against a moderation action
 * (PRD §25.8, UC-H03; Appendix {@code moderation_appeal_resolved.outcome}).
 *
 * <p>Responsibility: tracks an appeal from filing to outcome. An {@link #OPEN} appeal is decided by a
 * <b>different</b> moderator than the one who took the original action (appeal independence, §25.8,
 * Appendix F footnote ᵉ), reaching {@link #UPHELD} (original action stands) or {@link #OVERTURNED}
 * (original action reversed). The two outcomes mirror the analytics contract exactly.</p>
 */
public enum AppealStatus {

    /** Filed and awaiting an independent moderator's decision. */
    OPEN,

    /** Decided: the original moderation action stands. */
    UPHELD,

    /** Decided: the original moderation action is reversed. */
    OVERTURNED
}
