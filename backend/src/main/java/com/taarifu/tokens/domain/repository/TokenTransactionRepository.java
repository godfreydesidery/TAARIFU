package com.taarifu.tokens.domain.repository;

import com.taarifu.tokens.domain.model.TokenTransaction;
import com.taarifu.tokens.domain.model.Wallet;
import com.taarifu.tokens.domain.model.enums.TokenTransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for the append-only {@link TokenTransaction} ledger (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: appends are plain {@code save}s (the entity is immutable); this interface adds the
 * idempotency lookup, balance derivation, the per-wallet history read, and the anti-farming cap count.
 * There are deliberately <b>no</b> update/delete methods — corrections are new rows (PRD §23.1/§23.5).</p>
 */
public interface TokenTransactionRepository extends JpaRepository<TokenTransaction, Long> {

    /**
     * Idempotency guard: finds a prior entry by its globally-unique key.
     *
     * <p>WHY: a grant/earn/spend/purchase carries one key; if the key already exists the operation is a
     * replay and must be a no-op (return the existing result), preventing double-credit/double-spend
     * (PRD §23.5 idempotent ledger).</p>
     *
     * @param idempotencyKey the operation's idempotency key.
     * @return the existing entry, or empty if this is the first time.
     */
    Optional<TokenTransaction> findByIdempotencyKey(String idempotencyKey);

    /**
     * @param idempotencyKey the operation's idempotency key.
     * @return {@code true} if an entry with this key already exists.
     */
    boolean existsByIdempotencyKey(String idempotencyKey);

    /**
     * Derives a wallet's authoritative balance from the ledger (the source of truth, PRD §23.1).
     *
     * <p>Credits ({@code GRANT,EARN,PURCHASE,REFUND}) add their amount; debits ({@code SPEND,EXPIRE})
     * subtract; {@code ADJUST} is stored as a signed contribution by recording its already-signed effect
     * via the running balance — but here, since {@code amount} is a positive magnitude, ADJUST direction is
     * derived from {@code balance_after} continuity at write time. For derivation we therefore sum signed
     * amounts using the type. {@code ADJUST} is summed using the difference it produced (balance_after minus
     * the previous balance) and is excluded from this CASE sum; callers needing an exact recomputation that
     * includes ADJUST use {@link #latestBalanceForWallet}. This method is used as a cross-check.</p>
     *
     * @param walletId internal wallet id.
     * @return the summed balance from typed credit/debit entries (excludes ADJUST), or {@code 0} if none.
     */
    @Query("""
            select coalesce(sum(
                case
                    when t.type in (com.taarifu.tokens.domain.model.enums.TokenTransactionType.GRANT,
                                    com.taarifu.tokens.domain.model.enums.TokenTransactionType.EARN,
                                    com.taarifu.tokens.domain.model.enums.TokenTransactionType.PURCHASE,
                                    com.taarifu.tokens.domain.model.enums.TokenTransactionType.REFUND)
                        then t.amount
                    when t.type in (com.taarifu.tokens.domain.model.enums.TokenTransactionType.SPEND,
                                    com.taarifu.tokens.domain.model.enums.TokenTransactionType.EXPIRE)
                        then -t.amount
                    else 0
                end), 0)
            from TokenTransaction t
            where t.wallet.id = :walletId
            """)
    long sumTypedBalance(@Param("walletId") Long walletId);

    /**
     * Returns the most recent entry's {@code balanceAfter} for a wallet — the running balance that already
     * accounts for every entry including {@code ADJUST}.
     *
     * <p>WHY this is the canonical derivation used by the service: each entry stores the exact balance it
     * produced, so the latest one is the true balance with no sign-reconstruction ambiguity for ADJUST.</p>
     *
     * @param walletId internal wallet id.
     * @return the latest running balance, or empty if the wallet has no entries yet.
     */
    @Query("""
            select t.balanceAfter from TokenTransaction t
            where t.wallet.id = :walletId
            order by t.id desc
            limit 1
            """)
    Optional<Long> latestBalanceForWallet(@Param("walletId") Long walletId);

    /**
     * Paged ledger history for a wallet, newest first.
     *
     * @param wallet   the wallet.
     * @param pageable bounded paging.
     * @return a page of ledger entries.
     */
    Page<TokenTransaction> findByWalletOrderByIdDesc(Wallet wallet, Pageable pageable);

    /**
     * Anti-farming cap support: counts a wallet's entries of a type with a given action/reason code within
     * a window. Used to enforce {@code TokenReward.capCount} per period (PRD §23.5).
     *
     * @param walletId   internal wallet id.
     * @param type       the transaction type (typically {@code EARN}).
     * @param actionCode the behaviour/reason code.
     * @param since      window start (inclusive); entries at/after this instant are counted.
     * @return the count in the window.
     */
    @Query("""
            select count(t) from TokenTransaction t
            where t.wallet.id = :walletId
              and t.type = :type
              and t.actionCode = :actionCode
              and t.createdAt >= :since
            """)
    long countInWindow(@Param("walletId") Long walletId,
                       @Param("type") TokenTransactionType type,
                       @Param("actionCode") String actionCode,
                       @Param("since") Instant since);

    /**
     * @param publicId a ledger entry's public id.
     * @return the entry, or empty.
     */
    Optional<TokenTransaction> findByPublicId(UUID publicId);
}
