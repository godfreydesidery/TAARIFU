package com.taarifu.payments.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.pagination.PageMapper;
import com.taarifu.common.pagination.PageRequestFactory;
import com.taarifu.payments.api.PaymentQueryApi;
import com.taarifu.payments.api.dto.AdminPaymentDto;
import com.taarifu.payments.api.dto.PaymentTotalsDto;
import com.taarifu.payments.api.dto.RefundTopUpRequest;
import com.taarifu.payments.application.service.RefundService;
import com.taarifu.payments.domain.model.enums.MobileMoneyProvider;
import com.taarifu.payments.domain.model.enums.TopUpStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ADMIN REST surface for the payments operations console — list/search payments, window totals, a single
 * payment's status, and refund/void actions (ADR-0015 addendum; PRD §18, §23.5, §25.10).
 *
 * <p>Responsibility: the thin HTTP layer for operations on mobile-money top-ups. Every endpoint is gated by
 * {@code hasRole('ADMIN')} (deny-by-default method security; ROLE hierarchy ROOT &gt; ADMIN &gt; MODERATOR,
 * so ROOT inherits). Reads delegate to {@link PaymentQueryApi}; refund/void delegate to {@link RefundService}.
 * No business logic, no {@code @Transactional} (CLAUDE.md §8).</p>
 *
 * <p><b>Privacy (PRD §18):</b> nothing returned carries a secret or PII — no MSISDN (never stored), no
 * national/voter ID; the buyer is an opaque account UUID. The acting administrator is taken from the security
 * context, never the body.</p>
 *
 * <p><b>🔒 Fence (D18):</b> a money-movement admin surface only — refund/void touch the convenience wallet
 * exclusively (never a role/vote/weight), and no endpoint here reads or gates on a token balance.</p>
 */
@RestController
@RequestMapping(path = "/admin/payments")
@Tag(name = "Admin — Payments", description = "Operations console for mobile-money top-ups (ROLE_ADMIN).")
public class PaymentAdminController {

    private final PaymentQueryApi paymentQuery;
    private final RefundService refundService;
    private final ResponseFactory responses;
    private final PageRequestFactory pageRequests;
    private final PageMapper pageMapper;

    /**
     * @param paymentQuery  the published admin read port (search/totals/get).
     * @param refundService the refund/void use cases.
     * @param responses     envelope builder.
     * @param pageRequests  bounded {@link Pageable} factory.
     * @param pageMapper    {@code Page → PageMeta} mapper.
     */
    public PaymentAdminController(PaymentQueryApi paymentQuery, RefundService refundService,
                                  ResponseFactory responses, PageRequestFactory pageRequests,
                                  PageMapper pageMapper) {
        this.paymentQuery = paymentQuery;
        this.refundService = refundService;
        this.responses = responses;
        this.pageRequests = pageRequests;
        this.pageMapper = pageMapper;
    }

    /**
     * Lists/searches top-ups by optional status / provider / created-at window, paged + newest first.
     *
     * @param status   optional status filter ({@code null} = any).
     * @param provider optional rail filter ({@code null} = any).
     * @param from     optional inclusive lower bound on creation instant (ISO-8601; {@code null} = open).
     * @param to       optional inclusive upper bound on creation instant (ISO-8601; {@code null} = open).
     * @param page     zero-based page index.
     * @param size     page size (capped at {@link PageRequestFactory#MAX_SIZE}).
     * @param sort     sort expression (default {@code createdAt,desc}).
     * @return {@code 200} + the paged {@link AdminPaymentDto}s with pagination {@code meta}.
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List/search payments",
            description = "Filter by status/provider/date window. ROLE_ADMIN. No MSISDN/secret/PII in the view.")
    public ApiResponse<List<AdminPaymentDto>> list(
            @RequestParam(required = false) TopUpStatus status,
            @RequestParam(required = false) MobileMoneyProvider provider,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<AdminPaymentDto> result = paymentQuery.search(status, provider, from, to, pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    /**
     * Returns summary totals (counts + net settled/refunded money) over the same optional filter window.
     *
     * @param provider optional rail filter ({@code null} = any).
     * @param from     optional inclusive lower bound on creation instant (ISO-8601; {@code null} = open).
     * @param to       optional inclusive upper bound on creation instant (ISO-8601; {@code null} = open).
     * @return {@code 200} + the {@link PaymentTotalsDto}.
     */
    @GetMapping("/totals")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Payment totals",
            description = "Counts + net settled/refunded amounts (minor units) over the filter window. ROLE_ADMIN.")
    public ApiResponse<PaymentTotalsDto> totals(
            @RequestParam(required = false) MobileMoneyProvider provider,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return responses.ok(paymentQuery.totals(provider, from, to));
    }

    /**
     * Returns the full admin view of one top-up.
     *
     * @param publicId the top-up public id.
     * @return {@code 200} + the {@link AdminPaymentDto}; 404 if it does not exist.
     */
    @GetMapping("/{publicId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get one payment", description = "Full admin view of one top-up. ROLE_ADMIN.")
    public ApiResponse<AdminPaymentDto> get(@PathVariable UUID publicId) {
        return responses.ok(paymentQuery.get(publicId));
    }

    /**
     * Refunds a settled top-up — reverses the convenience-token credit and moves SUCCEEDED → REFUNDED
     * (idempotent; D18-safe — never touches democratic weight).
     *
     * @param publicId the top-up public id.
     * @param request  the validated refund request (audit reason).
     * @return {@code 200} + the refunded {@link AdminPaymentDto}; 409 if not in a refundable state.
     */
    @PostMapping("/{publicId}/refund")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Refund a settled top-up",
            description = "SUCCEEDED → REFUNDED; reverses the convenience-token credit (idempotent, D18). "
                    + "ROLE_ADMIN.")
    public ApiResponse<AdminPaymentDto> refund(@PathVariable UUID publicId,
                                               @Valid @RequestBody RefundTopUpRequest request) {
        return responses.ok(AdminPaymentDto.from(refundService.refund(publicId, request.reason())));
    }

    /**
     * Voids an un-settled top-up attempt — cancels an INITIATED/PENDING collection with no wallet effect
     * (INITIATED/PENDING → VOIDED).
     *
     * @param publicId the top-up public id.
     * @param request  the validated void request (audit reason).
     * @return {@code 200} + the voided {@link AdminPaymentDto}; 409 if already settlement-terminal.
     */
    @PostMapping("/{publicId}/void")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Void an un-settled top-up",
            description = "INITIATED/PENDING → VOIDED; cancels with no wallet effect. ROLE_ADMIN.")
    public ApiResponse<AdminPaymentDto> voidTopUp(@PathVariable UUID publicId,
                                                  @Valid @RequestBody RefundTopUpRequest request) {
        return responses.ok(AdminPaymentDto.from(refundService.voidTopUp(publicId, request.reason())));
    }
}
