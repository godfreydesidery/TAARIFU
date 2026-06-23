package com.taarifu.moderation.domain.model.enums;

import java.time.Duration;

/**
 * The triage severity of a {@link com.taarifu.moderation.domain.model.ModerationItem}, which sets its
 * review SLA (PRD §25.8 "Moderation SLAs by severity").
 *
 * <p>Responsibility: maps a queue item's severity to a <b>review-target window</b> so the queue is
 * prioritised by severity (and, later, virality). The SLA targets encode §25.8 directly:</p>
 * <ul>
 *   <li>{@link #CRITICAL} — GBV / safety / illegal → review target ≤ a few hours.</li>
 *   <li>{@link #HIGH} — abuse / PII / spam-at-scale → ≤24h.</li>
 *   <li>{@link #MEDIUM} — abuse / PII / spam → ≤24h.</li>
 *   <li>{@link #LOW} — general → ≤72h.</li>
 * </ul>
 *
 * <p>WHY the SLA lives on the enum (not scattered config): the §25.8 targets are a product-locked policy;
 * centralising them here keeps the queue-deadline computation DRY and the policy reviewable in one place.
 * When SLAs become admin-configurable (M14), this becomes the seeded default.</p>
 */
public enum ModerationSeverity {

    /** General content; review target ≤ 72h (§25.8). */
    LOW(Duration.ofHours(72)),

    /** Abuse/PII/spam; review target ≤ 24h (§25.8). */
    MEDIUM(Duration.ofHours(24)),

    /** Abuse/PII/spam at scale or repeat; review target ≤ 24h (§25.8). */
    HIGH(Duration.ofHours(24)),

    /** GBV / safety / illegal; review target ≤ a few hours (§25.8). */
    CRITICAL(Duration.ofHours(4));

    private final Duration reviewTarget;

    ModerationSeverity(Duration reviewTarget) {
        this.reviewTarget = reviewTarget;
    }

    /**
     * @return the maximum time-to-review window this severity allows, used to stamp the queue item's
     *         {@code slaDueAt} deadline (PRD §25.8).
     */
    public Duration reviewTarget() {
        return reviewTarget;
    }
}
