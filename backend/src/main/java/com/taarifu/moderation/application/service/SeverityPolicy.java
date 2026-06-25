package com.taarifu.moderation.application.service;

import com.taarifu.moderation.domain.model.enums.ContentSignal;
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
 * <p>It also classifies an auto-assist {@link ContentSignal} into the same severity ladder
 * ({@link #severityForSignal}) so auto-held items (US-12.3) reuse the §25.8 SLA chain rather than a parallel
 * policy (DRY).</p>
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

    /**
     * Classifies an auto-assist content-safety {@link ContentSignal} into the queue severity it should
     * <i>hold-and-prioritise</i> at (US-12.3, UC-H05) — reusing the same §25.8 severity→SLA chain as a
     * citizen flag so auto-assisted items sit in the same prioritised queue (DRY; no parallel SLA policy).
     *
     * <p>WHY these mappings: {@link ContentSignal#PII} is doxxing/privacy harm → {@code HIGH} (≤24h, the
     * R20 doxxing screen); {@link ContentSignal#PROFANITY} (the abuse/hate/threat band, including the
     * high-confidence GBV-sensitivity path of §25.3) → {@code MEDIUM} so a human sees it within ≤24h;
     * {@link ContentSignal#SPAM}/{@link ContentSignal#IMAGE} → {@code LOW} (≤72h, general). Auto-assist
     * <b>only raises severity, never removes content</b> (D-Q8, R21) — this is the priority it surfaces a
     * <i>human review</i> at, not an action.</p>
     *
     * @param signal the auto-assist signal raised.
     * @return the §25.8-aligned hold severity (never {@code null}).
     */
    public ModerationSeverity severityForSignal(ContentSignal signal) {
        return switch (signal) {
            case PII -> ModerationSeverity.HIGH;
            case PROFANITY -> ModerationSeverity.MEDIUM;
            case SPAM, IMAGE -> ModerationSeverity.LOW;
        };
    }
}
