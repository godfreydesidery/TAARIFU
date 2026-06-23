package com.taarifu.moderation.application.service;

import com.taarifu.moderation.domain.model.enums.FlagReason;
import com.taarifu.moderation.domain.model.enums.ModerationSeverity;
import org.springframework.stereotype.Component;

/**
 * Maps a {@link FlagReason} to the initial triage {@link ModerationSeverity} that sets a queue item's
 * §25.8 review SLA (PRD §25.8 "Queues prioritised by severity").
 *
 * <p>Responsibility: the single place the §25.8 severity classification lives, so the
 * reason→severity→SLA chain is DRY and reviewable. Safety-critical reasons map high; nuisance reasons map
 * low:</p>
 * <ul>
 *   <li>{@link FlagReason#HARASSMENT} → {@link ModerationSeverity#CRITICAL} (GBV/safety; review ≤ hours).</li>
 *   <li>{@link FlagReason#PII} → {@link ModerationSeverity#HIGH} (privacy harm; ≤24h).</li>
 *   <li>{@link FlagReason#ABUSE}, {@link FlagReason#MISINFORMATION} → {@link ModerationSeverity#MEDIUM} (≤24h).</li>
 *   <li>{@link FlagReason#SPAM}, {@link FlagReason#OTHER} → {@link ModerationSeverity#LOW} (general; ≤72h).</li>
 * </ul>
 *
 * <p>WHY a bean (not a static switch): it is a policy that will become admin-configurable (M14); a bean
 * keeps the call sites stable when the source moves to config, and keeps it mockable in tests.</p>
 */
@Component
public class SeverityPolicy {

    /**
     * Classifies a flag reason into its initial queue severity.
     *
     * @param reason the citizen-supplied flag reason.
     * @return the §25.8-aligned initial severity (never {@code null}).
     */
    public ModerationSeverity initialSeverity(FlagReason reason) {
        return switch (reason) {
            case HARASSMENT -> ModerationSeverity.CRITICAL;
            case PII -> ModerationSeverity.HIGH;
            case ABUSE, MISINFORMATION -> ModerationSeverity.MEDIUM;
            case SPAM, OTHER -> ModerationSeverity.LOW;
        };
    }
}
