package com.taarifu.tokens.domain.repository;

import com.taarifu.tokens.domain.model.Wallet;
import com.taarifu.tokens.domain.model.WalletFreeQuotaState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data repository for {@link WalletFreeQuotaState} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: get-or-create lookup of the per-(wallet, action) free-quota counter on the metering
 * path. Soft-deleted rows are excluded by the entity's {@code @SQLRestriction}; the DB partial-unique index
 * guarantees at most one live counter per key.</p>
 */
public interface WalletFreeQuotaStateRepository extends JpaRepository<WalletFreeQuotaState, Long> {

    /**
     * @param wallet     the wallet.
     * @param actionCode the metered action.
     * @return the wallet's counter for the action, or empty if not yet created.
     */
    Optional<WalletFreeQuotaState> findByWalletAndActionCode(Wallet wallet, String actionCode);
}
