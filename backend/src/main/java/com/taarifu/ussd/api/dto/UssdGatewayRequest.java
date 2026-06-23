package com.taarifu.ussd.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The inbound USSD webhook payload posted by the aggregator on every keypress (PRD §14, EI-4).
 *
 * <p>Responsibility: the validated boundary input for one menu interaction. The aggregator sends the
 * {@code sessionId} (stable for the whole dialogue), the caller's {@code msisdn}, the optional
 * {@code serviceCode} (the dialled shortcode), and {@code text} — the <b>accumulated</b> keypress string
 * the aggregator builds across the dialogue, usually {@code *}-delimited (e.g. {@code "1*2*Maji"}). The
 * machine reads the <b>last</b> segment as this turn's input (it holds its own per-step state, so it does
 * not need the full history).</p>
 *
 * <p>WHY a generic shape rather than one vendor's exact field names: aggregators differ ({@code phoneNumber}
 * vs {@code msisdn}, etc.); keeping a neutral DTO here and mapping per-vendor in the adapter layer means no
 * vendor format leaks into the flow (DI7, ADR-0004). The MSISDN is <b>PII</b> (S-4).</p>
 *
 * @param sessionId   the aggregator session id (stable per dialogue); required.
 * @param msisdn      the caller's phone (E.164 or local; normalised in the machine); required, PII.
 * @param serviceCode the dialled shortcode/service code, if supplied; optional.
 * @param text        the accumulated keypress string ({@code *}-delimited); may be empty on the first hit.
 */
public record UssdGatewayRequest(
        @NotBlank @Size(max = 128) String sessionId,
        @NotBlank @Size(max = 20) String msisdn,
        @Size(max = 16) String serviceCode,
        @Size(max = 2000) String text
) {
}
