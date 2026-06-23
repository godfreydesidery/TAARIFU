package com.taarifu.analytics.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * A funnel dashboard payload — the ordered step counts plus step-to-step conversion (PRD §3.3
 * Verification; Appendix C "verification funnel (T0→T3)"; Appendix E.2 funnels; M15).
 *
 * <p>Responsibility: the read model for the verification funnel
 * ({@code account_signed_up} → {@code profile_completed} → {@code identity_verification_started} →
 * {@code identity_verified}) and any ordered count funnel. {@code steps} preserves the funnel order; each
 * step carries its absolute count and the conversion rate from the <i>first</i> step (so the client can
 * show "% of signups reaching T3", the §3.3 ≥40% headline).</p>
 *
 * @param name  a label for the funnel (e.g. {@code "VERIFICATION_T0_T3"}).
 * @param from  inclusive window start applied (UTC).
 * @param to    exclusive window end applied (UTC).
 * @param steps the ordered funnel steps, top → bottom.
 */
public record FunnelDto(
        String name,
        Instant from,
        Instant to,
        List<Step> steps
) {

    /**
     * One funnel step.
     *
     * @param step                a label for the step (e.g. {@code "ACCOUNT_SIGNED_UP"}).
     * @param count               absolute number of events at this step in the window.
     * @param conversionFromTop   fraction in {@code [0,1]} of the first step's count that reached this step
     *                            ({@code 1.0} for the first step; {@code 0.0} when the top step is empty).
     */
    public record Step(String step, long count, double conversionFromTop) {
    }
}
