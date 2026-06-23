package com.taarifu.tokens.application.service;

import com.taarifu.tokens.api.dto.TokenTransactionDto;
import com.taarifu.tokens.api.dto.WalletDto;
import com.taarifu.tokens.application.mapper.TokenMapper;
import com.taarifu.tokens.domain.model.Wallet;
import com.taarifu.tokens.domain.model.enums.WalletOwnerType;
import com.taarifu.tokens.domain.repository.TokenTransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Read-only application service for an owner's <b>own</b> wallet and ledger history (PRD §23.5 transparency).
 *
 * <p>Responsibility: serves {@code GET /me/wallet} and {@code GET /me/wallet/ledger} for the authenticated
 * caller. It provisions the wallet on first read (get-or-create via {@link WalletService}) so a brand-new
 * citizen who has never transacted still sees a zero-balance wallet rather than a 404. The balance is always
 * derived from the ledger (source of truth, PRD §23.1).</p>
 *
 * <p><b>Privacy (PDPA, PRD §18):</b> this service only ever resolves the wallet of the <i>caller's own</i>
 * owner id — the controller passes {@code CurrentUser.publicId()}, never an id from the request — so one
 * citizen can never read another's wallet or ledger. There is deliberately no "get any wallet" read here.</p>
 */
@Service
@Transactional(readOnly = true)
public class WalletQueryService {

    private final WalletService walletService;
    private final TokenTransactionRepository ledger;
    private final TokenMapper mapper;

    /**
     * @param walletService wallet provisioning + balance derivation.
     * @param ledger        ledger history persistence.
     * @param mapper        entity→DTO mapper.
     */
    public WalletQueryService(WalletService walletService,
                              TokenTransactionRepository ledger,
                              TokenMapper mapper) {
        this.walletService = walletService;
        this.ledger = ledger;
        this.mapper = mapper;
    }

    /**
     * Returns the caller's own wallet (creating a zero-balance wallet on first access).
     *
     * @param ownerType owner class (USER for a citizen).
     * @param ownerId   the authenticated caller's public id.
     * @return the wallet DTO with its ledger-derived balance.
     */
    @Transactional
    public WalletDto getOwnWallet(WalletOwnerType ownerType, UUID ownerId) {
        Wallet wallet = walletService.getOrCreateWallet(ownerType, ownerId);
        long balance = walletService.currentBalance(wallet);
        return mapper.toWalletDto(wallet, balance);
    }

    /**
     * Returns the caller's own ledger history, newest first, paged.
     *
     * @param ownerType owner class.
     * @param ownerId   the authenticated caller's public id.
     * @param pageable  bounded paging.
     * @return a page of ledger entry DTOs.
     */
    @Transactional
    public Page<TokenTransactionDto> getOwnLedger(WalletOwnerType ownerType, UUID ownerId, Pageable pageable) {
        Wallet wallet = walletService.getOrCreateWallet(ownerType, ownerId);
        return ledger.findByWalletOrderByIdDesc(wallet, pageable).map(mapper::toTransactionDto);
    }
}
