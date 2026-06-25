package com.taarifu.payments.api.dto;

import com.taarifu.payments.domain.model.enums.MobileMoneyProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Request to initiate a mobile-money token top-up (ADR-0015; PRD §23.4/§23.6).
 *
 * <p>Responsibility: the validated input at the {@code POST /payments/top-ups} boundary. The buyer is the
 * authenticated caller (taken from the security context, never the body — so one cannot top up another's
 * wallet). Amount/token sizing is resolved server-side against the catalogue/policy; the client states the
 * rail and the payer number only.</p>
 *
 * <p><b>Privacy (PRD §18):</b> {@link #payerMsisdn} is sensitive PII — it is passed to the gateway adapter
 * and is <b>never</b> logged in full nor placed on any event/outbox payload.</p>
 *
 * @param provider    the mobile-money rail to collect on (required).
 * @param tokenAmount the number of convenience tokens to purchase (required, positive). Priced server-side.
 * @param payerMsisdn the payer's mobile-money number in E.164 (required); redacted in logs.
 */
public record InitiateTopUpRequest(
        @NotNull MobileMoneyProvider provider,
        @NotNull @Positive Long tokenAmount,
        @NotBlank String payerMsisdn
) {
}
