package com.taarifu.tokens.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

/**
 * Per-wallet, per-action consumption counter for the <b>current free-quota window</b> (PRD §23.1/§23.2 —
 * the recurring allowance that "refreshes automatically").
 *
 * <p>Responsibility: tracks how many free uses of a metered {@code actionCode} a wallet has consumed within
 * the window identified by {@link #windowStart}. The metering service increments {@link #usedCount} when it
 * consumes a free use; when {@code windowStart} no longer matches the policy's current window (because the
 * period rolled over), the counter is reset to the new window — that <b>is</b> the automatic refresh, with
 * no scheduled job (PRD §23.1).</p>
 *
 * <p>WHY a stored {@code windowStart} key (rather than a "resets-at" timer): it is deterministic and
 * idempotent. {@link com.taarifu.tokens.domain.model.enums.QuotaPeriod#windowStart(Instant)} maps any
 * instant to one window start; two concurrent spends in the same window resolve to the same key and
 * contend on the same row via optimistic locking ({@code @Version}, inherited), so the free allowance is
 * never over-granted under a race (PRD §23.5 — anti-abuse, race-safe).</p>
 *
 * <p>WHY this is separate from the ledger: a <i>free</i> use is not a token movement (no balance change),
 * so it must not pollute the {@link TokenTransaction} ledger; the ledger stays a pure record of token
 * value moved. This counter is the free-path bookkeeping that sits in front of the ledger (free quota
 * consumed first, then tokens — PRD §23.2).</p>
 */
@Entity
@Table(name = "wallet_free_quota_state",
        indexes = {
                @Index(name = "ix_wallet_free_quota_lookup", columnList = "wallet_id, action_code")
        })
@SQLRestriction("deleted = false")
public class WalletFreeQuotaState extends BaseEntity {
    // INVARIANT (DB-owned, migration V31): at most ONE non-deleted state row per (wallet_id, action_code)
    // — a partial unique index in SQL; the metering service does a get-or-create on this key.

    /** The wallet whose free uses are counted (FK). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    /** The metered action this counter is for (matches {@link ActionCostPolicy#getActionCode()}). */
    @Column(name = "action_code", nullable = false, length = 64)
    private String actionCode;

    /**
     * UTC start of the window this counter currently belongs to (from {@code QuotaPeriod.windowStart}).
     * When the policy's current window start differs, the counter has rolled over (refresh).
     */
    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    /** Free uses consumed in the current window. Reset to 0 (or 1) when the window rolls over. */
    @Column(name = "used_count", nullable = false)
    private int usedCount = 0;

    /** JPA requires a no-arg constructor; not for application use. */
    protected WalletFreeQuotaState() {
    }

    /**
     * Creates a counter for a wallet/action at the start of a window.
     *
     * @param wallet      the wallet.
     * @param actionCode  the metered action.
     * @param windowStart the current window's UTC start.
     */
    public WalletFreeQuotaState(Wallet wallet, String actionCode, Instant windowStart) {
        this.wallet = wallet;
        this.actionCode = actionCode;
        this.windowStart = windowStart;
        this.usedCount = 0;
    }

    /** @return the owning wallet. */
    public Wallet getWallet() {
        return wallet;
    }

    /** @return the metered action code. */
    public String getActionCode() {
        return actionCode;
    }

    /** @return the current window's UTC start. */
    public Instant getWindowStart() {
        return windowStart;
    }

    /** @return free uses consumed in the current window. */
    public int getUsedCount() {
        return usedCount;
    }

    /**
     * Rolls this counter to a new window, resetting the used count.
     *
     * @param newWindowStart the new window's UTC start.
     */
    public void rollTo(Instant newWindowStart) {
        this.windowStart = newWindowStart;
        this.usedCount = 0;
    }

    /** Records one consumed free use in the current window. */
    public void incrementUsed() {
        this.usedCount += 1;
    }
}
