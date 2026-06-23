package com.taarifu.tokens.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.tokens.domain.model.enums.QuotaPeriod;
import com.taarifu.tokens.domain.model.enums.RewardBehaviour;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

/**
 * Admin-tunable configuration of <b>how many tokens a good-civic-behaviour earns, and the anti-farming
 * cap</b> on earning it (PRD §23.3, §23.4 — "{@code TokenReward} (config): per behaviour → token grant +
 * caps (anti-farming)").
 *
 * <p>Responsibility: one row maps a {@link RewardBehaviour} to a {@link #grantAmount} and a cap of
 * {@link #capCount} grants per {@link #capPeriod}. The wallet service consults this before crediting an
 * EARN, and refuses (or no-ops) once the cap for the period is reached.</p>
 *
 * <p><b>Anti-farming (PRD §23.5):</b> earning is "capped per period and per behaviour; reward-able actions
 * are validated (e.g. a <i>confirmed</i> resolution, not a self-confirmed loop); idempotent ledger prevents
 * double-credit." This entity owns the per-behaviour cap; the <i>validation</i> that the behaviour really
 * happened is the calling module's responsibility, and the idempotency key on each EARN prevents replay.</p>
 *
 * <p><b>Fence note (D18):</b> earning tokens is a positive-behaviour incentive, never democratic weight —
 * a larger balance earned here can still buy only convenience/reach, never a signature/rating/poll outcome
 * (PRD §23 fence).</p>
 */
@Entity
@Table(name = "token_reward",
        indexes = {
                @Index(name = "ix_token_reward_behaviour", columnList = "behaviour, active")
        })
@SQLRestriction("deleted = false")
public class TokenReward extends BaseEntity {
    // INVARIANT (DB-owned, migration V32): at most ONE active, non-deleted reward per behaviour —
    // a partial unique index declared in SQL (JPA cannot express "unique where active = true").

    /** The civic behaviour that triggers this reward. */
    @Enumerated(EnumType.STRING)
    @Column(name = "behaviour", nullable = false, length = 48)
    private RewardBehaviour behaviour;

    /** Tokens granted each time the (validated) behaviour occurs, within the cap. Positive. */
    @Column(name = "grant_amount", nullable = false)
    private long grantAmount;

    /** Anti-farming cap: maximum number of times this reward may be earned per {@link #capPeriod}. */
    @Column(name = "cap_count", nullable = false)
    private int capCount;

    /** The period over which {@link #capCount} is enforced (e.g. LIFETIME for one-time rewards). */
    @Enumerated(EnumType.STRING)
    @Column(name = "cap_period", nullable = false, length = 16)
    private QuotaPeriod capPeriod = QuotaPeriod.LIFETIME;

    /** Whether this reward is currently active; superseded rows are deactivated, never deleted. */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** JPA requires a no-arg constructor; not for application use. */
    protected TokenReward() {
    }

    /**
     * Defines a behaviour reward.
     *
     * @param behaviour   the triggering civic behaviour.
     * @param grantAmount tokens granted per occurrence.
     * @param capCount    max grants per {@code capPeriod} (anti-farming).
     * @param capPeriod   the cap window.
     */
    public TokenReward(RewardBehaviour behaviour, long grantAmount, int capCount, QuotaPeriod capPeriod) {
        this.behaviour = behaviour;
        this.grantAmount = grantAmount;
        this.capCount = capCount;
        this.capPeriod = capPeriod;
        this.active = true;
    }

    /** @return the triggering behaviour. */
    public RewardBehaviour getBehaviour() {
        return behaviour;
    }

    /** @return tokens granted per occurrence. */
    public long getGrantAmount() {
        return grantAmount;
    }

    /** @return max grants per cap period. */
    public int getCapCount() {
        return capCount;
    }

    /** @return the cap window. */
    public QuotaPeriod getCapPeriod() {
        return capPeriod;
    }

    /** @return whether the reward is active. */
    public boolean isActive() {
        return active;
    }

    /** Updates the grant amount (admin tuning). */
    public void setGrantAmount(long grantAmount) {
        this.grantAmount = grantAmount;
    }

    /** Updates the anti-farming cap count (admin tuning). */
    public void setCapCount(int capCount) {
        this.capCount = capCount;
    }

    /** Updates the cap window (admin tuning). */
    public void setCapPeriod(QuotaPeriod capPeriod) {
        this.capPeriod = capPeriod;
    }

    /** Marks this reward inactive (superseded); never delete a reward config. */
    public void deactivate() {
        this.active = false;
    }
}
