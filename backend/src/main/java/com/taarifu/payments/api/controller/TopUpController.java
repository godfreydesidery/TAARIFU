package com.taarifu.payments.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.payments.api.dto.InitiateTopUpRequest;
import com.taarifu.payments.api.dto.TopUpDto;
import com.taarifu.payments.application.service.TopUpService;
import com.taarifu.payments.domain.model.TopUp;
import com.taarifu.payments.domain.model.enums.WalletOwnerKind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Authenticated REST surface for a citizen's <b>own</b> mobile-money token top-ups (ADR-0015; PRD §23.6).
 *
 * <p>Responsibility: the thin HTTP layer for {@code POST /payments/top-ups} (initiate) and
 * {@code GET /payments/top-ups/{publicId}} (status). It resolves the buyer from the security context and
 * delegates to {@link TopUpService}, wrapping every result in the single {@link ApiResponse} envelope. No
 * business logic, no {@code @Transactional} (ARCHITECTURE.md §3.3).</p>
 *
 * <p><b>Own-only (PDPA, PRD §18):</b> the buyer id is taken from {@link CurrentUser#requirePublicId()} —
 * <b>never</b> from the body or a parameter — so a citizen can only ever top up and read their <i>own</i>
 * wallet's purchases.</p>
 *
 * <p><b>🔒 Fence (D18):</b> this surface only moves money to top up a convenience wallet; it exposes no path
 * to a binding democratic action and never gates on a token balance.</p>
 */
@RestController
@RequestMapping(path = "/payments/top-ups")
@Tag(name = "Top-up", description = "Buy convenience tokens via mobile money (never democratic weight — D18).")
public class TopUpController {

    private final TopUpService topUpService;
    private final ResponseFactory responses;

    /**
     * @param topUpService the initiate/status use cases.
     * @param responses    envelope builder.
     */
    public TopUpController(TopUpService topUpService, ResponseFactory responses) {
        this.topUpService = topUpService;
        this.responses = responses;
    }

    /**
     * Initiates a mobile-money token top-up for the authenticated citizen.
     *
     * @param request        the validated top-up request (rail, token amount, payer MSISDN).
     * @param idempotencyKey optional {@code Idempotency-Key} header; a replay returns the original top-up.
     *                       When absent, a server key is generated (a missing header is still safe — but a
     *                       client retry without the header may create a second attempt, so clients SHOULD
     *                       send one, ARCHITECTURE.md §5.4).
     * @return an envelope carrying the initiated {@link TopUpDto} (status {@code PENDING} once the rail
     *         accepts the push).
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Initiate a mobile-money token top-up",
            description = "Pushes an STK collection for the requested token amount; tokens are credited only "
                    + "after the provider-verified settlement callback (never trust-the-callback).")
    public ApiResponse<TopUpDto> initiate(
            @Valid @RequestBody InitiateTopUpRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        UUID buyerId = CurrentUser.requirePublicId();
        String key = (idempotencyKey == null || idempotencyKey.isBlank())
                ? UUID.randomUUID().toString() : idempotencyKey;
        TopUp topUp = topUpService.initiate(buyerId, WalletOwnerKind.USER, request.provider(),
                request.tokenAmount(), request.payerMsisdn(), key);
        return responses.ok(TopUpDto.from(topUp));
    }

    /**
     * Returns the status of one of the caller's own top-ups (polling).
     *
     * @param publicId the top-up public id.
     * @return an envelope carrying the {@link TopUpDto}; 404 if it does not exist or is not the caller's.
     */
    @GetMapping("/{publicId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get my top-up status",
            description = "Returns the status of the caller's own mobile-money top-up.")
    public ApiResponse<TopUpDto> getStatus(@PathVariable UUID publicId) {
        UUID buyerId = CurrentUser.requirePublicId();
        return responses.ok(TopUpDto.from(topUpService.getOwnTopUp(publicId, buyerId)));
    }
}
