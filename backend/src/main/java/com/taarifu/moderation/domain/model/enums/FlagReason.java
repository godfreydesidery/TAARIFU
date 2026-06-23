package com.taarifu.moderation.domain.model.enums;

/**
 * Why a citizen flagged content (PRD §18, US-12.1; ARCHITECTURE.md §3.1).
 *
 * <p>Responsibility: the closed set of flag reasons offered to citizens (US-12.1 "flag reasons"). The
 * reason both feeds the abuse-report-rate KPI ({@code content_flagged.flag_reason}, PRD Appendix) and
 * seeds the default {@link ModerationSeverity} when a flag opens a queue item — e.g. {@link #HARASSMENT}
 * is treated as safety-critical, {@link #SPAM} as low (the mapping lives in the queue service, not here).</p>
 *
 * <p>WHY string-persisted + DB {@code CHECK}-guarded: a stable, localisable (SW/EN) reason vocabulary the
 * UI renders and analytics branch on; append-only.</p>
 */
public enum FlagReason {

    /** Abusive/offensive content. */
    ABUSE,

    /** Unsolicited / repetitive spam. */
    SPAM,

    /** Exposed personal data (PII) — privacy harm (PDPA, §25.1). */
    PII,

    /** Targeted harassment / threat — safety-critical (fast-track SLA, §25.8). */
    HARASSMENT,

    /** False or misleading information (handled neutrally in election periods — §18). */
    MISINFORMATION,

    /** Any other reason captured as free-text detail on the flag. */
    OTHER
}
