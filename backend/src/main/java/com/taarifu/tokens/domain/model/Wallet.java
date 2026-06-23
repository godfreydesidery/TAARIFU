package com.taarifu.tokens.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.tokens.domain.model.enums.WalletOwnerType;
import com.taarifu.tokens.domain.model.enums.WalletStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

/**
 * A token balance held by exactly one owner — a citizen {@code User} or an organisation/service-provider
 * (PRD §23.1, §23.4; M17, D18).
 *
 * <p>Responsibility: the wallet is the anchor for an owner's token economy. Its authoritative balance is
 * the <b>sum of its append-only {@link TokenTransaction} ledger</b> (PRD §23.1 — "balance =
 * derived/cached"); {@link #cachedBalance} on this row is only a denormalised read-cache kept in step with
 * the ledger inside the same transaction. The wallet also gates spend via its {@link #status}.</p>
 *
 * <p>WHY {@code (ownerType, ownerId)} not a hard FK: owners live in other modules (identity/responders);
 * the tokens module references them by public {@code UUID} only and never reaches across the boundary
 * (ARCHITECTURE.md §3.2). A unique {@code (owner_type, owner_id)} constraint enforces <b>one wallet per
 * owner</b>.</p>
 *
 * <p>WHY {@code cachedBalance} is recomputed-and-persisted under the ledger's transaction (not trusted
 * blindly): the ledger is the source of truth, so the cache is always derivable; persisting the new
 * balance with optimistic locking ({@code @Version}, inherited) makes concurrent spends serialise — a
 * stale read loses on commit and retries, preventing a double-spend that a naive read-then-write would
 * allow (PRD §23.5 idempotent/ledger-driven; ARCHITECTURE.md §4.2 optimistic lock).</p>
 *
 * <p><b>Civic-integrity fence (binding, D18 / PRD §23 fence):</b> a wallet balance meters convenience,
 * volume, reach, and commercial features only. It is <b>never</b> read in the authorization path of a
 * binding democratic action (sign petition / rate rep / binding poll) — those check tier + electoral scope
 * + one-per-person only. This entity therefore exposes no API that a binding-action endpoint could consult
 * for gating.</p>
 */
@Entity
@Table(name = "wallet",
        uniqueConstraints = @UniqueConstraint(name = "uq_wallet_owner", columnNames = {"owner_type", "owner_id"}),
        indexes = {
                @Index(name = "ix_wallet_owner", columnList = "owner_type, owner_id"),
                @Index(name = "ix_wallet_status", columnList = "status")
        })
@SQLRestriction("deleted = false")
public class Wallet extends BaseEntity {

    /** Whether the owner is a citizen account or an organisation/provider (drives policy lookups). */
    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 16)
    private WalletOwnerType ownerType;

    /**
     * Public {@code UUID} of the owning account in its home module (identity/responders). Referenced by id
     * only — never an FK across the module boundary (ARCHITECTURE.md §3.2).
     */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /** Operating status; only {@link WalletStatus#ACTIVE} may spend/earn (anti-abuse — PRD §23.5). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private WalletStatus status = WalletStatus.ACTIVE;

    /**
     * Denormalised cache of the ledger sum, maintained atomically with each appended {@link
     * TokenTransaction}. Authoritative balance is always the ledger; this column avoids summing the whole
     * log on every read (PRD §23.1). Tokens are whole units — a non-negative {@code long}.
     */
    @Column(name = "cached_balance", nullable = false)
    private long cachedBalance = 0L;

    /** JPA requires a no-arg constructor; not for application use. */
    protected Wallet() {
    }

    /**
     * Opens a new wallet for an owner with a zero balance.
     *
     * @param ownerType the owner class (USER/ORGANIZATION).
     * @param ownerId   the owner's public id in its home module.
     */
    public Wallet(WalletOwnerType ownerType, UUID ownerId) {
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.status = WalletStatus.ACTIVE;
        this.cachedBalance = 0L;
    }

    /** @return the owner class. */
    public WalletOwnerType getOwnerType() {
        return ownerType;
    }

    /** @return the owner's public id (home-module reference). */
    public UUID getOwnerId() {
        return ownerId;
    }

    /** @return the wallet's operating status. */
    public WalletStatus getStatus() {
        return status;
    }

    /** @return the cached ledger balance (always equal to the ledger sum after a committed transaction). */
    public long getCachedBalance() {
        return cachedBalance;
    }

    /**
     * Sets the cached balance to a freshly computed ledger sum. Called only by the wallet/metering service
     * inside the same transaction that appends the ledger entry, so the cache never diverges.
     *
     * @param newBalance the post-transaction balance (the new ledger sum).
     */
    public void applyBalance(long newBalance) {
        this.cachedBalance = newBalance;
    }

    /**
     * Sets the wallet status (admin/anti-abuse). Freezing blocks token spend but never the citizen's free
     * civic path (PRD §23.5).
     *
     * @param status the new status.
     */
    public void setStatus(WalletStatus status) {
        this.status = status;
    }

    /** @return {@code true} if the wallet may currently spend/earn tokens. */
    public boolean isActive() {
        return status == WalletStatus.ACTIVE;
    }
}
