package com.taarifu.tokens.application.service;

import com.taarifu.tokens.api.TokenLedgerApi;
import com.taarifu.tokens.domain.model.enums.RewardBehaviour;
import com.taarifu.tokens.domain.model.enums.WalletOwnerType;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Default implementation of the public {@link TokenLedgerApi}, delegating to the internal metering and wallet
 * services (ARCHITECTURE.md §3.2 — the {@code api} package is the only cross-module surface; M17, D18).
 *
 * <p>Responsibility: the seam other modules wire to. It exposes <b>only</b> convenience-metering and
 * reward-crediting — never a balance read on a binding path — so the civic-integrity fence holds at the API
 * boundary (PRD §23.5). It adds no logic beyond delegation; the fence and idempotency live in
 * {@link MeteringService}/{@link WalletService}.</p>
 */
@Service
public class TokenLedgerApiImpl implements TokenLedgerApi {

    private final MeteringService meteringService;
    private final WalletService walletService;

    /**
     * @param meteringService the fenced spend path (free quota first, then tokens).
     * @param walletService   the idempotent grant/earn path.
     */
    public TokenLedgerApiImpl(MeteringService meteringService, WalletService walletService) {
        this.meteringService = meteringService;
        this.walletService = walletService;
    }

    @Override
    public SpendOutcome meter(WalletOwnerType ownerType, UUID ownerId, String actionCode, String roleName,
                              String refEntityType, UUID refEntityId, String idempotencyKey) {
        // The fence (reject binding action codes) and free-quota-first ordering are enforced inside spend().
        return meteringService.spend(ownerType, ownerId, actionCode, roleName,
                refEntityType, refEntityId, idempotencyKey);
    }

    @Override
    public boolean topUp(WalletOwnerType ownerType, UUID accountPublicId, long amount,
                         String paymentReference) {
        // 🔒 FENCE (D18): the ONLY effect is a PURCHASE-kind convenience credit on the wallet. No role/vote/
        // weight is granted here and no balance is returned — purchased tokens buy convenience/reach only.
        // Idempotent on paymentReference (the payments credit_event_id) → exactly-once credit on webhook replay.
        return walletService.purchaseTopUp(ownerType, accountPublicId, amount, paymentReference,
                paymentReference) != null;
    }

    @Override
    public boolean reward(WalletOwnerType ownerType, UUID ownerId, RewardBehaviour behaviour,
                          String refEntityType, UUID refEntityId, String idempotencyKey) {
        return walletService.earn(ownerType, ownerId, behaviour, refEntityType, refEntityId, idempotencyKey)
                != null;
    }
}
