package com.taarifu.tokens.domain.model;

import com.taarifu.tokens.domain.model.enums.TokenTransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * One immutable, append-only entry in the token ledger — the <b>source of truth for balances</b>
 * (PRD §23.1, §23.4; D18).
 *
 * <p>Responsibility: records a single token movement (grant/earn/spend/purchase/refund/expire/adjust) with
 * the running {@link #balanceAfter} it produced, the {@link #idempotencyKey} that makes it post exactly
 * once, and an optional reference to the entity that caused it. A wallet's balance is the sum of its
 * entries; {@link Wallet#getCachedBalance()} is only a cache of that sum.</p>
 *
 * <p>WHY this is <b>not</b> a {@link com.taarifu.common.domain.model.BaseEntity} (mirrors {@code
 * AuditEvent}, AUTH-DESIGN §11): a ledger entry is never updated or soft-deleted — it has no {@code
 * version}, no {@code updated_*}, no {@code deleted} columns. A correction is a <i>new</i> {@link
 * TokenTransactionType#ADJUST}/{@link TokenTransactionType#REFUND} row, never an edit. The application is
 * granted only {@code INSERT}+{@code SELECT} on this table (the migration documents this); columns are
 * {@code updatable = false}.</p>
 *
 * <p>WHY {@link #idempotencyKey} is globally unique (not per-wallet): a single business operation (one
 * grant, one spend, one purchase settlement, one reward) carries one key; a replay — e.g. a duplicated or
 * out-of-order mobile-money webhook, or a retried request — hits the unique constraint and is swallowed as
 * a no-op, so there is <b>no double-credit and no double-spend</b> (PRD §23.5 idempotent ledger). The key
 * embeds the action so two different operations never collide.</p>
 *
 * <p><b>Fence note (D18):</b> {@link #actionCode} meters convenience/volume/reach/commercial actions only.
 * No binding democratic action (sign petition / rate rep / binding poll) ever produces a SPEND here —
 * those endpoints must not invoke metering at all (enforced in {@code MeteringService} and unit-tested).</p>
 */
@Entity
@Table(name = "token_transaction",
        uniqueConstraints = @UniqueConstraint(name = "uq_token_tx_idempotency", columnNames = "idempotency_key"),
        indexes = {
                @Index(name = "ix_token_tx_wallet_time", columnList = "wallet_id, created_at"),
                @Index(name = "ix_token_tx_type", columnList = "type"),
                @Index(name = "ix_token_tx_ref", columnList = "ref_entity_type, ref_entity_id")
        })
public class TokenTransaction {

    /** Internal surrogate PK (append-only; never exposed). */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /** Public, non-enumerable id exposed in ledger DTOs (PRD §17). */
    @Column(name = "public_id", updatable = false, nullable = false, unique = true)
    private UUID publicId;

    /** The wallet this entry moves (FK). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false, updatable = false)
    private Wallet wallet;

    /** The movement kind; fixes the sign and meaning of {@link #amount} (see enum doc). */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", updatable = false, nullable = false, length = 16)
    private TokenTransactionType type;

    /**
     * Magnitude of the movement in whole tokens, <b>always positive</b>; the {@link #type} carries the
     * sign. Stored as the magnitude so a corrupt sign can never be confused with a corrupt type.
     */
    @Column(name = "amount", updatable = false, nullable = false)
    private long amount;

    /** The wallet's balance immediately after this entry was applied (running balance / audit aid). */
    @Column(name = "balance_after", updatable = false, nullable = false)
    private long balanceAfter;

    /**
     * The metered action code (e.g. {@code FILE_REPORT}, {@code ASK_REP}, {@code BOOST_REPORT}) for a
     * SPEND, or the behaviour/reason for a GRANT/EARN; free-form but drawn from the policy catalogue.
     * Never a binding democratic action (fence, D18).
     */
    @Column(name = "action_code", updatable = false, length = 64)
    private String actionCode;

    /** Human/machine reason for grants, adjustments, refunds (e.g. {@code SIGNUP_GRANT}); never PII. */
    @Column(name = "reason", updatable = false, length = 255)
    private String reason;

    /** Type of the entity that caused this movement (e.g. {@code REPORT}, {@code PETITION}, {@code PAYMENT}). */
    @Column(name = "ref_entity_type", updatable = false, length = 48)
    private String refEntityType;

    /** Public id of the causing entity, referenced by id only (cross-module, no FK — §3.2). */
    @Column(name = "ref_entity_id", updatable = false)
    private UUID refEntityId;

    /** The idempotency key that makes this entry post exactly once (globally unique). */
    @Column(name = "idempotency_key", updatable = false, nullable = false, length = 200)
    private String idempotencyKey;

    /** Append instant (UTC), set once on persist. */
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    /** {@code publicId} of the actor that caused the entry (system/admin/owner); never PII. */
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /** JPA requires a no-arg constructor; not for application use. Use {@link Builder}. */
    protected TokenTransaction() {
    }

    /** Assigns the public id before insert if absent (mirrors {@code BaseEntity} contract). */
    @PrePersist
    void onPersist() {
        if (this.publicId == null) {
            this.publicId = UUID.randomUUID();
        }
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    /** @return internal numeric PK (backend-only). */
    public Long getId() {
        return id;
    }

    /** @return public id used in ledger DTOs. */
    public UUID getPublicId() {
        return publicId;
    }

    /** @return the owning wallet. */
    public Wallet getWallet() {
        return wallet;
    }

    /** @return the movement type. */
    public TokenTransactionType getType() {
        return type;
    }

    /** @return positive magnitude in whole tokens. */
    public long getAmount() {
        return amount;
    }

    /** @return the wallet balance immediately after this entry. */
    public long getBalanceAfter() {
        return balanceAfter;
    }

    /** @return the metered action code / reason code, or {@code null}. */
    public String getActionCode() {
        return actionCode;
    }

    /** @return the human/machine reason, or {@code null}. */
    public String getReason() {
        return reason;
    }

    /** @return the causing entity's type, or {@code null}. */
    public String getRefEntityType() {
        return refEntityType;
    }

    /** @return the causing entity's public id, or {@code null}. */
    public UUID getRefEntityId() {
        return refEntityId;
    }

    /** @return the globally-unique idempotency key. */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /** @return append instant (UTC). */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @return actor public id, or {@code null}. */
    public UUID getCreatedBy() {
        return createdBy;
    }

    /**
     * Fluent builder for an immutable ledger entry.
     *
     * <p>WHY a builder (not a long constructor): a ledger entry has several optional reference fields and
     * is constructed only by the wallet/metering services; a builder keeps each call site readable and the
     * entity's setters package-private/absent so the row stays immutable once persisted.</p>
     */
    public static final class Builder {
        private final TokenTransaction tx = new TokenTransaction();

        /**
         * @param wallet the wallet to move.
         * @param type   the movement type.
         * @param amount positive magnitude in whole tokens.
         */
        public static Builder of(Wallet wallet, TokenTransactionType type, long amount) {
            Builder b = new Builder();
            b.tx.wallet = wallet;
            b.tx.type = type;
            b.tx.amount = amount;
            return b;
        }

        /** @param balanceAfter the post-application balance. @return this builder. */
        public Builder balanceAfter(long balanceAfter) {
            tx.balanceAfter = balanceAfter;
            return this;
        }

        /** @param actionCode metered action / reason code. @return this builder. */
        public Builder actionCode(String actionCode) {
            tx.actionCode = actionCode;
            return this;
        }

        /** @param reason human/machine reason. @return this builder. */
        public Builder reason(String reason) {
            tx.reason = reason;
            return this;
        }

        /**
         * @param refType causing entity type.
         * @param refId   causing entity public id.
         * @return this builder.
         */
        public Builder ref(String refType, UUID refId) {
            tx.refEntityType = refType;
            tx.refEntityId = refId;
            return this;
        }

        /** @param idempotencyKey the globally-unique key. @return this builder. */
        public Builder idempotencyKey(String idempotencyKey) {
            tx.idempotencyKey = idempotencyKey;
            return this;
        }

        /** @param actorPublicId the causing actor. @return this builder. */
        public Builder actor(UUID actorPublicId) {
            tx.createdBy = actorPublicId;
            return this;
        }

        /** @return the assembled, not-yet-persisted entry. */
        public TokenTransaction build() {
            return tx;
        }
    }
}
