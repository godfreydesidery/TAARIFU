package com.taarifu.tokens.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.pagination.PageMapper;
import com.taarifu.common.pagination.PageRequestFactory;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.tokens.api.dto.TokenTransactionDto;
import com.taarifu.tokens.api.dto.WalletDto;
import com.taarifu.tokens.application.service.WalletQueryService;
import com.taarifu.tokens.domain.model.enums.WalletOwnerType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Authenticated REST surface for a citizen's <b>own</b> wallet and ledger history (PRD §23.1, §23.5
 * transparency; M17).
 *
 * <p>Responsibility: the thin HTTP layer for {@code GET /me/wallet} and {@code GET /me/wallet/ledger}. It
 * resolves the caller from the security context and delegates to {@link WalletQueryService}, wrapping every
 * result in the single {@link ApiResponse} envelope. It contains no business logic and no
 * {@code @Transactional} (ARCHITECTURE.md §3.3).</p>
 *
 * <p><b>Privacy / own-only (PDPA, PRD §18):</b> every method derives the owner id from
 * {@link CurrentUser#requirePublicId()} — <b>never</b> from a path/query parameter — so a citizen can only
 * ever see their <i>own</i> wallet and ledger; there is no "read another's wallet" endpoint here. The owner
 * type is {@link WalletOwnerType#USER} (the authenticated principal is a user account); organisation
 * wallets are addressed through their own workspace endpoints in a later increment.</p>
 *
 * <p><b>Fence note (D18):</b> nothing here exposes a way to spend on, or gate, a binding democratic action;
 * the wallet is read-only on this surface (balance/ledger transparency only).</p>
 */
@RestController
@RequestMapping(path = "/me/wallet")
@Tag(name = "Wallet", description = "A citizen's own token wallet and ledger (transparency; never democratic weight).")
public class WalletController {

    private final WalletQueryService walletQueryService;
    private final ResponseFactory responses;
    private final PageRequestFactory pageRequests;
    private final PageMapper pageMapper;

    /**
     * @param walletQueryService own-wallet/ledger reads.
     * @param responses          envelope builder.
     * @param pageRequests       safe pageable factory (size caps).
     * @param pageMapper         {@code Page}→{@code PageMeta} adapter.
     */
    public WalletController(WalletQueryService walletQueryService,
                           ResponseFactory responses,
                           PageRequestFactory pageRequests,
                           PageMapper pageMapper) {
        this.walletQueryService = walletQueryService;
        this.responses = responses;
        this.pageRequests = pageRequests;
        this.pageMapper = pageMapper;
    }

    /**
     * Returns the authenticated caller's own wallet (provisioned on first access with a zero balance).
     *
     * @return an envelope carrying the {@link WalletDto}.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get my wallet",
            description = "Returns the authenticated citizen's own token wallet (balance + status).")
    public ApiResponse<WalletDto> getMyWallet() {
        UUID ownerId = CurrentUser.requirePublicId();
        return responses.ok(walletQueryService.getOwnWallet(WalletOwnerType.USER, ownerId));
    }

    /**
     * Returns the authenticated caller's own ledger history, newest first, paged.
     *
     * @param page zero-based page index.
     * @param size page size (capped at 100).
     * @param sort sort expression (default newest-first is applied by the service).
     * @return a paged envelope of {@link TokenTransactionDto}.
     */
    @GetMapping("/ledger")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get my token ledger",
            description = "Paged, newest-first history of the caller's own token transactions.")
    public ApiResponse<List<TokenTransactionDto>> getMyLedger(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        UUID ownerId = CurrentUser.requirePublicId();
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<TokenTransactionDto> result =
                walletQueryService.getOwnLedger(WalletOwnerType.USER, ownerId, pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }
}
