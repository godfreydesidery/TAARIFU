package com.taarifu.tokens.domain.model.enums;

/**
 * Catalogued good-civic-behaviours that may earn tokens (PRD §23.3 — "earn through good civic behaviour…
 * a positive-behaviour incentive, not a paywall").
 *
 * <p>Responsibility: enumerates the abuse-resistant, server-validated behaviours a {@link
 * com.taarifu.tokens.domain.model.TokenReward} can attach a grant to. Each reward is config-driven
 * (admin-tunable amount + per-period cap, anti-farming) and keyed by one of these codes.</p>
 *
 * <p>Integrity / anti-farming (PRD §23.5):</p>
 * <ul>
 *   <li>Reward-able behaviours must be <b>validated server-side</b> — e.g. {@link #RESOLUTION_CONFIRMED}
 *       credits only on a <i>genuinely confirmed</i> resolution, never a self-confirmed loop.</li>
 *   <li>Earning is <b>capped per period and per behaviour</b>; the idempotent ledger (one idempotency key
 *       per earn) prevents double-credit.</li>
 *   <li>These are <b>incentives, not democratic weight</b>: earning tokens never buys a signature, rating,
 *       or poll outcome — the civic-integrity fence stands regardless of balance (PRD §23 fence).</li>
 * </ul>
 *
 * <p>WHY an enum (not free-text behaviour strings): the earn surface must be a closed, reviewable set so a
 * new earn path is a deliberate, audited decision — not something a caller can invent to mint tokens.</p>
 */
public enum RewardBehaviour {

    /** Completing the user profile to a sufficient degree (one-time, capped). */
    PROFILE_COMPLETED,

    /** Reaching T3 via national/voter-ID verification (one-time, capped). */
    IDENTITY_VERIFIED_T3,

    /** Confirming a resolution that the responder actually resolved (never self-confirmed — anti-farming). */
    RESOLUTION_CONFIRMED,

    /** A contribution (answer/comment) marked helpful/accepted by an eligible party. */
    HELPFUL_CONTRIBUTION,

    /** Crossing a reputation milestone (capped, anti-farming). */
    REPUTATION_MILESTONE
}
