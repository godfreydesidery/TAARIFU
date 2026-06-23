package com.taarifu.tokens.application.mapper;

import com.taarifu.tokens.api.dto.ActionCostPolicyDto;
import com.taarifu.tokens.api.dto.TokenRewardDto;
import com.taarifu.tokens.api.dto.TokenTransactionDto;
import com.taarifu.tokens.api.dto.WalletDto;
import com.taarifu.tokens.domain.model.ActionCostPolicy;
import com.taarifu.tokens.domain.model.TokenReward;
import com.taarifu.tokens.domain.model.TokenTransaction;
import com.taarifu.tokens.domain.model.Wallet;
import org.springframework.stereotype.Component;

/**
 * Maps tokens entities to their boundary DTOs (ARCHITECTURE.md §3.3, CLAUDE.md §8).
 *
 * <p>Responsibility: the single translation layer ensuring <b>entities never leave the module</b> and that
 * only public ids (never internal {@code Long} ids) are exposed (ADR-0006). Hand-written {@code @Component}
 * mapper, matching the geography slice precedent (trivial, explicit, no annotation-processor dependency).</p>
 *
 * <p>WHY the wallet DTO takes a balance argument rather than reading {@code wallet.getCachedBalance()}: the
 * authoritative balance is the ledger (PRD §23.1); the caller passes the freshly derived balance so a DTO
 * can never serve a stale cache.</p>
 */
@Component
public class TokenMapper {

    /**
     * @param wallet  the wallet.
     * @param balance the authoritative (ledger-derived) balance to expose.
     * @return the owner-safe wallet DTO.
     */
    public WalletDto toWalletDto(Wallet wallet, long balance) {
        return new WalletDto(
                wallet.getPublicId(),
                wallet.getOwnerId(),
                balance,
                wallet.getStatus().name());
    }

    /**
     * @param tx a ledger entry.
     * @return the ledger DTO.
     */
    public TokenTransactionDto toTransactionDto(TokenTransaction tx) {
        return new TokenTransactionDto(
                tx.getPublicId(),
                tx.getType().name(),
                tx.getAmount(),
                tx.getBalanceAfter(),
                tx.getActionCode(),
                tx.getReason(),
                tx.getRefEntityType(),
                tx.getRefEntityId(),
                tx.getCreatedAt());
    }

    /**
     * @param policy a cost/quota policy.
     * @return the policy DTO.
     */
    public ActionCostPolicyDto toPolicyDto(ActionCostPolicy policy) {
        return new ActionCostPolicyDto(
                policy.getPublicId(),
                policy.getActionCode(),
                policy.getRoleName(),
                policy.getTokenCost(),
                policy.getFreeQuotaPeriod().name(),
                policy.getFreeQuotaCount(),
                policy.getPolicyVersion(),
                policy.isActive());
    }

    /**
     * @param reward a behaviour reward config.
     * @return the reward DTO.
     */
    public TokenRewardDto toRewardDto(TokenReward reward) {
        return new TokenRewardDto(
                reward.getPublicId(),
                reward.getBehaviour().name(),
                reward.getGrantAmount(),
                reward.getCapCount(),
                reward.getCapPeriod().name(),
                reward.isActive());
    }
}
