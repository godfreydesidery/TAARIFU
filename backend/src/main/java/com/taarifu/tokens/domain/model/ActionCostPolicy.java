package com.taarifu.tokens.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.tokens.domain.model.enums.QuotaPeriod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

/**
 * Admin-tunable configuration of <b>what a metered action costs and how much of it is free</b>, per
 * action code and per role (PRD §23.4 — "{@code ActionCost} / {@code FreeQuotaPolicy} (config): per
 * actionCode × role → cost + free-quota (period, count)").
 *
 * <p>Responsibility: one row defines, for a given {@code (actionCode, roleName)} pair: the recurring free
 * allowance ({@link #freeQuotaPeriod} × {@link #freeQuotaCount}) consumed before any tokens, and the
 * per-use {@link #tokenCost} charged after the free allowance is exhausted. The metering service consumes
 * the free quota <b>first</b>, then tokens — never the other way round (PRD §23.2; the free path is never
 * hidden).</p>
 *
 * <p>WHY {@code (actionCode, roleName)} keys a policy: costs and quotas legitimately differ by role — a
 * citizen filing a report has a generous free quota; an org authoring campaigns is metered harder
 * (PRD §23.2). A {@code null} {@link #roleName} is the <b>default/fallback</b> policy for an action when no
 * role-specific row exists, so the catalogue stays small.</p>
 *
 * <p><b>Civic-integrity fence (D18):</b> binding democratic actions (sign petition / rate rep / binding
 * poll) must have <b>no cost policy that gates them on balance</b>. By convention those actions are never
 * routed through metering at all (enforced in {@code MeteringService}); a policy row for them, if any,
 * exists only to express an "effectively free" quota and can never block participation (PRD §23.2/§23.5).</p>
 *
 * <p>WHY versioned + {@link #active}: costs/quotas are "admin-tunable; versioned" (PRD §23.4). Rather than
 * mutate a live row (which would retro-change past metering semantics), a change deactivates the old row
 * and inserts a new {@link #policyVersion}; the active row is the one used for new spends. History is thus
 * preserved (equity/transparency — PRD §23.5).</p>
 */
@Entity
@Table(name = "action_cost_policy",
        indexes = {
                @Index(name = "ix_action_cost_policy_lookup", columnList = "action_code, role_name, active")
        })
@SQLRestriction("deleted = false")
public class ActionCostPolicy extends BaseEntity {
    // INVARIANT (DB-owned, migration V32): at most ONE active, non-deleted policy per
    // (action_code, role_name) — a partial unique index the migration declares; JPA cannot express
    // "unique only where active = true", so it lives in SQL (mirrors geography ux_ward_constituency_current).

    /** The metered action this policy governs (e.g. {@code FILE_REPORT}, {@code ASK_REP}, {@code BOOST_REPORT}). */
    @Column(name = "action_code", nullable = false, length = 64)
    private String actionCode;

    /**
     * The role this policy applies to (e.g. {@code CITIZEN}, {@code ORG_ADMIN}), or {@code null} for the
     * default/fallback policy used when no role-specific row exists. Stored as the role name string to
     * avoid a cross-module enum dependency on identity's {@code RoleName} (referenced by value, §3.2).
     */
    @Column(name = "role_name", length = 32)
    private String roleName;

    /** Tokens charged per use <b>after</b> the free allowance is exhausted; {@code 0} = always free. */
    @Column(name = "token_cost", nullable = false)
    private long tokenCost;

    /** Recurrence of the free allowance (DAILY/WEEKLY/MONTHLY/LIFETIME). */
    @Enumerated(EnumType.STRING)
    @Column(name = "free_quota_period", nullable = false, length = 16)
    private QuotaPeriod freeQuotaPeriod = QuotaPeriod.DAILY;

    /** Free uses allowed per {@link #freeQuotaPeriod} before tokens are charged; {@code 0} = no free quota. */
    @Column(name = "free_quota_count", nullable = false)
    private int freeQuotaCount;

    /** Monotonic version of this policy for the {@code (actionCode, roleName)} key (audit/transparency). */
    @Column(name = "policy_version", nullable = false)
    private int policyVersion = 1;

    /**
     * Whether this is the policy currently in force for its key. Exactly one active row per
     * {@code (actionCode, roleName)} (DB unique constraint includes {@code active}); superseded rows are
     * set inactive, never deleted.
     */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** JPA requires a no-arg constructor; not for application use. */
    protected ActionCostPolicy() {
    }

    /**
     * Defines a cost/quota policy.
     *
     * @param actionCode      the metered action.
     * @param roleName        the role (or {@code null} for the default).
     * @param tokenCost       per-use token cost after free quota.
     * @param freeQuotaPeriod free-allowance recurrence.
     * @param freeQuotaCount  free uses per period.
     */
    public ActionCostPolicy(String actionCode, String roleName, long tokenCost,
                            QuotaPeriod freeQuotaPeriod, int freeQuotaCount) {
        this.actionCode = actionCode;
        this.roleName = roleName;
        this.tokenCost = tokenCost;
        this.freeQuotaPeriod = freeQuotaPeriod;
        this.freeQuotaCount = freeQuotaCount;
        this.active = true;
        this.policyVersion = 1;
    }

    /** @return the metered action code. */
    public String getActionCode() {
        return actionCode;
    }

    /** @return the role name, or {@code null} for the default policy. */
    public String getRoleName() {
        return roleName;
    }

    /** @return per-use token cost after free quota. */
    public long getTokenCost() {
        return tokenCost;
    }

    /** @return free-allowance recurrence. */
    public QuotaPeriod getFreeQuotaPeriod() {
        return freeQuotaPeriod;
    }

    /** @return free uses per period. */
    public int getFreeQuotaCount() {
        return freeQuotaCount;
    }

    /** @return policy version for its key. */
    public int getPolicyVersion() {
        return policyVersion;
    }

    /** @return whether this policy is currently in force. */
    public boolean isActive() {
        return active;
    }

    /** Updates the per-use cost (admin tuning). */
    public void setTokenCost(long tokenCost) {
        this.tokenCost = tokenCost;
    }

    /** Updates the free-quota recurrence (admin tuning). */
    public void setFreeQuotaPeriod(QuotaPeriod freeQuotaPeriod) {
        this.freeQuotaPeriod = freeQuotaPeriod;
    }

    /** Updates the free-quota count (admin tuning). */
    public void setFreeQuotaCount(int freeQuotaCount) {
        this.freeQuotaCount = freeQuotaCount;
    }

    /** Marks this policy inactive (superseded by a new version); never delete a priced policy. */
    public void deactivate() {
        this.active = false;
    }

    /** Sets the version (used when superseding). */
    public void setPolicyVersion(int policyVersion) {
        this.policyVersion = policyVersion;
    }
}
