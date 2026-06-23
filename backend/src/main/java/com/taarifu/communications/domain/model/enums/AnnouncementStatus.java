package com.taarifu.communications.domain.model.enums;

/**
 * Lifecycle state of an {@link com.taarifu.communications.domain.model.Announcement}
 * (PRD §12 state machine, §9.1, M4).
 *
 * <p>Responsibility: encodes where an announcement is in its publish lifecycle. The PRD-locked machine
 * is {@code DRAFT → (moderation) → SCHEDULED → PUBLISHED → EXPIRED} (PRD §12, UC-G01/G02/G03). A new or
 * untrusted author's announcement is held for moderation before it can leave {@code DRAFT}; the
 * moderation outcome is tracked separately on the entity ({@code moderationHeld}) so this enum stays a
 * pure lifecycle axis and is never conflated with the moderation decision.</p>
 *
 * <p>WHY {@code SCHEDULED} is distinct from {@code PUBLISHED}: an author may compose now and set a
 * future {@code publishAt}; only when that instant passes (and moderation, if any, has cleared) does
 * fan-out occur. Keeping the two states separate lets the scheduler promote {@code SCHEDULED→PUBLISHED}
 * deterministically without re-reading author trust (PRD §12, §13).</p>
 */
public enum AnnouncementStatus {

    /** Composed but not yet released; editable; the only state from which an author edits freely. */
    DRAFT,

    /** Approved/queued with a future {@code publishAt}; awaits its publish instant before fan-out. */
    SCHEDULED,

    /** Live and visible in matching subscribers' feeds (and dispatched per channel/preference). */
    PUBLISHED,

    /** Past its {@code expireAt}; retained for history/audit but excluded from feeds (PRD §22.6). */
    EXPIRED
}
